package com.nestor10.slackbot.infrastructure.observability

import com.nestor10.slackbot.conf.AppConfig
import io.opentelemetry.api
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry

/** OpenTelemetry SDK layer - builds complete SDK with trace + metric exporters */
object OtelSdk:

  /** Create OpenTelemetry SDK from config (real OTLP export) */
  val live: ZLayer[AppConfig, Throwable, api.OpenTelemetry] =
    ZLayer.fromZIO {
      ZIO.serviceWith[AppConfig](_.otel).flatMap {
        case Some(otelCfg) =>
          ZIO.logInfo(s"Building OpenTelemetry SDK - exporting to ${otelCfg.otlpEndpoint}") *>
            ZIO.succeed(
              OpenTelemetry.custom(
                for {
                  // Trace exporter
                  spanExporter <- ZIO.fromAutoCloseable(
                    ZIO.succeed(
                      OtlpGrpcSpanExporter
                        .builder()
                        .setEndpoint(otelCfg.otlpEndpoint)
                        .build()
                    )
                  )

                  // Metric exporter
                  metricExporter <- ZIO.fromAutoCloseable(
                    ZIO.succeed(
                      OtlpGrpcMetricExporter
                        .builder()
                        .setEndpoint(otelCfg.otlpEndpoint)
                        .build()
                    )
                  )

                  // Resource (service name)
                  resource = Resource.create(
                    Attributes.of(
                      AttributeKey.stringKey("service.name"),
                      otelCfg.serviceName
                    )
                  )

                  // Tracer provider
                  tracerProvider <- ZIO.fromAutoCloseable(
                    ZIO.succeed(
                      SdkTracerProvider
                        .builder()
                        .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                        .setResource(resource)
                        .build()
                    )
                  )

                  // Meter provider
                  meterProvider <- ZIO.fromAutoCloseable(
                    ZIO.succeed(
                      SdkMeterProvider
                        .builder()
                        .registerMetricReader(
                          PeriodicMetricReader
                            .builder(metricExporter)
                            .setInterval(java.time.Duration.ofSeconds(10))
                            .build()
                        )
                        .setResource(resource)
                        .build()
                    )
                  )

                  // Complete SDK
                  openTelemetry <- ZIO.fromAutoCloseable(
                    ZIO.succeed(
                      OpenTelemetrySdk
                        .builder()
                        .setTracerProvider(tracerProvider)
                        .setMeterProvider(meterProvider)
                        .build()
                    )
                  )
                } yield openTelemetry
              )
            )
        case None =>
          ZIO.fail(new IllegalStateException("OpenTelemetry config required"))
      }
    }.flatten

  /** No-op SDK (no export) */
  val noop: ULayer[api.OpenTelemetry] =
    ZLayer.succeed(api.OpenTelemetry.noop())

  /** Auto-fallback: tries live, falls back to noop on failure */
  val layer: ZLayer[AppConfig, Nothing, api.OpenTelemetry] =
    live
      .tapError(err =>
        ZIO.logWarning(
          s"OpenTelemetry SDK failed: ${err.getMessage} - using no-op (no telemetry export)"
        )
      )
      .orElse(noop)
