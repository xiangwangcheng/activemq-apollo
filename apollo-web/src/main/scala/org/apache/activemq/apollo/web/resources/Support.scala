/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.web.resources

import java.lang.String
import javax.ws.rs._
import core.{UriInfo, Response, Context}
import com.sun.jersey.api.view.ImplicitProduces
import Response._
import Response.Status._
import java.util.concurrent.TimeUnit
import org.fusesource.hawtdispatch._
import org.fusesource.scalate.{NoValueSetException, RenderContext}
import com.sun.jersey.core.util.Base64
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import java.io.UnsupportedEncodingException
import org.apache.activemq.apollo.broker._
import security.{SecuredResource, Authorizer, SecurityContext, Authenticator}
import util.continuations._
import org.apache.activemq.apollo.util._
import java.net.{InetSocketAddress, URI}
import java.security.cert.X509Certificate

object Resource {

  val SECURITY_CONTEXT_ATTRIBUTE: String = classOf[SecurityContext].getName
  val HEADER_WWW_AUTHENTICATE: String = "WWW-Authenticate"
  val HEADER_AUTHORIZATION: String = "Authorization"
  val AUTHENTICATION_SCHEME_BASIC: String = "Basic"

  private def decode_base64(value: String): String = {
    var transformed: Array[Byte] = Base64.decode(value)
    try {
      return new String(transformed, "ISO-8859-1")
    } catch {
      case uee: UnsupportedEncodingException => {
        return new String(transformed)
      }
    }
  }

}

/**
 * Defines the default representations to be used on resources
 */
@ImplicitProduces(Array("text/html;qs=5"))
@Produces(Array("application/json", "application/xml","text/xml"))
abstract class Resource(parent:Resource=null) extends Logging {
  import Resource._

  @Context
  var uri_info:UriInfo = null
  @Context
  var http_request: HttpServletRequest = null

  if( parent!=null ) {
    copy(parent)
  }

  def copy(other:Resource) = {
    this.uri_info = other.uri_info
    this.http_request = other.http_request
  }

  def result(value:Status, message:Any=null):Nothing = {
    val response = Response.status(value)
    if( message!=null ) {
      response.entity(message)
    }
    throw new WebApplicationException(response.build)
  }

  def result[T](uri:URI):T = {
    throw new WebApplicationException(seeOther(uri).build)
  }

  def path(value:Any) = uri_info.getAbsolutePathBuilder().path(value.toString).build()

  def strip_resolve(value:String) = {
    new URI(uri_info.getAbsolutePath.resolve(value).toString.stripSuffix("/"))
  }


  def authorize[T](authenticator:Authenticator, authorizer:Authorizer, action:String, resource:SecuredResource, block: =>FutureResult[T]):FutureResult[T] = {
    if ( authenticator != null ) {
      val rc = FutureResult[T]()
      authenticate(authenticator) { security_context =>
        try {
          if (security_context != null) {
            if (authorizer.can(security_context, action, resource)) {
              block.onComplete(rc)
            } else {
              unauthroized
            }
          } else {
            unauthroized
          }
        } catch {
          case e:Throwable =>
            rc.apply(Failure(e))
        }
      }
      rc
    } else {
      block
    }
  }

  protected def monitoring[T](broker:Broker)(func: =>FutureResult[T]):FutureResult[T] = {
    authorize(broker.authenticator, broker.authorizer, "monitor", broker, func)
  }

  protected def admining[T](broker:Broker)(func: =>FutureResult[T]):FutureResult[T] = {
    authorize(broker.authenticator, broker.authorizer, "admin", broker, func)
  }

  protected def configing[T](broker:Broker)(func: =>FutureResult[T]):FutureResult[T] = {
    authorize(broker.authenticator, broker.authorizer, "config", broker, func)
  }

  protected def admining[T](host:VirtualHost)(func: =>FutureResult[T]):FutureResult[T] = {
    authorize(host.authenticator, host.authorizer, "admin", host, func)
  }
  protected def monitoring[T](host:VirtualHost)(func: =>FutureResult[T]):FutureResult[T] = {
    authorize(host.authenticator, host.authorizer, "monitor", host, func)
  }

