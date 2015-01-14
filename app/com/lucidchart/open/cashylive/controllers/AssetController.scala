package com.lucidchart.open.cashylive.controllers

import com.lucidchart.open.cashylive.Contexts

import play.api.libs.iteratee.Enumerator
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import play.api.mvc._
import play.api.Play.configuration
import play.api.Play.current
import play.api.Logger

import scala.collection.JavaConversions
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

class UnsetHostException extends Exception("Host header not set")
class UnknownHostException(host: String) extends Exception("Unknown host " + host)

class AssetController extends AppController {
  /**
   * Proxy the request to the appropriate S3 bucket.
   * If the client accepts gzip encoding, return that version instead (if available).
   */
  def gzProxy(name: String) = Action.async { implicit request =>
    implicit val ec = Contexts.gzipProxyContext
    try {
      val host = requestHostNoPort(request)
      val s3Bucket = bucketForHost(host)

      val originalUrl = s"https://s3.amazonaws.com/$s3Bucket/$name"
      val gzippedUrl = s"$originalUrl.gz"

      val originalRequest = proxyRequest(originalUrl, request)

      if (!requestSupportsGzip(request)) {
        proxyResponse(originalRequest)
      }
      else {
        val gzippedRequest = proxyRequest(gzippedUrl, request)

        Future.sequence(List(originalRequest, gzippedRequest)).map { responses =>
          val originalResponse = responses(0)
          val gzippedResponse = responses(1)

          val correctResponse = if (AssetController.successCodes.contains(gzippedResponse._1.status)) {
            gzippedResponse
          }
          else {
            originalResponse
          }

          proxyResponse(correctResponse._1, correctResponse._2)
        }


        // val p1 = Promise[(WSResponseHeaders, Enumerator[Array[Byte]])]()

        // gzippedRequest.map { case (response, body) =>
        //   if (200, 304) {
        //     p1.success(response, body)
        //   }
        //   else {
        //     originalRequest.map { case(response, body) =>
        //       p1.success(response, body)
        //     }
        //   }
        // }

      }
    }
    catch {
      case e: Exception => {
        Logger.error("Error while getting resource " + name)
        Future.successful {
          BadGateway
        }
      }
    }
  }

  /**
   * Create a proxy request
   * Pass all the headers both ways
   */
  private def proxyRequest(url: String, request: Request[_])(implicit ec: ExecutionContext) = {
    // initialize
    val wsNoHeaders = WS.url(url).withFollowRedirects(false)

    val applicableHeaders = request.headers.toMap - "Host" - "Connection"

    // copy headers
    val ws = applicableHeaders.foldLeft(wsNoHeaders) { case (ws, (key, value)) =>
      ws.withHeaders(key -> value.head)
    }

    // stream response
    ws.getStream()
  }

  private def proxyResponse(response: WSResponseHeaders, body: Enumerator[Array[Byte]]): Result = {
    // extract headers
    val responseHeaders = response.headers.map { case (key, value) =>
      (key -> value.head)
    }

    if (response.status == 200) {
      // Get the content type
      val contentType = response.headers.get("Content-Type").flatMap(_.headOption).getOrElse("application/octet-stream")

      // If there's a content length, send that, otherwise return the body chunked
      val proxiedResponse = response.headers.get("Content-Length") match {
        case Some(Seq(length)) => {
          // Result(ResponseHeader(response.status, responseHeaders), body).as(contentType).withHeaders("Content-Length" -> length)
          Ok.feed(body).as(contentType).withHeaders("Content-Length" -> length)
        }
        case _ => {
          // Result(ResponseHeader(response.status, responseHeaders), body).as(contentType)
          Ok.chunked(body).as(contentType)
        }
      }

      val applicableHeaders = responseHeaders - "Content-Type" - "Content-Length"
      applicableHeaders.foldLeft(proxiedResponse) { case (response, (key, value)) =>
        response.withHeaders(key -> value)
      }
    }
    else {
      Result(ResponseHeader(response.status, responseHeaders), body)
    }
  }

  private def proxyResponse(response: Future[(WSResponseHeaders, Enumerator[Array[Byte]])])(implicit ec: ExecutionContext): Future[Result] = {
    response.map { case (response, body) =>
      proxyResponse(response, body)
    }
  }

  /**
   * Get the associated s3 bucket given the host
   */
  private def bucketForHost(host: String) = {
    AssetController.hostMapping.get(host).getOrElse {
      throw new UnknownHostException(host)
    }
  }

  /**
   * extract the host header from the request, and pull off the port number
   */
  private def requestHostNoPort(request: Request[_]) = {
    val hostWithPort = request.headers.get("Host").getOrElse {
      throw new UnsetHostException
    }

    val portIndex = hostWithPort.indexOf(':')
    if (portIndex == -1) {
      hostWithPort
    }
    else {
      hostWithPort.substring(0, portIndex)
    }
  }

  /**
   * find out whether the requester supports gzip
   */
  private def requestSupportsGzip(request: Request[_]) = {
    request.headers.get("Accept-Encoding").map { encoding =>
      encoding.split(',').contains("gzip")
    }.getOrElse(false)
  }
}

object AssetController extends AssetController {
  val hostMapping = JavaConversions.iterableAsScalaIterable(
    configuration.getConfigList("hostmapping").get
  ).map { c =>
    (c.getString("host").get -> c.getString("bucket").get)
  }.toMap

  val successCodes = Set(200, 304)
}
