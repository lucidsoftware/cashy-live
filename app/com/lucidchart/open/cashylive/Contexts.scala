package com.lucidchart.open.cashylive

import com.lucidchart.open.cashylive.util.Executors

import play.api.Play.current
import play.api.Play.configuration

object Contexts {
  val gzipProxyContext = Executors.fixedThreadPool(configuration.getInt("context.gzip-proxy.size").get, "gzip-proxy")
}