  protected def admining[T](dest:Queue)(func: =>FutureResult[T]):FutureResult[T] = {
    authorize(dest.virtual_host.authenticator, dest.virtual_host.authorizer, "admin", dest, func)
  }
  protected def monitoring[T](dest:Queue)(func: =>FutureResult[T]):FutureResult[T] = {
    authorize(dest.virtual_host.authenticator, dest.virtual_host.authorizer, "monitor", dest, func)
  }

  protected def admining[T](dest:Topic)(func: =>FutureResult[T]):FutureResult[T] = {
    authorize(dest.virtual_host.authenticator, dest.virtual_host.authorizer,"admin", dest, func)
  }
  protected def monitoring[T](dest:Topic)(func: =>FutureResult[T]):FutureResult[T] = {
    authorize(dest.virtual_host.authenticator, dest.virtual_host.authorizer, "monitor", dest, func)
  }

  protected def authenticate[T](authenticator:Authenticator)(func: (SecurityContext)=>Unit): Unit = {

    var security_context = http_request.getAttribute(SECURITY_CONTEXT_ATTRIBUTE).asInstanceOf[SecurityContext]
    if( security_context!=null ) {
      func(security_context)
    } else {
      security_context = new SecurityContext
      security_context.local_address = new InetSocketAddress(http_request.getLocalAddr, http_request.getLocalPort)
      security_context.remote_address = new InetSocketAddress(http_request.getRemoteAddr, http_request.getRemotePort)
      security_context.certificates = http_request.getAttribute("javax.servlet.request.X509Certificate").asInstanceOf[Array[X509Certificate]]

      if(http_request.getAttribute("username")!=null) {
        security_context.user = http_request.getAttribute("username").asInstanceOf[String];
        security_context.password = http_request.getAttribute("password").asInstanceOf[String];
      } else if( http_request.getSession(false) !=null ) {
        val session = http_request.getSession(false)
        security_context.user = session.getAttribute("username").asInstanceOf[String];
        security_context.password = session.getAttribute("password").asInstanceOf[String];
      } else {
        var auth_header = http_request.getHeader(HEADER_AUTHORIZATION)
        if (auth_header != null && auth_header.length > 0) {
          auth_header = auth_header.trim
          var blank = auth_header.indexOf(' ')
          if (blank > 0) {
            var auth_type = auth_header.substring(0, blank)
            var auth_info = auth_header.substring(blank).trim
            if (auth_type.equalsIgnoreCase(AUTHENTICATION_SCHEME_BASIC)) {
              try {
                var srcString = decode_base64(auth_info)
                var i = srcString.indexOf(':')
                var username: String = srcString.substring(0, i)
                var password: String = srcString.substring(i + 1)


//            connection.transport match {
//              case t:SslTransport=>
//                security_context.certificates = Option(t.getPeerX509Certificates).getOrElse(Array[X509Certificate]())
//              case _ => None
//            }
                security_context.user = username
                security_context.password = password

              } catch {
                case e: Exception =>
              }
            }
          }
        }
      }
      reset {
        if( authenticator.authenticate(security_context) ) {
          http_request.setAttribute(SECURITY_CONTEXT_ATTRIBUTE, security_context)
          func(security_context)
        } else {
          func(null)
        }
      }
    }
  }

  protected def unauthroized = {
    val response = Response.status(HttpServletResponse.SC_UNAUTHORIZED)
    if( http_request.getHeader("AuthPrompt")!="false" && http_request.getSession(false)==null ) {
      // TODO: perhaps get the realm from the authenticator
      var http_realm = "Apollo"
      response.header(HEADER_WWW_AUTHENTICATE, AUTHENTICATION_SCHEME_BASIC + " realm=\"" + http_realm + "\"")
    }
    throw new WebApplicationException(response.build())
  }

