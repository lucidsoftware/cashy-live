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
    val responsePromise = Promise[Result]()

    try {
      val host = requestHostNoPort(request)
      val s3Bucket = bucketForHost(host)
      val proto = if (request.secure) "https" else "http"

      val originalUrl = s"$proto://s3.amazonaws.com/$s3Bucket/$name"
      val gzippedUrl = s"$originalUrl.gz"

      val originalRequest = proxyRequest(originalUrl, request)

      /**
       * This gets called when the gzipped response didn't work out,
       * for whatever reason
       */
      def fallbackToOriginal {
        originalRequest.onFailure { case e =>
          Logger.error(s"Error retrieving asset: $originalUrl", e)
          responsePromise.success(BadGateway)
        }

        originalRequest.onSuccess { case (response, body) =>
          responsePromise.success(proxyResponse(response, body))
        }
      }

      if (!requestSupportsGzip(request)) {
        Logger.info(s"Requesting $originalUrl")
        fallbackToOriginal
      }
      else {
        Logger.info(s"Requesting $gzippedUrl")
        val gzippedRequest = proxyRequest(gzippedUrl, request)

        gzippedRequest.onFailure { case e =>
          fallbackToOriginal
        }

        gzippedRequest.onSuccess { case (response, body) =>
          if (AssetController.successCodes.contains(response.status)) {
            responsePromise.success(proxyResponse(response, body))
          }
          else {
            fallbackToOriginal
          }
        }
      }
    }
    catch {
      case e: Exception => {
        Logger.error("Error while getting resource " + name)
        responsePromise.success(BadGateway)
      }
    }

    responsePromise.future.map { result =>
      result.withHeaders("Vary" -> "Accept-Encoding")
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
    val garbageBody = new Status(response.status)

    // If there's a content length, send that, otherwise return the body chunked
    val filledBody = response.headers.get("Content-Length") match {
      case Some(Seq(length)) => {
        garbageBody.feed(body)
      }
      case _ => {
        garbageBody.chunked(body)
      }
    }

    response.headers.foldLeft(filledBody) { case (response, (key, values)) =>
      response.withHeaders(key -> values.head)
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
