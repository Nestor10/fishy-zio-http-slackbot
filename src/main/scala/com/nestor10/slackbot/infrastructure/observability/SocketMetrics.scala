package com.nestor10.slackbot.infrastructure.observability

import zio.*
import zio.stream.*
import zio.metrics.*
import com.nestor10.slackbot.infrastructure.socket.SocketManager
import com.nestor10.slackbot.domain.model.socket.{SocketStatus, SocketConnectionState}
import java.time.Instant

/** ZIO Runtime metrics for WebSocket connections.
  *
  * Uses ZIO Metric API with labels for dimensional observability. Provides event recording methods
  * (counters/histograms) for socket lifecycle. Observable gauges are registered separately after
  * SocketManager is available.
  *
  * Critical for socket uptime monitoring, alerting, and root cause analysis.
  *
  * Zionomicon References:
  *   - Chapter 37: ZIO Metrics (Counter, Histogram, Gauge with labels)
  *   - Chapter 17: Dependency Injection (layer pattern)
  */
trait SocketMetrics {

  /** Record a socket connection started event. */
  def recordConnectionStarted(socketId: String): UIO[Unit]

  /** Record a socket connection closed event with reason and optional duration. */
  def recordConnectionClosed(
      socketId: String,
      reason: String,
      durationSeconds: Option[Long]
  ): UIO[Unit]

  /** Record a network error with categorized type. */
  def recordNetworkError(socketId: String, errorCategory: String): UIO[Unit]
}

object SocketMetrics {

  /** Event recording implementation using ZIO Runtime Metrics with labels. Does NOT depend on
    * SocketManager - breaks circular dependency.
    */
  case class Live() extends SocketMetrics {

    // Counter: Total socket connections started (labeled by socket_id)
    private val connectionsStartedCounter = Metric.counterInt("socket_connections_started_total")

    // Counter: Total socket connections closed (labeled by socket_id, reason)
    private val connectionsClosedCounter = Metric.counterInt("socket_connections_closed_total")

    // Histogram: Socket connection duration in seconds (labeled by socket_id, reason)
    private val connectionDurationHistogram = Metric.histogram(
      "socket_connection_duration_seconds",
      MetricKeyType.Histogram.Boundaries
        .fromChunk(Chunk(10.0, 60.0, 300.0, 900.0, 1800.0, 3600.0)) // 10s, 1m, 5m, 15m, 30m, 1h
    )

    // Counter: Total network errors (labeled by socket_id, error_category)
    private val networkErrorsCounter = Metric.counterInt("socket_errors_total")

    override def recordConnectionStarted(socketId: String): UIO[Unit] =
      connectionsStartedCounter
        .tagged(MetricLabel("socket_id", socketId))
        .increment
        .unit

    override def recordConnectionClosed(
        socketId: String,
        reason: String,
        durationSeconds: Option[Long]
    ): UIO[Unit] =
      for {
        // Increment closed counter with labels
        _ <- connectionsClosedCounter
          .tagged(
            MetricLabel("socket_id", socketId),
            MetricLabel("reason", reason)
          )
          .increment
          .unit

        // Record duration histogram if available
        _ <- ZIO.whenCase(durationSeconds) { case Some(duration) =>
          connectionDurationHistogram
            .tagged(
              MetricLabel("socket_id", socketId),
              MetricLabel("reason", reason)
            )
            .update(duration.toDouble)
        }
      } yield ()

    override def recordNetworkError(socketId: String, errorCategory: String): UIO[Unit] =
      networkErrorsCounter
        .tagged(
          MetricLabel("socket_id", socketId),
          MetricLabel("error_category", errorCategory)
        )
        .increment
        .unit
  }

  /** Layer for event recording service (no dependencies). */
  val layer: ULayer[SocketMetrics] =
    ZLayer.succeed(Live())

  /** Observable gauge registration using ZIO Metric API (depends on SocketManager). Call this AFTER
    * SocketManager is initialized. Registers callback-based gauges for real-time socket health.
    */
  val observableGauges: RIO[Scope & SocketManager, Unit] =
    for {
      socketManager <- ZIO.service[SocketManager]

      // Gauge: Count of sockets in "ok" status (healthy)
      _ <- Metric
        .gauge("socket_connections_ok")
        .tagged(MetricLabel("status", "ok"))
        .set(0.0) // Initialize
        .forkDaemon *> // Fork polling fiber
        ZStream
          .repeatZIO(
            socketManager.getAllConnectionStates.map { states =>
              states.count(_._2.status == SocketStatus.Ok).toDouble
            }
          )
          .schedule(Schedule.fixed(10.seconds)) // Poll every 10 seconds
          .foreach { count =>
            Metric
              .gauge("socket_connections_ok")
              .tagged(MetricLabel("status", "ok"))
              .set(count)
          }
          .forkScoped

      // Gauge: Count of sockets in "closing_soon" status (warned/expiring)
      _ <- Metric
        .gauge("socket_connections_closing_soon")
        .tagged(MetricLabel("status", "closing_soon"))
        .set(0.0)
        .forkDaemon *>
        ZStream
          .repeatZIO(
            socketManager.getAllConnectionStates.map { states =>
              states.count(_._2.status == SocketStatus.ClosingSoon).toDouble
            }
          )
          .schedule(Schedule.fixed(10.seconds))
          .foreach { count =>
            Metric
              .gauge("socket_connections_closing_soon")
              .tagged(MetricLabel("status", "closing_soon"))
              .set(count)
          }
          .forkScoped

      // Gauge: Count of sockets in "closed" status (disconnected)
      _ <- Metric
        .gauge("socket_connections_closed")
        .tagged(MetricLabel("status", "closed"))
        .set(0.0)
        .forkDaemon *>
        ZStream
          .repeatZIO(
            socketManager.getAllConnectionStates.map { states =>
              states.count(_._2.status == SocketStatus.Closed).toDouble
            }
          )
          .schedule(Schedule.fixed(10.seconds))
          .foreach { count =>
            Metric
              .gauge("socket_connections_closed")
              .tagged(MetricLabel("status", "closed"))
              .set(count)
          }
          .forkScoped

      // Gauge: Total number of socket connections (any status)
      _ <- Metric
        .gauge("socket_connections_total")
        .set(0.0)
        .forkDaemon *>
        ZStream
          .repeatZIO(
            socketManager.getAllConnectionStates.map { states =>
              states.size.toDouble
            }
          )
          .schedule(Schedule.fixed(10.seconds))
          .foreach { count =>
            Metric.gauge("socket_connections_total").set(count)
          }
          .forkScoped

      // Gauge: Seconds since last pong received (max staleness across all sockets)
      // CRITICAL for detecting unhealthy connections before they're marked closed
      _ <- Metric
        .gauge("socket_pong_seconds_since_last")
        .set(0.0)
        .forkDaemon *>
        ZStream
          .repeatZIO {
            socketManager.getAllConnectionStates.map { states =>
              val now = Instant.now()
              states.values
                .flatMap(_.lastPongReceivedAt)
                .map { lastPong =>
                  java.time.Duration.between(lastPong, now).getSeconds.toDouble
                }
                .maxOption
                .getOrElse(0.0)
            }
          }
          .schedule(Schedule.fixed(10.seconds))
          .foreach { maxStaleness =>
            Metric.gauge("socket_pong_seconds_since_last").set(maxStaleness)
          }
          .forkScoped

    } yield ()

  /** Layer for observable gauges (requires SocketManager). */
  val observableGaugesLayer: RLayer[SocketManager, Unit] =
    ZLayer.scoped(observableGauges)
}
