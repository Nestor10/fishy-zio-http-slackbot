package slacksocket.demo.otel

import zio.*
import zio.telemetry.opentelemetry.metrics.Meter
import slacksocket.demo.service.SocketManager
import slacksocket.demo.domain.socket.{SocketStatus, SocketConnectionState}
import java.time.Instant

/** OpenTelemetry metrics for WebSocket connections.
  *
  * Provides event recording methods (counters/histograms) for socket lifecycle. Observable gauges
  * are registered separately after SocketManager is available.
  *
  * Critical for socket uptime monitoring, alerting, and root cause analysis.
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

  /** Event recording implementation (counters/histograms only). Does NOT depend on SocketManager -
    * breaks circular dependency.
    */
  case class Live(meter: Meter) extends SocketMetrics {

    override def recordConnectionStarted(socketId: String): UIO[Unit] =
      meter
        .counter(
          name = "socket.connections.started.total",
          unit = Some("{connections}"),
          description = Some("Total number of WebSocket connections started")
        )
        .flatMap(_.inc())

    override def recordConnectionClosed(
        socketId: String,
        reason: String,
        durationSeconds: Option[Long]
    ): UIO[Unit] =
      for {
        // Increment closed counter
        _ <- meter
          .counter(
            name = "socket.connections.closed.total",
            unit = Some("{connections}"),
            description = Some("Total number of WebSocket connections closed")
          )
          .flatMap(_.inc())

        // Record duration histogram if available
        _ <- ZIO.whenCase(durationSeconds) { case Some(duration) =>
          meter
            .histogram(
              name = "socket.connection.duration.seconds",
              unit = Some("{seconds}"),
              description = Some("Duration of WebSocket connections from start to close"),
              boundaries =
                Some(Chunk(10.0, 60.0, 300.0, 900.0, 1800.0, 3600.0)) // 10s, 1m, 5m, 15m, 30m, 1h
            )
            .flatMap(_.record(duration.toDouble))
        }
      } yield ()

    override def recordNetworkError(socketId: String, errorCategory: String): UIO[Unit] =
      meter
        .counter(
          name = "socket.errors.total",
          unit = Some("{errors}"),
          description = Some("Total number of socket network errors")
        )
        .flatMap(_.inc())
  }

  /** Layer for event recording service (no SocketManager dependency). */
  val layer: RLayer[Meter, SocketMetrics] =
    ZLayer.fromFunction(Live.apply)

  /** Observable gauge registration (depends on SocketManager). Call this AFTER SocketManager is
    * initialized. Registers callback-based gauges for real-time socket health.
    */
  val observableGauges: RIO[Scope & SocketManager & Meter, Unit] =
    for {
      socketManager <- ZIO.service[SocketManager]
      meter <- ZIO.service[Meter]

      // Gauge: Count of sockets in "ok" status (healthy)
      _ <- meter.observableGauge(
        name = "socket.connections.ok",
        unit = Some("{connections}"),
        description = Some("Number of WebSocket connections in healthy (ok) status")
      ) { observation =>
        socketManager.getAllConnectionStates.flatMap { states =>
          val okCount = states.count(_._2.status == SocketStatus.Ok)
          observation.record(okCount.toDouble)
        }
      }

      // Gauge: Count of sockets in "closing_soon" status (warned/expiring)
      _ <- meter.observableGauge(
        name = "socket.connections.closing_soon",
        unit = Some("{connections}"),
        description = Some("Number of WebSocket connections in closing_soon (warned) status")
      ) { observation =>
        socketManager.getAllConnectionStates.flatMap { states =>
          val closingSoonCount = states.count(_._2.status == SocketStatus.ClosingSoon)
          observation.record(closingSoonCount.toDouble)
        }
      }

      // Gauge: Count of sockets in "closed" status (disconnected)
      _ <- meter.observableGauge(
        name = "socket.connections.closed",
        unit = Some("{connections}"),
        description = Some("Number of WebSocket connections in closed (disconnected) status")
      ) { observation =>
        socketManager.getAllConnectionStates.flatMap { states =>
          val closedCount = states.count(_._2.status == SocketStatus.Closed)
          observation.record(closedCount.toDouble)
        }
      }

      // Gauge: Total number of socket connections (any status)
      _ <- meter.observableGauge(
        name = "socket.connections.total",
        unit = Some("{connections}"),
        description = Some("Total number of WebSocket connections tracked")
      ) { observation =>
        socketManager.getAllConnectionStates.flatMap { states =>
          observation.record(states.size.toDouble)
        }
      }

      // Gauge: Seconds since last pong received (max staleness across all sockets)
      // CRITICAL for detecting unhealthy connections before they're marked closed
      _ <- meter.observableGauge(
        name = "socket.pong.seconds_since_last",
        unit = Some("{seconds}"),
        description =
          Some("Maximum seconds since last pong received across all sockets (early warning)")
      ) { observation =>
        socketManager.getAllConnectionStates.flatMap { states =>
          val now = Instant.now()
          val maxStaleness = states.values
            .flatMap(_.lastPongReceivedAt)
            .map { lastPong =>
              java.time.Duration.between(lastPong, now).getSeconds
            }
            .maxOption
            .getOrElse(0L)

          observation.record(maxStaleness.toDouble)
        }
      }

    } yield ()

  /** Layer for observable gauges (requires SocketManager). */
  val observableGaugesLayer: RLayer[SocketManager & Meter, Unit] =
    ZLayer.scoped(observableGauges)
}
