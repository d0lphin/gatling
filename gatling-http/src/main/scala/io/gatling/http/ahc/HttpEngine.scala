/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.ahc

import java.util.{ ArrayList => JArrayList }
import java.util.concurrent.{ TimeUnit, Executors, ThreadFactory }

import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.HashedWheelTimer
import io.netty.util.internal.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }

import org.asynchttpclient.{ DefaultAsyncHttpClient, AsyncHttpClient, AsyncHttpClientConfig, Request }
import org.asynchttpclient.providers.netty4.NettyAsyncHttpProviderConfig
import org.asynchttpclient.providers.netty4.channel.pool.DefaultChannelPool
import org.asynchttpclient.ws.{ WebSocketListener, WebSocketUpgradeHandler }
import org.asynchttpclient.providers.netty4.ws.NettyWebSocket
import org.asynchttpclient.providers.netty4.NettyAsyncHttpProviderConfig.{ LazyResponseBodyPartFactory, NettyWebSocketFactory }
import com.typesafe.scalalogging.StrictLogging

import akka.actor.ActorRef
import io.gatling.core.ConfigKeys
import io.gatling.core.akka.AkkaDefaults
import io.gatling.core.config.GatlingConfiguration.configuration
import io.gatling.core.controller.{ Controller, ThrottledRequest }
import io.gatling.core.session.{ Session, SessionPrivateAttributes }
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.http.action.ws.{ OnFailedOpen, WsListener }
import io.gatling.http.config.HttpProtocol
import io.gatling.http.request.HttpRequest
import io.gatling.http.response.ResponseBuilder
import io.gatling.http.util.SSLHelper.{ RichAsyncHttpClientConfigBuilder, newKeyManagers, newTrustManagers }
import io.gatling.http.check.ws.WsCheck
import io.gatling.core.check.CheckResult

object HttpTx {

  def silent(request: HttpRequest, primary: Boolean): Boolean = {

      def silentBecauseProtocolSilentResources = !primary && request.config.protocol.requestPart.silentResources

      def silentBecauseProtocolSilentURI: Option[Boolean] = request.config.protocol.requestPart.silentURI
        .map(_.matcher(request.ahcRequest.getUrl).matches)

    request.config.silent.orElse(silentBecauseProtocolSilentURI).getOrElse(silentBecauseProtocolSilentResources)
  }
}

case class HttpTx(session: Session,
                  request: HttpRequest,
                  responseBuilderFactory: Request => ResponseBuilder,
                  next: ActorRef,
                  primary: Boolean = true,
                  redirectCount: Int = 0,
                  update: Session => Session = Session.Identity) {

  val silent: Boolean = HttpTx.silent(request, primary)
}

case class WsTx(session: Session,
                request: Request,
                requestName: String,
                protocol: HttpProtocol,
                next: ActorRef,
                start: Long,
                reconnectCount: Int = 0,
                check: Option[WsCheck] = None,
                pendingCheckSuccesses: List[CheckResult] = Nil,
                updates: List[Session => Session] = Nil) {

  def applyUpdates(session: Session) = {
    val newSession = session.update(updates)
    copy(session = newSession, updates = Nil)
  }
}

object HttpEngine extends AkkaDefaults with StrictLogging {

  private var _instance: Option[HttpEngine] = None

  def start(): Unit = {
    if (!_instance.isDefined) {
      val client = new HttpEngine
      _instance = Some(client)
      system.registerOnTermination(stop())
    }
  }

  def stop(): Unit = {
    _instance.map { engine =>
      engine.applicationThreadPool.shutdown()
      engine.nioThreadPool.shutdown()
    }
    _instance = None
  }

  def instance: HttpEngine = _instance match {
    case Some(engine) => engine
    case _            => throw new UnsupportedOperationException("HTTP engine hasn't been started")
  }
}

class HttpEngine extends AkkaDefaults with StrictLogging {

  val applicationThreadPool = Executors.newCachedThreadPool(new ThreadFactory {
    override def newThread(r: Runnable) = {
      val t = new Thread(r, "Netty Thread")
      t.setDaemon(true)
      t
    }
  })

