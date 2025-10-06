package com.nestor10.slackbot.service

import zio._
import zio.http.ChannelEvent.{Read, UserEvent, UserEventTriggered}
import zio.http._
import zio.json._
import com.nestor10.slackbot.conf.AppConfig
import com.nestor10.slackbot.service.SlackApiClient
import com.nestor10.slackbot.domain.socket.{SocketId, SocketConnectionState, InboundQueue}
import com.nestor10.slackbot.domain.slack.BusinessMessage
import com.nestor10.slackbot.domain.slack.{
  SlackSocketMessage,
  EventsApiMessage,
  AckResponse,
  Hello,
  Disconnect,
  InteractiveMessage,
  SlashCommand
}

import java.time.{Duration, Instant}

// Service for websocket communication
trait SocketService {

  def createSocketConnection(
      debugReconnects: Boolean,
      socketId: SocketId,
      sharedStateRef: Ref[Map[SocketId, SocketConnectionState]]
  ): WebSocketApp[InboundQueue]
}

object SocketService {

  object Live {

    final case class Live(inboundQueue: InboundQueue) extends SocketService {

      // Helper method to categorize network errors
      private def categorizeNetworkError(throwable: Throwable): String = throwable match {
        case _: java.net.ConnectException                                  => "CONNECTION"
        case _: java.net.SocketTimeoutException                            => "TIMEOUT"
        case _: java.io.IOException                                        => "IO_ERROR"
        case e if e.getMessage != null && e.getMessage.contains("timeout") => "TIMEOUT"
        case e if e.getMessage != null && e.getMessage.contains("Connection refused") =>
          "CONNECTION"
        case e if e.getMessage != null && e.getMessage.contains("channel gone inactive") =>
          "CHANNEL_INACTIVE"
        case e if e.getMessage != null && e.getMessage.contains("PrematureChannelClosure") =>
          "CONNECTION"
        case _ => "UNKNOWN"
      }

      override def createSocketConnection(
          debugReconnects: Boolean,
          socketId: SocketId,
          sharedStateRef: Ref[Map[SocketId, SocketConnectionState]]
      ): WebSocketApp[InboundQueue] =
        Handler
          .webSocket { channel =>
            for {
              cfg <- ZIO.config(AppConfig.config)

              // Helper to update shared state for this socket
              updateSharedState = (update: SocketConnectionState => SocketConnectionState) =>
                sharedStateRef.update(_.updatedWith(socketId)(_.map(update)))

              // Initialize shared state for this socket
              initialState = SocketConnectionState(
                isDisconnected = false,
                isWarned = false,
                connectedAt = Some(Instant.now()),
                approximateConnectionTime = None,
                lastPongReceivedAt = None
              )
              _ <- sharedStateRef.update(_ + (socketId -> initialState))

              // Log connection attempt with debug info
              _ <- ZIO.logInfo(s"🔌 WEBSOCKET_CONNECTING: socket=$socketId debug=$debugReconnects")

              // Simple single ping effect (no sleep here!)
              singlePing = for {
                _ <- ZIO.logDebug(s"Socket $socketId: Sending WebSocket")

                _ <- channel.send(Read(WebSocketFrame.Ping)).catchAll { error =>
                  val errorCategory = categorizeNetworkError(error)
                  ZIO.logError(
                    s"Socket $socketId: PING_FAILED category=$errorCategory error=${error.getMessage}"
                  )
                }
              } yield ()

              // Use repeat with spaced schedule - this handles the timing correctly
              pingEffect = singlePing.repeat(Schedule.spaced(cfg.pingIntervalSeconds.seconds)).unit

              _ <- channel.receiveAll {
                case UserEventTriggered(UserEvent.HandshakeComplete) =>
                  ZIO.logInfo(s"Socket $socketId: Handshake complete. Starting ping fiber.") *>
                    pingEffect.fork
                case Read(WebSocketFrame.Text(x)) =>
                  ZIO.logDebug(s"Received WebSocket text: $x") *>
                    ZIO
                      .fromEither(x.fromJson[SlackSocketMessage])
                      .foldZIO(
                        error => ZIO.logError(s"Failed to parse SlackSocketMessage: $error"),
                        message =>
                          message match
                            case eventsApiMessage: EventsApiMessage =>
                              // Send acknowledgment
                              val ack = AckResponse(eventsApiMessage.envelope_id)
                              val ackFrame = WebSocketFrame.Text(ack.toJson)
                              channel.send(Read(ackFrame)) *>
                                // Forward the EventsApiMessage to inbound queue
                                inboundQueue.offer(eventsApiMessage) *>
                                ZIO.logInfo(
                                  s"Event acknowledged and queued: ${eventsApiMessage.envelope_id}"
                                )
                            case hello: Hello =>
                              // Update shared state with approximate connection time from hello
                              updateSharedState(state =>
                                state.copy(approximateConnectionTime =
                                  Some(
                                    Duration
                                      .ofSeconds(
                                        hello.debug_info.approximate_connection_time.toLong
                                      )
                                  )
                                )
                              ) *>
                                ZIO.logInfo(
                                  s"Socket $socketId: Received hello message - connection established"
                                )
                            case disconnect: Disconnect =>
                              // Handle different types of disconnect messages
                              val (newIsDisconnected, newIsWarned) = disconnect.reason match {
                                case "warning" => (false, true) // Warning disconnect
                                case _         => (true, false) // Full disconnect
                              }
                              updateSharedState(state =>
                                state.copy(
                                  isDisconnected = newIsDisconnected,
                                  isWarned = newIsWarned
                                )
                              ) *>
                                ZIO.logWarning(
                                  s"Socket $socketId: Received disconnect: ${disconnect.reason} -> isDisconnected: $newIsDisconnected, isWarned: $newIsWarned"
                                )
                            case interactive: InteractiveMessage =>
                              // Send acknowledgment for interactive messages
                              val ack = AckResponse(interactive.envelope_id)
                              val ackFrame = WebSocketFrame.Text(ack.toJson)
                              channel.send(Read(ackFrame)) *>
                                // Queue for business logic processing
                                inboundQueue.offer(interactive) *>
                                ZIO.logInfo(
                                  s"Interactive message acknowledged and queued: ${interactive.envelope_id}"
                                )
                            case slashCommand: SlashCommand =>
                              // Send acknowledgment for slash commands
                              val ack = AckResponse(slashCommand.envelope_id)
                              val ackFrame = WebSocketFrame.Text(ack.toJson)
                              channel.send(Read(ackFrame)) *>
                                // Queue for business logic processing
                                inboundQueue.offer(slashCommand) *>
                                ZIO.logInfo(
                                  s"Slash command acknowledged and queued: ${slashCommand.envelope_id}"
                                )
                      )
                case Read(WebSocketFrame.Close(status, reason)) =>
                  // Update shared state to closed on WebSocket close
                  updateSharedState(state => state.copy(isDisconnected = true, isWarned = false)) *>
                    ZIO.logInfo(
                      s"Socket $socketId: WebSocket close received: status=$status, reason=$reason"
                    )
                case Read(WebSocketFrame.Ping) =>
                  ZIO.logInfo(s"Socket $socketId: Received WebSocket ping frame") *>
                    // Automatically respond with pong containing the same data
                    channel.send(Read(WebSocketFrame.Pong))
                case Read(WebSocketFrame.Pong) =>
                  updateSharedState(state =>
                    state.copy(lastPongReceivedAt = Some(Instant.now()))
                  ) *>
                    ZIO.logDebug(s"Socket $socketId: Received WebSocket pong frame")
                case _ => ZIO.unit
              }
            } yield ()
          }
    }

    val layer: ZLayer[InboundQueue, Nothing, SocketService] = ZLayer.fromFunction(Live.apply)
  }

  object Stub {

    final case class Stub(inboundQueue: InboundQueue) extends SocketService {

      override def createSocketConnection(
          debugReconnects: Boolean,
          socketId: SocketId,
          sharedStateRef: Ref[Map[SocketId, SocketConnectionState]]
      ): WebSocketApp[InboundQueue] =
        Handler.webSocket { channel =>
          ZIO.logInfo(s"🔌 STUB: WebSocket handler created for socket $socketId") *>
            // Initialize stub state in shared ref
            sharedStateRef.update(
              _ + (socketId -> SocketConnectionState(
                isDisconnected = false,
                isWarned = false,
                connectedAt = Some(Instant.now()),
                approximateConnectionTime = None,
                lastPongReceivedAt = None
              ))
            ) *>
            channel.receiveAll { case _ =>
              ZIO.logDebug(s"🔌 STUB: Socket $socketId received WebSocket frame")
            }
        }
    }

    val layer: ZLayer[InboundQueue, Nothing, SocketService] = ZLayer.fromFunction(Stub.apply)
  }
}
