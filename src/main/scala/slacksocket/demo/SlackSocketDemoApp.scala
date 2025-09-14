package slacksocket.demo

import slacksocket.demo.conf.AppConfig
import slacksocket.demo.service.{SocketService, SlackApiClient, SocketManager, MessageProcessor}
import slacksocket.demo.domain.socket.{SocketId, InboundQueue}
import slacksocket.demo.domain.slack.{
  BusinessMessage,
  EventsApiMessage,
  InteractiveMessage,
  SlashCommand
}
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.netty.NettyConfig

import zio.http._
import zio.stream._
import zio.{Runtime, durationInt, _}

object SlackSocketDemoApp extends ZIOAppDefault {

  private val clientConfig = Client.Config.default
    .webSocketConfig(
      WebSocketConfig.default
        .forwardPongFrames(true)
        .handshakeTimeout(15.seconds)
        .forceCloseTimeout(5.seconds)
        .forwardCloseFrames(true)
    )
    .connectionTimeout(30.seconds)
    .idleTimeout(120.seconds)

  private val clientConfigLayer = ZLayer.succeed(clientConfig)

  // Central config provider (resource path -> kebab-case keys) with default fallback (env, system props, etc.)
  private val configProvider =
    ConfigProvider.defaultProvider.orElse(
      TypesafeConfigProvider
        .fromResourcePath()
        .kebabCase
    )

  // Set config provider (standard pattern) so ZIO.config(AppConfig.config) works globally
  override val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.setConfigProvider(configProvider)

  val run: ZIO[Any, Throwable, Any] =
    ZIO.logInfo("🚀 APP: Application starting...") *> {
      val inboundLayer = ZLayer.fromZIO(Queue.unbounded[BusinessMessage])

      val program = ZIO.scoped {
        for {
          cfg <- ZIO.config(AppConfig.config)
          qIn <- ZIO.service[InboundQueue]

          consumerFiber <- ZStream
            .fromQueue(qIn) // Source: Pull from queue
            .buffer(32) // Pipeline: Buffer for chunk efficiency
            .mapZIOParUnordered(4) { message => // Pipeline: Parallel processing (4 concurrent)
              // Delegate all message processing to the MessageProcessor service
              ZIO.serviceWithZIO[MessageProcessor](_.processMessage(message))
            }
            .buffer(16) // Pipeline: Buffer processed messages
            .tap(_ => ZIO.succeed(())) // Pipeline: Metrics/monitoring tap
            .runDrain // Sink: Consume all elements
            .forkScoped

          socketManager <- ZIO.service[SocketManager]
          // Start the manager
          _ <- socketManager.startManager()

          // run forever
          _ <- consumerFiber.join

        } yield ()
      }

      // Add cleanup using ensuring - this runs regardless of success/failure/interruption
      program
        .provide(
          clientConfigLayer,
          Client.live,
          ZLayer.succeed(NettyConfig.default),
          DnsResolver.default,
          inboundLayer,
          SocketService.Live.layer,
          SlackApiClient.Live.layer,
          SocketManager.Live.layer,
          MessageProcessor.Live.layer
        )
    }
}
