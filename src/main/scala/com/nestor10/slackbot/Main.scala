package com.nestor10.slackbot

import com.nestor10.slackbot.conf.AppConfig
import com.nestor10.slackbot.domain.service.MessageEventBus
import com.nestor10.slackbot.application.{SlackEventOrchestrator, ProcessorRegistry}
import com.nestor10.slackbot.infrastructure.socket.{SocketService, SocketManager}
import com.nestor10.slackbot.infrastructure.slack.{SlackApiClient, BotIdentityService}
import com.nestor10.slackbot.infrastructure.storage.MessageStore
import com.nestor10.slackbot.infrastructure.llm.LLMService
import com.nestor10.slackbot.domain.processor.{
  AiBotProcessor,
  AnalyticsProcessor,
  NotificationProcessor
}
import com.nestor10.slackbot.infrastructure.observability.{
  OpenTelemetrySetup,
  StorageMetrics,
  SocketMetrics,
  LLMMetrics,
  LogContext
}
import com.nestor10.slackbot.domain.model.socket.{SocketId, InboundQueue}
import com.nestor10.slackbot.domain.model.slack.{
  BusinessMessage,
  EventsApiMessage,
  InteractiveMessage,
  SlashCommand
}
import zio.config.typesafe.TypesafeConfigProvider
import zio.config.ConfigErrorOps // For prettyPrint extension method
import zio.http.netty.NettyConfig
import zio.telemetry.opentelemetry.OpenTelemetry // Phase 13: ZIO Metrics → OpenTelemetry bridge

import zio.http._
import zio.stream._
import zio.{Runtime, durationInt, _}

object Main extends ZIOAppDefault {

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
    ZIO.logInfo("Application starting") @@
      LogContext.app *> {

        // Load and validate configuration first (fail fast with pretty errors)
        val configLoad = ZIO
          .config(AppConfig.config)
          .tapError { error =>
            ZIO.logError("Configuration error:") @@
              LogContext.app *>
              ZIO.logError(error.prettyPrint()) @@
              LogContext.app
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
                  // Delegate all message processing to the SlackEventOrchestrator service
                  ZIO.serviceWithZIO[SlackEventOrchestrator](_.processMessage(message))
                }
                .buffer(16) // Pipeline: Buffer processed messages
                .tap(_ => ZIO.succeed(())) // Pipeline: Metrics/monitoring tap
                .runDrain // Sink: Consume all elements
                .forkScoped

              // Phase 3: Register processors and start worker fiber
              // Note: Storage metrics are automatically collected via ObservableGauge callbacks (every 10s)
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
              Scope.default, // Provide global scope for resource management
              clientConfigLayer,
              Client.live,
              ZLayer.succeed(NettyConfig.default),
              DnsResolver.default,
              inboundLayer,
              ZLayer.succeed(cfg), // Configuration (already validated)
              OpenTelemetrySetup.liveWithMetrics, // Phase 12/13: Distributed tracing + metrics (OTLP to Collector)
              OpenTelemetry.zioMetrics, // Phase 13: Export ZIO runtime metrics to OpenTelemetry (enables labeled metrics)
              SocketService.Live.layer,
              SlackApiClient.Live.layer,
              BotIdentityService.Live.layer, // Phase 8: Bot identity service (depends on SlackApiClient)
              SocketMetrics.layer, // Phase 13: Socket event recording (ZIO Metric API with labels - no dependencies)
              SocketManager.Live.layer, // Depends on SocketMetrics for event recording
              SocketMetrics.observableGaugesLayer, // Phase 13: Socket state gauges (ZIO Metric API - depends on SocketManager)
              MessageEventBus.Live.layer, // Phase 2: Event broadcasting (no dependencies)
              MessageStore.InMemory.layer, // Phase 1/7a: In-memory storage (depends on MessageEventBus)
              StorageMetrics.layer, // Phase 13: Storage metrics (ZIO Metric API polling gauges - depends on MessageStore)
              SlackEventOrchestrator.Live.layer, // Phase 4/7b/8: Orchestrator (depends on MessageStore + BotIdentityService)
              ProcessorRegistry.Live.layer, // Phase 3: Processor registry
              LLMService.configured, // Phase 6: LLM service (dynamic: Ollama or OpenAI based on config)
              LLMMetrics.Live.layer, // Phase 13: LLM metrics (requests, interruptions, latency, workers)
              AiBotProcessor.layer, // Phase 3/6b: AI bot with LLM integration
              AnalyticsProcessor.layer,
              NotificationProcessor.layer
            )
        }
      }
}
