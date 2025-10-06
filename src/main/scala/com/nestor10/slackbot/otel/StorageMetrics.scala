package com.nestor10.slackbot.otel

import zio.*
import zio.telemetry.opentelemetry.metrics.Meter
import com.nestor10.slackbot.infrastructure.storage.MessageStore

/** OpenTelemetry metrics for MessageStore.
  *
  * Uses ObservableGauge (callback-based) for:
  *   - storage.messages.count: Current message count
  *   - storage.threads.count: Current thread count
  *
  * Callbacks query MessageStore directly on each export (every 60s).
  */
object StorageMetrics {

  /** Register observable gauges that query MessageStore stats directly.
    *
    * This creates two gauges with callbacks that are invoked every 60 seconds by the
    * PeriodicMetricReader. The callbacks query MessageStore.stats() and record the current values.
    *
    * Returns a scoped resource (callbacks are registered until scope closes).
    */
  val live: RIO[Scope & MessageStore & Meter, Unit] =
    for {
      messageStore <- ZIO.service[MessageStore]
      meter <- ZIO.service[Meter]

      // Register observable gauge for message count
      _ <- meter.observableGauge(
        name = "storage.messages.count",
        unit = Some("{messages}"),
        description = Some("Current number of messages stored in MessageStore")
      ) { observation =>
        messageStore.stats().flatMap { case (msgCount, _) =>
          observation.record(msgCount.toDouble)
        }
      }

      // Register observable gauge for thread count
      _ <- meter.observableGauge(
        name = "storage.threads.count",
        unit = Some("{threads}"),
        description = Some("Current number of threads tracked in MessageStore")
      ) { observation =>
        messageStore.stats().flatMap { case (_, threadCount) =>
          observation.record(threadCount.toDouble)
        }
      }

    } yield ()

  /** Layer version - provides Unit (metrics are side-effecting registration) */
  val layer: RLayer[MessageStore & Meter, Unit] =
    ZLayer.scoped(live)
}