  protected implicit def to_local_router(host:VirtualHost):LocalRouter = {
    host.router.asInstanceOf[LocalRouter]
  }

  def now = BrokerRegistry.list.headOption.map(_.now).getOrElse(System.currentTimeMillis())

  protected def with_broker[T](func: (org.apache.activemq.apollo.broker.Broker)=>FutureResult[T]):FutureResult[T] = {
    BrokerRegistry.list.headOption match {
      case Some(broker)=>
        sync(broker) {
          func(broker)
        }
      case None=>
        result(NOT_FOUND)
    }
  }

  protected def with_connector[T](id:String)(func: (org.apache.activemq.apollo.broker.Connector)=>FutureResult[T]):FutureResult[T] = {
    with_broker { broker =>
      broker.connectors.get(id) match {
        case Some(connector)=>
          func(connector)
        case None=> result(NOT_FOUND)
      }
    }
  }

  protected def with_virtual_host[T](id:String)(func: (VirtualHost)=>FutureResult[T]):FutureResult[T] = {
    with_broker { broker =>
      broker.virtual_hosts.valuesIterator.find( _.id == id) match {
        case Some(virtualHost)=>
          sync(virtualHost) {
            func(virtualHost)
          }
        case None=>
          result(NOT_FOUND)
      }
    }
  }

  protected def with_connection[T](id:Long)(func: BrokerConnection=>FutureResult[T]):FutureResult[T] = {
    with_broker { broker =>
      broker.connections.get(id) match {
        case Some(connection:BrokerConnection) =>
          sync(connection) {
            func(connection)
          }
        case None=>
          result(NOT_FOUND)
      }
    }
  }

}

object ViewHelper extends ViewHelper {

  val KB: Long = 1024
  val MB: Long = KB * 1024
  val GB: Long = MB * 1024
  val TB: Long = GB * 1024

  val SECONDS: Long = TimeUnit.SECONDS.toMillis(1)
  val MINUTES: Long = TimeUnit.MINUTES.toMillis(1)
  val HOURS: Long = TimeUnit.HOURS.toMillis(1)
  val DAYS: Long = TimeUnit.DAYS.toMillis(1)
  val YEARS: Long = DAYS * 365


}
class ViewHelper {
  import ViewHelper._

  lazy val uri_info = {
    try {
      RenderContext().attribute[UriInfo]("uri_info")
    } catch {
      case x:NoValueSetException =>
        RenderContext().attribute[Resource]("it").uri_info
    }
  }

  def path(value:Any) = {
    uri_info.getAbsolutePathBuilder().path(value.toString).build()
  }

  def strip_resolve(value:String) = {
    uri_info.getAbsolutePath.resolve(value).toString.stripSuffix("/")
  }


  def memory(value:Int):String = memory(value.toLong)
  def memory(value:Long):String = {

    if( value < KB ) {
      "%d bytes".format(value)
    } else if( value < MB ) {
       "%,.2f kb".format(value.toFloat/KB)
    } else if( value < GB ) {
      "%,.3f mb".format(value.toFloat/MB)
    } else if( value < TB ) {
      "%,.4f gb".format(value.toDouble/GB)
    } else {
      "%,.5f tb".format(value.toDouble/TB)
    }
  }

  def friendly_duration(duration:Long):String = {
    if( duration < SECONDS ) {
      "%d ms".format(duration)
    } else if (duration < MINUTES) {
      "%d seconds".format(duration / SECONDS)
    } else if (duration < HOURS) {
      "%d minutes".format(duration / MINUTES)
    } else if (duration < DAYS) {
      "%d hours %s".format(duration / HOURS, friendly_duration(duration%HOURS))
    } else if (duration < YEARS) {
      "%d days %s".format(duration / DAYS, friendly_duration(duration%DAYS))
    } else {
      "%,d years %s".format(duration / YEARS, friendly_duration(duration%YEARS))
    }
  }

  def uptime(value:Long):String = {
    friendly_duration(System.currentTimeMillis - value)
  }
}

