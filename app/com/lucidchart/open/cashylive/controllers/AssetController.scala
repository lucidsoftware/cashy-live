package com.lucidchart.open.cashylive.controllers

import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest

import com.lucidchart.open.cashylive.Contexts

import java.net.ConnectException
import java.util.concurrent.TimeoutException
import java.util.Date

import play.api.libs.iteratee.Enumerator
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import play.api.mvc._
import play.api.Play.configuration
import play.api.Play.current
import play.api.Logger

import scala.collection.JavaConversions
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.language.postfixOps

class UnsetHostException extends Exception("Host header not set")
class UnknownHostException(host: String) extends Exception("Unknown host " + host)

class AssetController extends AppController {
  private val logger = Logger(this.getClass)
  private val maxRetries = configuration.getInt("gzproxy.maxRetries").get
  private val s3Client = new AmazonS3Client(new ProfileCredentialsProvider())

  /**
   * The routes file cannot handle a *file without a /
   */
  def gzProxyRoot() = gzProxy("/")

  /**
   * Proxy the request to the appropriate S3 bucket.
   * If the client accepts gzip encoding, return that version instead (if available).
   */
  def gzProxy(name: String) = Action.async { implicit request =>
    implicit val ec = Contexts.gzipProxyContext
    val responsePromise = Promise[Result]()

    try {
      val host = requestHostNoPort(request)
      val info = infoForHost(host)

      val (originalUrl, gzippedUrl) = if (info.auth) {
        val originalUrl = signedUrl(info.bucket, name)
        val gzippedUrl = signedUrl(info.bucket, s"$name.gz")

        (originalUrl, gzippedUrl)
      }
      else {
        val s3Bucket = info.bucket
        val secureForward = AssetController.trustXForwardedProto && request.headers.get("X-Fowarded-Proto").map(_ == "https").getOrElse(false)
        val proto = if (request.secure || secureForward || AssetController.alwaysSecure) "https" else "http"

        val originalUrl = s"$proto://s3.amazonaws.com/$s3Bucket/$name"

        (originalUrl, s"$originalUrl.gz")
      }

      val originalRequest = proxyRequestWithRetry(originalUrl, request, maxRetries)

      /**
       * This gets called when the gzipped response didn't work out,
       * for whatever reason
       */
      def fallbackToOriginal {
        originalRequest.onFailure { case e =>
          logger.error(s"Error retrieving asset: $originalUrl", e)
          responsePromise.success(BadGateway)
        }

        originalRequest.onSuccess { case (response, body) =>
          responsePromise.success(proxyResponse(response, body))
        }
      }

      if (!requestSupportsGzip(request)) {
        logger.info(s"Requesting $originalUrl")
        fallbackToOriginal
      }
      else {
        logger.info(s"Requesting $gzippedUrl")
        val gzippedRequest = proxyRequestWithRetry(gzippedUrl, request, maxRetries)

        gzippedRequest.onFailure { case e =>
          logger.error(s"Error retrieving asset: $gzippedUrl", e)
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
        logger.error("Error while getting resource " + name)
        responsePromise.success(BadGateway)
      }
    }

    responsePromise.future.map { result =>
      result.withHeaders("Vary" -> "Accept-Encoding, Origin")
    }
  }

  /**
   * Make a request for a resource and retry until either a successful response is given or the
   * limit is reached. Pass all headers through both ways.
   *
   * @param url the url of the resource to request.
   * @param request the original request made for the resource
   * @param retriesRemaining the number of retries remaining
   * @return the response from cloudfront
   */
  private def proxyRequestWithRetry(
      url: String,
      request: Request[_],
      retriesRemaining: Int)(
      implicit ec: ExecutionContext): Future[(WSResponseHeaders, Enumerator[Array[Byte]])] = {
    if (retriesRemaining <= 0) {
      proxyRequest(url, request)
    }
    else {
      proxyRequest(url, request) flatMap { case (response, body) =>
        if (response.status >= 500) {
          proxyRequestWithRetry(url, request, retriesRemaining - 1)
        }
        else {
          Future.successful((response, body))
        }
      } recoverWith {
        case e @ (_: TimeoutException | _: ConnectException) => {
          proxyRequestWithRetry(url, request, retriesRemaining - 1)
        }
      }
    }
  }

  /**
   * Create a proxy request
   * Pass all the headers both ways
   */
  private def proxyRequest(
      url: String,
      request: Request[_])(
      implicit ec: ExecutionContext): Future[(WSResponseHeaders, Enumerator[Array[Byte]])] = {
    // initialize
    val wsNoHeaders = WS.url(url)

    val applicableHeaders = request.headers.toMap - "Host" - "Connection"

    // copy headers
    val ws = applicableHeaders.foldLeft(wsNoHeaders) { case (ws, (key, value)) =>
      ws.withHeaders(key -> value.head)
    }

    // stream response
    ws.getStream()
  }

  /**
   * Turn the response from the proxied request into a result
   * that play can handle.
   */
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

  /**
   * Turn the response from the proxied request into a result
   * that play can handle.
   */
  private def proxyResponse(response: Future[(WSResponseHeaders, Enumerator[Array[Byte]])])(implicit ec: ExecutionContext): Future[Result] = {
    response.map { case (response, body) =>
      proxyResponse(response, body)
    }
  }

  /**
   * Get a signed URL for an S3 item.
   * Always returns https urls. (always over ssl)
   */
  private def signedUrl(bucket: String, name: String) = {
    val expiration = new Date(System.currentTimeMillis + AssetController.s3SignedExpirationMS)
    val presignedRequest = new GeneratePresignedUrlRequest(bucket, name)
    presignedRequest.setMethod(HttpMethod.GET)
    presignedRequest.setExpiration(expiration)

    val signedUrl = s3Client.generatePresignedUrl(presignedRequest)
    signedUrl.toString
  }

  /**
   * Get the host info, given the host
   */
  private def infoForHost(host: String): HostInfo = {
    AssetController.hostMapping.get(host).getOrElse {
      throw new UnknownHostException(host)
    }
  }

  /**
   * extract the host header from the request, and pull off the port number
   */
  private def requestHostNoPort(request: Request[_]): String = {
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
  private def requestSupportsGzip(request: Request[_]): Boolean = {
    request.headers.get("Accept-Encoding").map { encoding =>
      encoding.split(',').contains("gzip")
    }.getOrElse(false)
  }
}

case class HostInfo(bucket: String, auth: Boolean)

object AssetController extends AssetController {
  val hostMapping = JavaConversions.iterableAsScalaIterable(
    configuration.getConfigList("hostmapping").get
  ).map { c =>
    (c.getString("host").get -> HostInfo(
      c.getString("bucket").get,
      c.getBoolean("auth").getOrElse(false)
    ))
  }.toMap

  val successCodes = Set(200, 304)
  val trustXForwardedProto = configuration.getBoolean("forward.trust.x-forwarded-proto").get
  val alwaysSecure = configuration.getBoolean("gzproxy.alwaysSecure").get
  val s3SignedExpirationMS = configuration.getLong("s3.signed.expirationMS").get
}
