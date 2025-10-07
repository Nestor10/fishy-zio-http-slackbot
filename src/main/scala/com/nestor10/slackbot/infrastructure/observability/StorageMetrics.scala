package com.nestor10.slackbot.infrastructure.observability

import com.nestor10.slackbot.infrastructure.storage.MessageStore
import zio.*
import zio.metrics.*
import zio.stream.*

/** ZIO Runtime metrics for MessageStore.
  *
  * Uses ZIO Metric API with polling gauges for:
  *   - storage_messages_count: Current message count
  *   - storage_threads_count: Current thread count
  *
  * Polls MessageStore.stats() every 10 seconds to update gauge values.
  *
  * Zionomicon References:
  *   - Chapter 37: ZIO Metrics (Gauge with polling pattern)
  *   - Chapter 17: Dependency Injection (layer pattern)
  */
object StorageMetrics {

  /** Register polling gauges that query MessageStore stats periodically.
    *
    * This creates two gauges with polling fibers that query MessageStore.stats() every 10 seconds
    * and update the gauge values. The fibers are scoped and will be cancelled when the scope
    * closes.
    *
    * Returns a scoped resource (polling fibers run until scope closes).
    */
  val live: RIO[Scope & MessageStore, Unit] =
    for {
      messageStore <- ZIO.service[MessageStore]

      _ <- Metric
        .gauge("storage_messages_count")
        .set(0.0)
        .forkDaemon *>
        ZStream
          .repeatZIO(
            messageStore.stats().map { case (msgCount, _) => msgCount.toDouble }
          )
          .schedule(Schedule.fixed(10.seconds))
          .foreach { count =>
            Metric.gauge("storage_messages_count").set(count)
          }
          .forkScoped

      _ <- Metric
        .gauge("storage_threads_count")
        .set(0.0)
        .forkDaemon *>
        ZStream
          .repeatZIO(
            messageStore.stats().map { case (_, threadCount) => threadCount.toDouble }
          )
          .schedule(Schedule.fixed(10.seconds))
          .foreach { count =>
            Metric.gauge("storage_threads_count").set(count)
          }
          .forkScoped

    } yield ()

  /** Layer version - provides Unit (metrics are side-effecting registration) */
  val layer: RLayer[MessageStore, Unit] =
    ZLayer.scoped(live)
}
