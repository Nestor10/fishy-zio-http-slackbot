package slacksocket.demo

import slacksocket.demo.conf.AppConfig
import slacksocket.demo.service.{
  SocketService,
  SlackApiClient,
  SocketManager,
  MessageProcessorService,
  MessageStore,
  MessageEventBus,
  ProcessorRegistry,
  LLMService
}
import slacksocket.demo.processor.{AiBotProcessor, AnalyticsProcessor, NotificationProcessor}
import slacksocket.demo.domain.socket.{SocketId, InboundQueue}
import slacksocket.demo.domain.slack.{
  BusinessMessage,
  EventsApiMessage,
  InteractiveMessage,
  SlashCommand
}
import zio.config.typesafe.TypesafeConfigProvider
import zio.config.ConfigErrorOps // For prettyPrint extension method
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

      // Load and validate configuration first (fail fast with pretty errors)
      val configLoad = ZIO
        .config(AppConfig.config)
        .tapError { error =>
          ZIO.logError("❌ Configuration Error:\n") *>
            ZIO.logError(error.prettyPrint())
        }
        .orDie // Config errors are defects - crash cleanly

      configLoad.flatMap { cfg =>
        val inboundLayer = ZLayer.fromZIO(Queue.unbounded[BusinessMessage])

        val program = ZIO.scoped {
          for {
            qIn <- ZIO.service[InboundQueue]

            consumerFiber <- ZStream
              .fromQueue(qIn) // Source: Pull from queue
              .buffer(32) // Pipeline: Buffer for chunk efficiency
              .mapZIOParUnordered(4) { message => // Pipeline: Parallel processing (4 concurrent)
                // Delegate all message processing to the MessageProcessorService service
                ZIO.serviceWithZIO[MessageProcessorService](_.processMessage(message))
              }
              .buffer(16) // Pipeline: Buffer processed messages
              .tap(_ => ZIO.succeed(())) // Pipeline: Metrics/monitoring tap
              .runDrain // Sink: Consume all elements
              .forkScoped

            // Start periodic stats logger (every 60 seconds)
            statsFiber <- (ZIO.serviceWithZIO[MessageStore](_.stats()).flatMap {
              case (msgCount, threadCount) =>
                ZIO.logInfo(s"📊 STORAGE_STATS: messages=$msgCount threads=$threadCount")
            } *> ZIO.sleep(60.seconds)).forever.forkScoped

            // Phase 3: Register processors and start worker fiber
            registry <- ZIO.service[ProcessorRegistry]
            aiBot <- ZIO.service[AiBotProcessor]
            analytics <- ZIO.service[AnalyticsProcessor]
            notifications <- ZIO.service[NotificationProcessor]

            _ <- registry.register(aiBot)
            _ <- registry.register(analytics)
            _ <- registry.register(notifications)

            // Start the processor worker fiber
            processorWorker <- registry.startProcessing

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
            ZLayer.succeed(cfg), // Configuration (already validated)
            SocketService.Live.layer,
            SlackApiClient.Live.layer,
            SocketManager.Live.layer,
            MessageStore.InMemory.layer, // Phase 1: In-memory storage
            MessageEventBus.Live.layer, // Phase 2: Event broadcasting
            MessageProcessorService.Live.layer,
            ProcessorRegistry.Live.layer, // Phase 3: Processor registry
            LLMService.configured, // Phase 6: LLM service (dynamic: Ollama or OpenAI based on config)
            AiBotProcessor.layer, // Phase 3/6b: AI bot with LLM integration
            AnalyticsProcessor.layer,
            NotificationProcessor.layer
          )
      }
    }
}
