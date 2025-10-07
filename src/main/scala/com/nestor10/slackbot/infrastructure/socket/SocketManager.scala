package com.nestor10.slackbot.infrastructure.socket

import com.nestor10.slackbot.conf.AppConfig
import com.nestor10.slackbot.domain.model.slack.BusinessMessage
import com.nestor10.slackbot.domain.model.socket.{InboundQueue, SocketConnectionState, SocketId}
import com.nestor10.slackbot.infrastructure.observability.{LogContext, SocketMetrics}
import com.nestor10.slackbot.infrastructure.slack.SlackApiClient
import zio.*
import zio.http.*
import zio.json.*

import java.util.concurrent.TimeUnit

trait SocketManager:
  def startManager(): UIO[Unit]
  def stopManager(): UIO[Unit]
  def getConnectionState(id: SocketId): UIO[Option[SocketConnectionState]]
  def getAllConnectionStates: UIO[Map[SocketId, SocketConnectionState]]
  def listConnections: UIO[List[SocketConnectionState]]

object SocketManager:

  object Live:

    case class Live(
        socketService: SocketService,
        slackApiClient: SlackApiClient,
        socketMetrics: SocketMetrics,
        cfg: AppConfig,
        client: Client,
        inboundQueue: InboundQueue,
        connectionStatesRef: Ref[Map[SocketId, SocketConnectionState]],
        connectionFibersRef: Ref[Map[SocketId, Fiber[Throwable, Unit]]],
        connectionFiber: Ref[Option[Fiber[Throwable, Unit]]]
    ) extends SocketManager {

      // Categorize errors for better logging and debugging
      private def categorizeError(throwable: Throwable): String = throwable match {
        case _: java.net.UnknownHostException   => "NETWORK_DNS"
        case _: java.net.ConnectException       => "NETWORK_CONNECTION"
        case _: java.net.SocketTimeoutException => "NETWORK_TIMEOUT"
        case _: java.io.IOException             => "NETWORK_IO"
        case e if e.getMessage != null && e.getMessage.contains("nodename nor servname provided") =>
          "NETWORK_DNS"
        case e if e.getMessage != null && e.getMessage.contains("timeout") => "NETWORK_TIMEOUT"
        case e if e.getMessage != null && e.getMessage.contains("Connection refused") =>
          "NETWORK_CONNECTION"
        case e if e.getMessage != null && e.getMessage.contains("readAddress(..) failed") =>
          "NETWORK_TIMEOUT"
        case e if e.getMessage != null && e.getMessage.contains("PrematureChannelClosure") =>
          "NETWORK_CONNECTION"
        case _: RuntimeException => "APPLICATION"
        case _                   => "UNKNOWN"
      }

      // Check if error indicates network is unavailable
      private def isNetworkError(category: String): Boolean = category match {
        case "NETWORK_DNS" | "NETWORK_CONNECTION" | "NETWORK_TIMEOUT" | "NETWORK_IO" => true
        case _                                                                       => false
      }

      private def connectSocket(
          id: SocketId,
          url: String
      ): ZIO[InboundQueue & Client, Throwable, Unit] =
        ZIO
          .scoped {
            socketService
              .createSocketConnection(cfg.debugReconnects, id, connectionStatesRef)
              .connect(url) *>
              // Use interruptible never - this should respond to interruption
              ZIO.never.interruptible
          }
          .catchAll { e =>
            ZIO.logError(s"Socket connection failed: ${e.getMessage}") @@
              LogContext.socketManager @@
              LogContext.connectionId(id.value)
          }
          .unit

      override def startManager(): UIO[Unit] = {
        val managementLoop = for {
          _ <- ZIO.logInfo(
            s"Starting with desired socket count: ${cfg.socketCount}"
          ) @@ LogContext.socketManager

          // Management loop
          _ <- (for {
            // 1. Get current connections
            currentConnections <- connectionStatesRef.get
            healthyConnections = currentConnections.filter { case (_, state) =>
              SocketConnectionState.isHealthy(state, cfg.pingIntervalSeconds)
            }

            _ <- ZIO.logDebug(
              s"Current healthy connections: ${healthyConnections.size}/${cfg.socketCount}"
            ) @@ LogContext.socketManager

            // 2. Close and remove disconnected sockets
            unhealthyConnections = currentConnections.filterNot { case (_, state) =>
              SocketConnectionState.isHealthy(state, cfg.pingIntervalSeconds)
            }

            // First, interrupt fibers for unhealthy connections
            _ <- ZIO.foreachDiscard(unhealthyConnections.keys) { socketId =>
              connectionFibersRef.get.flatMap { fibers =>
                fibers.get(socketId) match {
                  case Some(fiber) =>
                    // Calculate connection duration if we have connectedAt
                    val durationEffect =
                      unhealthyConnections.get(socketId).flatMap(_.connectedAt) match {
                        case Some(connectedAt) =>
                          val now = java.time.Instant.now()
                          val durationSeconds =
                            java.time.Duration.between(connectedAt, now).getSeconds
                          ZIO.succeed(Some(durationSeconds))
                        case None =>
                          ZIO.succeed(None)
                      }

                    durationEffect.flatMap { duration =>
                      // Record connection closed event
                      socketMetrics.recordConnectionClosed(socketId.value, "unhealthy", duration) *>
                        ZIO.logInfo(
                          s"Interrupting unhealthy socket"
                        ) @@
                        LogContext.socketManager @@
                        LogContext.connectionId(socketId.value) *>
                        fiber.interrupt.timeout(2.seconds).flatMap {
                          case Some(_) =>
                            ZIO.logDebug("Socket fiber interrupted successfully") @@
                              LogContext.socketManager @@
                              LogContext.connectionId(socketId.value)
                          case None =>
                            ZIO.logWarning("Socket fiber interrupt timed out") @@
                              LogContext.socketManager @@
                              LogContext.connectionId(socketId.value)
                        }
                    }
                  case None =>
                    ZIO.logWarning("No fiber found for unhealthy socket") @@
                      LogContext.socketManager @@
                      LogContext.connectionId(socketId.value)
                }
              }
            }

            // Remove from both state and fiber tracking
            _ <- connectionStatesRef.update(_.filterNot { case (id, _) =>
              unhealthyConnections.contains(id)
            })
            _ <- connectionFibersRef.update(_.filterNot { case (id, _) =>
              unhealthyConnections.contains(id)
            })

            _ <- ZIO.when(unhealthyConnections.nonEmpty) {
              ZIO.logInfo(
                s"Closed and removed ${unhealthyConnections.size} unhealthy socket(s)"
              ) @@ LogContext.socketManager
            }

            // 4. Calculate additional sockets needed
            socketsNeeded = cfg.socketCount - healthyConnections.size

            // 5. Start additional sockets if needed
            _ <- ZIO.when(socketsNeeded > 0) {
              ZIO.logInfo(
                s"Need to start $socketsNeeded additional socket(s)"
              ) @@ LogContext.socketManager *>
                ZIO.foreachDiscard(1 to socketsNeeded) { i =>
                  val socketId = SocketId(s"socket-${java.lang.System.currentTimeMillis()}-$i")
                  startSocket(socketId)
                }
            }

            // 6. Log current status
            _ <- getAllConnectionStates.flatMap { states =>
              ZIO.foreachDiscard(states) { case (id, state) =>
                ZIO.logDebug(s"${id.value} -> ${state.status}") @@
                  LogContext.socketManager @@
                  LogContext.operation("socket_state")
              }
            }

          } yield ()).repeat(Schedule.spaced(15.seconds))

        } yield ()

        // Start the management loop in a fiber and store it
        managementLoop.fork.flatMap(fiber => connectionFiber.set(Some(fiber))).unit
      }

      private def startSocket(socketId: SocketId): UIO[Unit] =
        (for {
          wsResponse <- ZIO.scoped {
            slackApiClient.requestSocketUrl.mapError(e => new RuntimeException(e.getMessage, e))
          }
          baseUrl = wsResponse.url
          finalUrl =
            if (cfg.debugReconnects) {
              ZIO.logInfo("Enabling debug reconnects") @@
                LogContext.socketManager @@
                LogContext.connectionId(socketId.value) *>
                ZIO.succeed(s"$baseUrl&debug_reconnects=true")
            } else {
              ZIO.succeed(baseUrl)
            }
          url <- finalUrl
          _ <- ZIO.logInfo(s"Starting socket with URL $url") @@
            LogContext.socketManager @@
            LogContext.connectionId(socketId.value)

          // Record connection started event
          _ <- socketMetrics.recordConnectionStarted(socketId.value)

          fiber <- connectSocket(socketId, url)
            .provideEnvironment(ZEnvironment(inboundQueue, client))
            .fork
          _ <- connectionFibersRef.update(_ + (socketId -> fiber))
          _ <- ZIO.logInfo("Socket started successfully") @@
            LogContext.socketManager @@
            LogContext.connectionId(socketId.value)
        } yield ()).catchAll { e =>
          // Categorize and log errors with more context
          val errorCategory = categorizeError(e)
          val errorDetails = s"${e.getClass.getSimpleName}: ${e.getMessage}"

          // Record network error metric
          socketMetrics.recordNetworkError(socketId.value, errorCategory) *>
            ZIO.logError(
              s"Socket start failed - category=$errorCategory error=$errorDetails"
            ) @@
            LogContext.socketManager @@
            LogContext.connectionId(socketId.value) @@
            LogContext.errorType(errorCategory) *>
            ZIO.logDebug(s"Socket error stacktrace: ${e.toString}") @@
            LogContext.socketManager @@
            LogContext.connectionId(socketId.value)
        }

      override def stopManager(): UIO[Unit] =
        ZIO.logInfo("Stop requested") @@
          LogContext.socketManager *>
          // First interrupt all socket fibers
          connectionFibersRef.get.flatMap { fibers =>
            ZIO.foreachDiscard(fibers) { case (socketId, fiber) =>
              ZIO.logInfo("Interrupting socket fiber") @@
                LogContext.socketManager @@
                LogContext.connectionId(socketId.value) *>
                fiber.interrupt.timeout(2.seconds).flatMap {
                  case Some(_) =>
                    ZIO.logDebug("Socket fiber interrupted successfully") @@
                      LogContext.socketManager @@
                      LogContext.connectionId(socketId.value)
                  case None =>
                    ZIO.logWarning("Socket fiber interrupt timed out") @@
                      LogContext.socketManager @@
                      LogContext.connectionId(socketId.value)
                }
            }
          } *>
          // Then interrupt the management fiber
          connectionFiber.get.flatMap {
            case Some(fiber) =>
              ZIO.logInfo("Interrupting management fiber") @@
                LogContext.socketManager *>
                fiber.interrupt.timeout(2.seconds).flatMap {
                  case Some(_) =>
                    ZIO.logInfo("Management fiber interrupted successfully") @@
                      LogContext.socketManager
                  case None =>
                    ZIO.logWarning(
                      "Management fiber did not interrupt within timeout"
                    ) @@ LogContext.socketManager
                }
            case None =>
              ZIO.logDebug("No management fiber to interrupt") @@
                LogContext.socketManager
          } *>
          // Clear all state
          connectionFiber.set(None) *>
          connectionFibersRef.set(Map.empty) *>
          connectionStatesRef.set(Map.empty) *>
          ZIO.logInfo("All sockets stopped and state cleared") @@
          LogContext.socketManager

      override def getConnectionState(id: SocketId): UIO[Option[SocketConnectionState]] =
        connectionStatesRef.get.map(_.get(id))

      override def getAllConnectionStates: UIO[Map[SocketId, SocketConnectionState]] =
        connectionStatesRef.get

      override def listConnections: UIO[List[SocketConnectionState]] =
        connectionStatesRef.get.map(_.values.toList)

    }

    val layer: ZLayer[
      SocketService & SlackApiClient & SocketMetrics & Client & InboundQueue,
      Throwable,
      SocketManager
    ] =
      ZLayer {
        for {
          socketService <- ZIO.service[SocketService]
          slackApiClient <- ZIO.service[SlackApiClient]
          socketMetrics <- ZIO.service[SocketMetrics]
          client <- ZIO.service[Client]
          cfg <- ZIO
            .config(AppConfig.config)
            .mapError(e => new RuntimeException(s"Config error: ${e.getMessage}", e))
          inboundQueue <- ZIO.service[InboundQueue]
          connectionStatesRef <- Ref.make(Map.empty[SocketId, SocketConnectionState])
          connectionFibersRef <- Ref.make(Map.empty[SocketId, Fiber[Throwable, Unit]])
          connectionFiber <- Ref.make[Option[Fiber[Throwable, Unit]]](None)
        } yield Live(
          socketService,
          slackApiClient,
          socketMetrics,
          cfg,
          client,
          inboundQueue,
          connectionStatesRef,
          connectionFibersRef,
          connectionFiber
        )
      }

  object Stub:

    val layer: ZLayer[
      SocketService & SlackApiClient & zio.http.Client & InboundQueue,
      Throwable,
      SocketManager
    ] = ZLayer {
      for {
        _ <- ZIO.service[SocketService]
        _ <- ZIO.service[SlackApiClient]
        _ <- ZIO.service[zio.http.Client]
        _ <- ZIO.service[InboundQueue]
      } yield new SocketManager {
        override def startManager(): UIO[Unit] =
          ZIO.logInfo("STUB: SocketManager.startManager called") @@
            LogContext.socketManager

        override def stopManager(): UIO[Unit] =
          ZIO.logInfo("STUB: SocketManager.stopManager called") @@
            LogContext.socketManager

        override def getConnectionState(id: SocketId): UIO[Option[SocketConnectionState]] =
          ZIO.logInfo("STUB: SocketManager.getConnectionState called") @@
            LogContext.socketManager @@
            LogContext.connectionId(id.value) *>
            ZIO.succeed(
              Some(
                SocketConnectionState(
                  isDisconnected = false,
                  isWarned = false,
                  connectedAt = Some(java.time.Instant.now()),
                  approximateConnectionTime = None,
                  lastPongReceivedAt = None
                )
              )
            )

        override def getAllConnectionStates: UIO[Map[SocketId, SocketConnectionState]] =
          ZIO.logInfo("STUB: SocketManager.getAllConnectionStates called") @@
            LogContext.socketManager *>
            ZIO.succeed(
              Map(
                SocketId("stub") -> SocketConnectionState(
                  isDisconnected = false,
                  isWarned = false,
                  connectedAt = Some(java.time.Instant.now()),
                  approximateConnectionTime = None,
                  lastPongReceivedAt = None
                )
              )
            )

        override def listConnections: UIO[List[SocketConnectionState]] =
          ZIO.logInfo("STUB: SocketManager.listConnections called") @@
            LogContext.socketManager *>
            ZIO.succeed(
              List(
                SocketConnectionState(
                  isDisconnected = false,
                  isWarned = false,
                  connectedAt = Some(java.time.Instant.now()),
                  approximateConnectionTime = None,
                  lastPongReceivedAt = None
                )
              )
            )

      }
    }
