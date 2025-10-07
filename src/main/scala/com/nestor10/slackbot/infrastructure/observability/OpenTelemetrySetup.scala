package com.nestor10.slackbot.infrastructure.observability

import com.nestor10.slackbot.conf.AppConfig
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.Instrument
import zio.telemetry.opentelemetry.tracing.Tracing

/** OpenTelemetry configuration for Slack bot.
  *
  * Following Zio-telemetry.md Section 4 Pure Manual Instrumentation pattern:
  *   - Uses OpenTelemetry.custom for SDK configuration
  *   - Uses OpenTelemetry.contextZIO for FiberRef-based context
  *   - Uses OpenTelemetry.tracing() for Tracing service
  *   - Uses OpenTelemetry.metrics() for Meter service (Phase 13)
  *
  * Based on zio-opentelemetry 3.1.10
  *
  * Usage:
  * ```
  * myApp.provide(
  *   OpenTelemetrySetup.live
  * )
  * ```
  */
object OpenTelemetrySetup:

  /** Create OpenTelemetry SDK with OTLP exporter for traces and metrics.
    *
    * This is a Scoped ZIO that:
    *   1. Creates OTLP gRPC span exporter (traces) 2. Creates OTLP gRPC metric exporter (metrics)
    *      3. Configures batch span processor 4. Configures periodic metric reader 5. Sets up
    *      resource attributes 6. Builds OpenTelemetry SDK
    */
  def otelSdk(cfg: AppConfig): ZIO[Scope, Throwable, io.opentelemetry.api.OpenTelemetry] =
    ZIO.acquireRelease {
      ZIO.attempt {
        val spanExporter = OtlpGrpcSpanExporter
          .builder()
          .setEndpoint(cfg.otel.otlpEndpoint)
          .build()

        val metricExporter = OtlpGrpcMetricExporter
          .builder()
          .setEndpoint(cfg.otel.otlpEndpoint)
          .build()

        val resource = Resource.create(
          Attributes.of(
            AttributeKey.stringKey("service.name"),
            cfg.otel.serviceName
          )
        )

        val tracerProvider = SdkTracerProvider
          .builder()
          .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
          .setResource(resource)
          .build()

        val meterProvider = SdkMeterProvider
          .builder()
          .registerMetricReader(
            PeriodicMetricReader
              .builder(metricExporter)
              .setInterval(java.time.Duration.ofSeconds(10))
              .build()
          )
          .setResource(resource)
          .build()

        val sdk = OpenTelemetrySdk
          .builder()
          .setTracerProvider(tracerProvider)
          .setMeterProvider(meterProvider)
          .build()

        ZIO.logInfo(
          s"OpenTelemetry SDK initialized with traces + metrics"
        ) @@
          LogContext.app @@
          LogContext.operation("otel_init") *>
          ZIO.succeed(sdk)
      }.flatten
    } { sdk =>
      ZIO.attempt(sdk.close()).ignore *>
        ZIO.logInfo("OpenTelemetry SDK shut down") @@
        LogContext.app
    }

  /** Complete OpenTelemetry layer stack for tracing only (existing).
    *
    * Requires AppConfig to be provided in the layer stack.
    *
    * {{{
    * myApp.provide(
    *   AppConfig.layer,
    *   OpenTelemetrySetup.live
    * )
    * }}}
    */
  val live: ZLayer[AppConfig, Throwable, Tracing] =
    ZLayer.makeSome[AppConfig, Tracing](
      ZLayer.fromZIO(ZIO.service[AppConfig]).flatMap { cfg =>
        OpenTelemetry.custom(otelSdk(cfg.get[AppConfig]))
      },
      OpenTelemetry.contextZIO,
      ZLayer.fromZIO(ZIO.service[AppConfig]).flatMap { cfg =>
        OpenTelemetry.tracing(cfg.get[AppConfig].otel.instrumentationScopeName)
      }
    )

  /** Complete OpenTelemetry layer stack for tracing + metrics (Phase 13).
    *
    * Provides both Tracing and Meter services for manual instrumentation. Requires AppConfig to be
    * provided in the layer stack.
    *
    * {{{
    * myApp.provide(
    *   AppConfig.layer,
    *   OpenTelemetrySetup.liveWithMetrics
    * )
    * }}}
    */
  val liveWithMetrics: ZLayer[AppConfig, Throwable, Tracing & Meter & Instrument.Builder] =
    ZLayer.makeSome[AppConfig, Tracing & Meter & Instrument.Builder](
      ZLayer.fromZIO(ZIO.service[AppConfig]).flatMap { cfg =>
        OpenTelemetry.custom(otelSdk(cfg.get[AppConfig]))
      },
      OpenTelemetry.contextZIO,
      ZLayer.fromZIO(ZIO.service[AppConfig]).flatMap { cfg =>
        val appCfg = cfg.get[AppConfig]
        OpenTelemetry.tracing(appCfg.otel.instrumentationScopeName)
      },
      ZLayer.fromZIO(ZIO.service[AppConfig]).flatMap { cfg =>
        val appCfg = cfg.get[AppConfig]
        OpenTelemetry.metrics(appCfg.otel.instrumentationScopeName)
      }
    )