  val nioThreadPool = Executors.newCachedThreadPool

  val nettyTimer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)
  nettyTimer.start()
  system.registerOnTermination(nettyTimer.stop())

  // set up Netty LoggerFactory for slf4j instead of default JDK
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  val channelPool = new DefaultChannelPool(configuration.http.ahc.pooledConnectionIdleTimeout,
    configuration.http.ahc.connectionTTL,
    configuration.http.ahc.allowPoolingSslConnections,
    nettyTimer)

  val nettyConfig = {
    val eventGroup = new NioEventLoopGroup
    system.registerOnTermination(eventGroup.shutdownGracefully())
    val nettyConfig = new NettyAsyncHttpProviderConfig
    nettyConfig.setEventLoopGroup(eventGroup)
    nettyConfig.setNettyTimer(nettyTimer)
    nettyConfig.setChannelPool(channelPool)
    nettyConfig.setHttpClientCodecMaxInitialLineLength(configuration.http.ahc.httpClientCodecMaxInitialLineLength)
    nettyConfig.setHttpClientCodecMaxHeaderSize(configuration.http.ahc.httpClientCodecMaxHeaderSize)
    nettyConfig.setHttpClientCodecMaxChunkSize(configuration.http.ahc.httpClientCodecMaxChunkSize)
    nettyConfig.setNettyWebSocketFactory(new NettyWebSocketFactory {
      override def newNettyWebSocket(channel: Channel, nettyConfig: NettyAsyncHttpProviderConfig): NettyWebSocket =
        new NettyWebSocket(channel, nettyConfig, new JArrayList[WebSocketListener](1))
    })
    nettyConfig.setKeepEncodingHeader(configuration.http.ahc.keepEncodingHeader)
    nettyConfig.setWebSocketMaxFrameSize(configuration.http.ahc.webSocketMaxFrameSize)
    nettyConfig.setBodyPartFactory(new LazyResponseBodyPartFactory)
    nettyConfig
  }

  val defaultAhcConfig = {
    val ahcConfigBuilder = new AsyncHttpClientConfig.Builder()
      .setAllowPoolingConnections(configuration.http.ahc.allowPoolingConnections)
      .setAllowPoolingSslConnections(configuration.http.ahc.allowPoolingSslConnections)
      .setCompressionEnforced(configuration.http.ahc.compressionEnforced)
      .setConnectTimeout(configuration.http.ahc.connectTimeout)
      .setPooledConnectionIdleTimeout(configuration.http.ahc.pooledConnectionIdleTimeout)
      .setReadTimeout(configuration.http.ahc.readTimeout)
      .setConnectionTTL(configuration.http.ahc.connectionTTL)
      .setIOThreadMultiplier(configuration.http.ahc.ioThreadMultiplier)
      .setMaxConnectionsPerHost(configuration.http.ahc.maxConnectionsPerHost)
      .setMaxConnections(configuration.http.ahc.maxConnections)
      .setMaxRequestRetry(configuration.http.ahc.maxRetry)
      .setRequestTimeout(configuration.http.ahc.requestTimeOut)
      .setUseProxyProperties(configuration.http.ahc.useProxyProperties)
      .setUserAgent(null)
      .setExecutorService(applicationThreadPool)
      .setAsyncHttpClientProviderConfig(nettyConfig)
      .setWebSocketTimeout(configuration.http.ahc.webSocketTimeout)
      .setUseRelativeURIsWithConnectProxies(configuration.http.ahc.useRelativeURIsWithConnectProxies)
      .setTimeConverter(ThreeTenBPConverter)
      .setAcceptAnyCertificate(configuration.http.ahc.acceptAnyCertificate)
      .setEnabledProtocols(configuration.http.ahc.httpsEnabledProtocols match {
        case Nil => null
        case ps  => ps.toArray
      })
      .setEnabledCipherSuites(configuration.http.ahc.httpsEnabledCipherSuites match {
        case Nil => null
        case ps  => ps.toArray
      })

    val trustManagers = configuration.http.ssl.trustStore
      .map(config => newTrustManagers(config.storeType, config.file, config.password, config.algorithm))

    val keyManagers = configuration.http.ssl.keyStore
      .map(config => newKeyManagers(config.storeType, config.file, config.password, config.algorithm))

    if (trustManagers.isDefined || keyManagers.isDefined)
      ahcConfigBuilder.setSSLContext(trustManagers, keyManagers)

    ahcConfigBuilder.build
  }

  def newAHC(session: Session): AsyncHttpClient = newAHC(Some(session))

  def newAHC(session: Option[Session]) = {
    val ahcConfig = session.flatMap { session =>

      val trustManagers = for {
        file <- session(ConfigKeys.http.ssl.trustStore.File).asOption[String]
        password <- session(ConfigKeys.http.ssl.trustStore.Password).asOption[String]
        storeType = session(ConfigKeys.http.ssl.trustStore.Type).asOption[String]
        algorithm = session(ConfigKeys.http.ssl.trustStore.Algorithm).asOption[String]
      } yield newTrustManagers(storeType, file, password, algorithm)

      val keyManagers = for {
        file <- session(ConfigKeys.http.ssl.keyStore.File).asOption[String]
        password <- session(ConfigKeys.http.ssl.keyStore.Password).asOption[String]
        storeType = session(ConfigKeys.http.ssl.keyStore.Type).asOption[String]
        algorithm = session(ConfigKeys.http.ssl.keyStore.Algorithm).asOption[String]
      } yield newKeyManagers(storeType, file, password, algorithm)

      trustManagers.orElse(keyManagers).map { _ =>
        logger.info(s"Setting a custom SSLContext for user ${session.userId}")
        new AsyncHttpClientConfig.Builder(defaultAhcConfig).setSSLContext(trustManagers, keyManagers).build
      }

    }.getOrElse(defaultAhcConfig)

    val client = new DefaultAsyncHttpClient(ahcConfig)
    system.registerOnTermination(client.close())
    client
  }

  lazy val DefaultAHC = newAHC(None)

  val AhcAttributeName = SessionPrivateAttributes.PrivateAttributePrefix + "http.ahc"

  def httpClient(session: Session, protocol: HttpProtocol): (Session, AsyncHttpClient) = {
    if (protocol.enginePart.shareClient)
      (session, DefaultAHC)
    else
      session(AhcAttributeName).asOption[AsyncHttpClient] match {
        case Some(client) => (session, client)
        case _ =>
          val httpClient = newAHC(session)
          (session.set(AhcAttributeName, httpClient), httpClient)
      }
  }

  def startHttpTransaction(tx: HttpTx): Unit = {

      def executeRequestSafe(client: AsyncHttpClient, ahcRequest: Request, handler: AsyncHandler): Unit =
        try {
          client.executeRequest(ahcRequest, handler)
        } catch {
          // there might be some corner cases where executeRequest throws an Exception and onThrowable wasn't notified
          // this works properly because we prevent multiple calls with an AtomicBoolean
          case e: Exception => handler.onThrowable(e)
        }

    val requestConfig = tx.request.config

    val (newTx, client) = {
      val (newSession, client) = httpClient(tx.session, requestConfig.protocol)
      (tx.copy(session = newSession), client)
    }

    val ahcRequest = newTx.request.ahcRequest
    val handler = new AsyncHandler(newTx)

    if (requestConfig.throttled)
      Controller ! ThrottledRequest(tx.session.scenarioName, () => executeRequestSafe(client, ahcRequest, handler))
    else
      executeRequestSafe(client, ahcRequest, handler)
  }

  def startWebSocketTransaction(tx: WsTx, wsActor: ActorRef): Unit = {
    val (newTx, client) = {
      val (newSession, client) = httpClient(tx.session, tx.protocol)
      (tx.copy(session = newSession), client)
    }

    try {
      val listener = new WsListener(newTx, wsActor)

      val handler = new WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build
      client.executeRequest(tx.request, handler)

    } catch {
      case e: Exception =>
        wsActor ! OnFailedOpen(newTx, e.getMessage, nowMillis)
    }
  }
}
