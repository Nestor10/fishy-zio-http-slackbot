package com.nestor10.slackbot.infrastructure.observability

import com.nestor10.slackbot.conf.AppConfig
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing

/** Tracing layer with instrumentation scope name from config */
object Tracing:

  val live =
    ZLayer.service[AppConfig].flatMap { config =>
      val serviceName = config.get.otel.map(_.serviceName).getOrElse("slack-bot")
      OpenTelemetry.tracing(instrumentationScopeName = serviceName)
    }
