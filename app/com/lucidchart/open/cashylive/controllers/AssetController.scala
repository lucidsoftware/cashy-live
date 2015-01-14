package com.lucidchart.open.cashylive.controllers

import com.lucidchart.open.cashylive.Contexts

import play.api.mvc._
import play.api.Play.current
import play.api.Play.configuration

import scala.collection.JavaConversions

class UnsetHostException extends Exception("Host header not set")
class UnknownHostException(host: String) extends Exception("Unknown host " + host)

class AssetController extends AppController {
  /**
   * Proxy the request to the appropriate S3 bucket.
   * If the client accepts gzip encoding, return that version instead (if available).
   */
  def gzProxy(name: String) = Action.async { implicit request =>
    val host = requestHostNoPort(request)
    val supportsGzip = requestSupportsGzip(request)
    val s3Bucket = bucketForHost(host)

    scala.concurrent.Future {
      Ok(name)
    }(Contexts.gzipProxyContext)
  }

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
}
