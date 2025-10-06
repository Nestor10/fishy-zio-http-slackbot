package com.nestor10.slackbot.infrastructure.observability

import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.Instrument
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributeKey

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

  private val serviceName = "slack-bot"
  private val instrumentationScopeName = "slack-bot-instrumentation"
  private val otlpEndpoint = "http://localhost:4317"

  /** Create OpenTelemetry SDK with OTLP exporter for traces and metrics.
    *
    * This is a Scoped ZIO that:
    *   1. Creates OTLP gRPC span exporter (traces) 2. Creates OTLP gRPC metric exporter (metrics)
    *      3. Configures batch span processor 4. Configures periodic metric reader 5. Sets up
    *      resource attributes 6. Builds OpenTelemetry SDK
    */
  val otelSdk: ZIO[Scope, Throwable, io.opentelemetry.api.OpenTelemetry] =
    ZIO.acquireRelease {
      ZIO.attempt {
        // 1. OTLP gRPC span exporter (traces)
        val spanExporter = OtlpGrpcSpanExporter
          .builder()
          .setEndpoint(otlpEndpoint)
          .build()

        // 2. OTLP gRPC metric exporter (metrics)
        val metricExporter = OtlpGrpcMetricExporter
          .builder()
          .setEndpoint(otlpEndpoint)
          .build()

        // 3. Resource with service name
        val resource = Resource.create(
          Attributes.of(
            AttributeKey.stringKey("service.name"),
            serviceName
          )
        )

        // 4. Tracer provider with batch processor
        val tracerProvider = SdkTracerProvider
          .builder()
          .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
          .setResource(resource)
          .build()

        // 5. Meter provider with periodic metric reader (10s export interval)
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

        // 6. Build SDK with both tracing and metrics
        val sdk = OpenTelemetrySdk
          .builder()
          .setTracerProvider(tracerProvider)
          .setMeterProvider(meterProvider)
          .build()

        ZIO.logInfo(
          s"🔭 OTEL: OpenTelemetry SDK initialized (endpoint=$otlpEndpoint, service=$serviceName) with traces + metrics"
        ) *> ZIO.succeed(sdk)
      }.flatten
    } { sdk =>
      ZIO.attempt(sdk.close()).ignore *>
        ZIO.logInfo("🔭 OTEL: OpenTelemetry SDK shut down")
    }

  /** Complete OpenTelemetry layer stack for tracing only (existing).
    *
    * {{{
    * myApp.provide(
    *   OpenTelemetrySetup.live
    * )
    * }}}
    */
  val live: ZLayer[Any, Throwable, Tracing] =
    ZLayer.make[Tracing](
      OpenTelemetry.custom(otelSdk),
      OpenTelemetry.contextZIO,
      OpenTelemetry.tracing(instrumentationScopeName)
    )

  /** Complete OpenTelemetry layer stack for tracing + metrics (Phase 13).
    *
    * Provides both Tracing and Meter services for manual instrumentation.
    *
    * {{{
    * myApp.provide(
    *   OpenTelemetrySetup.liveWithMetrics
    * )
    * }}}
    */
  val liveWithMetrics: ZLayer[Any, Throwable, Tracing & Meter & Instrument.Builder] =
    ZLayer.make[Tracing & Meter & Instrument.Builder](
      OpenTelemetry.custom(otelSdk),
      OpenTelemetry.contextZIO,
      OpenTelemetry.tracing(instrumentationScopeName),
      OpenTelemetry.metrics(instrumentationScopeName)
    )
