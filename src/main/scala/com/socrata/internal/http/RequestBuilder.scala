package com.socrata.internal.http

import java.io.InputStream

import com.rojoma.json.io.JsonEvent

class RequestBuilder private (val host: String,
                              val secure: Boolean,
                              val port: Int,
                              val path: Iterable[String],
                              val query: Iterable[(String, String)],
                              val headers: Iterable[(String, String)],
                              val method: Option[String],
                              val connectTimeoutMS: Option[Int],
                              val receiveTimeoutMS: Option[Int],
                              val timeoutMS: Option[Int]) {
  def copy(host: String = this.host,
           secure: Boolean = this.secure,
           port: Int = this.port,
           path: Iterable[String] = this.path,
           query: Iterable[(String, String)] = this.query,
           headers: Iterable[(String, String)] = this.headers,
           method: Option[String] = this.method,
           connectTimeoutMS: Option[Int] = this.connectTimeoutMS,
           receiveTimeoutMS: Option[Int] = this.receiveTimeoutMS,
           timeoutMS: Option[Int] = this.timeoutMS) =
    new RequestBuilder(host, secure, port, path, query, headers, method, connectTimeoutMS, receiveTimeoutMS, timeoutMS)

  def port(newPort: Int) = copy(port = newPort)

  def path(newPath: Seq[String]) = copy(path = newPath)

  def p(newPath: String*) = copy(path = newPath)

  def query(newQuery: Iterable[(String, String)]) = copy(query = newQuery)

  def q(newQuery: (String, String)*) = copy(query = newQuery)

  def addParameter(parameter: (String, String)) = copy(query = query.toVector :+ parameter)

  def headers(newHeaders: Iterable[(String, String)]) = copy(headers = newHeaders)

  def h(newHeaders: (String, String)*) = copy(headers = newHeaders)

  def addHeader(header: (String, String)) = copy(headers = headers.toVector :+ header)

  def method(newMethod: String) = copy(method = Some(newMethod))

  /** Sets the connection timeout.  Note that this is independent of any liveness ping check. */
  def connectTimeoutMS(newConnectTimeoutMS: Option[Int]) = copy(connectTimeoutMS = newConnectTimeoutMS)

  /** Sets the receive timeout -- if the HTTP client blocks for this many milliseconds without receiving
    * anything, an exception is thrown.  Note that this is independent of any liveness ping check. */
  def receiveTimeoutMS(newReceiveTimeoutMS: Option[Int]) = copy(receiveTimeoutMS = newReceiveTimeoutMS)

  /** Sets the whole-lifecycle timeout -- if the HTTP request lasts this many milliseconds, it will be
    * aborted.  Note that this is independent of any liveness ping check. */
  def timeoutMS(newTimeoutMS: Option[Int]) = copy(timeoutMS = newTimeoutMS)

  private def finish(methodIfNone: String) = method match {
    case Some(_) => this
    case None => copy(method = Some(methodIfNone))
  }

  def get = new BodylessHttpRequest(this.finish("GET"))

  def delete = new BodylessHttpRequest(this.finish("DELETE"))

  def form(contents: Iterable[(String, String)]) =
    new FormHttpRequest(this.finish("POST"), contents)

  /**
   * @note This does ''not'' take ownership of the input stream.  It must remain open for the
   *       duration of the HTTP request.
   */
  def file(contents: InputStream, file: String = "file", field: String = "file", contentType: String = "application/octet-stream") =
    new FileHttpRequest(this.finish("POST"), contents, file, field, contentType)

  /**
   * @note The iterator must remain valid for the duration of the HTTP request.
   */
  def json(contents: Iterator[JsonEvent]) =
    new JsonHttpRequest(this.finish("POST"), contents)

  def url = RequestBuilder.url(this)
}

object RequestBuilder {
  def apply(host: String, secure: Boolean = false) =
    new RequestBuilder(host, secure, if(secure) 443 else 80, Nil, Vector.empty, Vector.empty, None, None, None, None)

  private[this] val hexDigit = "0123456789ABCDEF".toCharArray
  private[this] val encPB = locally {
    val x = new Array[Boolean](256)
    for(c <- 'a' to 'z') x(c.toInt) = true
    for(c <- 'A' to 'Z') x(c.toInt) = true
    for(c <- '0' to '9') x(c.toInt) = true
    for(c <- ":@-._~!$&'()*+,;=") x(c.toInt) = true
    x
  }
  private[this] val encQB = locally {
    val x = new Array[Boolean](256)
    for(c <- 'a' to 'z') x(c.toInt) = true
    for(c <- 'A' to 'Z') x(c.toInt) = true
    for(c <- '0' to '9') x(c.toInt) = true
    for(c <- "-_.!~*'()") x(c.toInt) = true
    x
  }

  private def enc(sb: java.lang.StringBuilder, s: String, byteAllowed:Array[Boolean]) {
    val bs = s.getBytes("UTF-8")
    var i = 0
    while(i != bs.length) {
      val b = bs(i & 0xff)
      if(byteAllowed(b)) {
        sb.append(b.toChar)
      } else {
        sb.append('%').append(hexDigit(b >>> 4)).append(hexDigit(b & 0xf))
      }
      i += 1
    }
  }

  private def encP(sb: java.lang.StringBuilder, s: String) =
    enc(sb, s, encPB)

  private def encQ(sb: java.lang.StringBuilder, s: String) =
    enc(sb, s, encQB)

  private def url(req: RequestBuilder): String = {
    val sb = new java.lang.StringBuilder

    import req._

    def appendPath() {
      val it = path.iterator
      if(!it.hasNext) sb.append('/')
      else for(pathElement <- it) {
        sb.append('/')
        encP(sb, pathElement)
      }
    }

    def appendQuery() {
      def appendParameter(kv: (String, String)) = {
        encQ(sb, kv._1)
        sb.append('=')
        encQ(sb, kv._2)
      }

      if(query.nonEmpty) {
        sb.append('?')
        val it = query.iterator
        appendParameter(it.next())
        while(it.hasNext) {
          sb.append('&')
          appendParameter(it.next())
        }
      }
    }

    sb.append(if(secure) "https" else "http")
    sb.append("://")
    sb.append(host)
    sb.append(":")
    sb.append(port)
    appendPath()
    appendQuery()

    sb.toString
  }
}

sealed trait SimpleHttpRequest {
  val builder: RequestBuilder
}
class BodylessHttpRequest(val builder: RequestBuilder) extends SimpleHttpRequest
class FormHttpRequest(val builder: RequestBuilder, val contents: Iterable[(String, String)]) extends SimpleHttpRequest
class FileHttpRequest(val builder: RequestBuilder, val contents: InputStream, val file: String, val field: String, val contentType: String) extends SimpleHttpRequest
class JsonHttpRequest(val builder: RequestBuilder, val contents: Iterator[JsonEvent]) extends SimpleHttpRequest