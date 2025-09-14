package slacksocket.demo.domain

import zio.json.*
import zio.Queue
import java.time.{Instant, Duration}
import slacksocket.demo.domain.slack.BusinessMessage

// Business message queue type for socket communication
type InboundQueue = Queue[BusinessMessage]

// Socket identifier
case class SocketId(value: String)

// Socket connection status
enum SocketStatus(val value: String):
  case Ok extends SocketStatus("ok")
  case ClosingSoon extends SocketStatus("closing-soon")
  case Closed extends SocketStatus("closed")

// Socket connection state for health tracking
case class SocketConnectionState(
    isDisconnected: Boolean,
    isWarned: Boolean,
    connectedAt: Option[Instant],
    approximateConnectionTime: Option[Duration], // in seconds from Slack API
    lastPongReceivedAt: Option[Instant] // timestamp of last pong frame received
) {

  // Calculated status based on state fields
  def status: SocketStatus =
    if (isDisconnected) {
      SocketStatus.Closed
    } else if (isWarned || willCloseSoon) {
      SocketStatus.ClosingSoon
    } else {
      SocketStatus.Ok
    }

  // Helper to check if connection will close within 5 minutes
  private def willCloseSoon: Boolean =
    approximateConnectionTime.exists(_.toMillis <= (5 * 60 * 1000L)) // 5 minutes in milliseconds
}

object SocketConnectionState {

  // Helper methods
  def isHealthy(state: SocketConnectionState, pingIntervalSeconds: Int): Boolean =
    state.status == SocketStatus.Ok
      && !hasMissedPingIntervals(state, pingIntervalSeconds)

  def willCloseSoon(state: SocketConnectionState, thresholdMinutes: Int = 5): Boolean =
    state.isWarned || state.approximateConnectionTime.exists(
      _.toMillis <= (thresholdMinutes * 60 * 1000L)
    )

  // Health check method that detects missed ping intervals
  def hasMissedPingIntervals(
      state: SocketConnectionState,
      pingIntervalSeconds: Int,
      missedIntervalThreshold: Int = 2
  ): Boolean =
    state.lastPongReceivedAt match {
      case None => false // No pongs received yet, can't determine if missed
      case Some(lastPong) =>
        val now = Instant.now()
        val timeSinceLastPong = Duration.between(lastPong, now)
        val expectedMaxDelay = Duration.ofSeconds(pingIntervalSeconds * missedIntervalThreshold)
        timeSinceLastPong.compareTo(expectedMaxDelay) > 0
    }
}
