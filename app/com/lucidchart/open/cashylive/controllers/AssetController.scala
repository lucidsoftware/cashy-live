package com.lucidchart.open.cashylive.controllers

import play.api.mvc._

class AssetController extends AppController {
  /**
   * Proxy the request to the appropriate S3 bucket.
   * If the client accepts gzip encoding, return that version instead (if available).
   */
  def gzProxy(name: String) = Action { implicit request =>
    Ok(name)
  }
}

object AssetController extends AssetController
