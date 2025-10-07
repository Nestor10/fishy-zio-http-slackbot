package com.nestor10.slackbot.infrastructure.observability

import com.nestor10.slackbot.conf.AppConfig
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.Instrument
import zio.telemetry.opentelemetry.metrics

/** Metrics layer with instrumentation scope name from config */
object Metrics:

  val live =
    ZLayer.service[AppConfig].flatMap { config =>
      val serviceName = config.get.otel.map(_.serviceName).getOrElse("slack-bot")
      OpenTelemetry.metrics(instrumentationScopeName = serviceName)
    }
