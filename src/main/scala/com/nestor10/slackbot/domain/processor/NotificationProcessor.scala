package com.nestor10.slackbot.domain.processor

import zio.*
import com.nestor10.slackbot.domain.service.MessageEventBus.MessageEvent
import com.nestor10.slackbot.infrastructure.observability.LogContext

/** Notification processor for sending alerts and notifications.
  *
  * This is a stub implementation that logs events. In production, this would:
  *   - Send email notifications
  *   - Post to webhook endpoints
  *   - Trigger SMS alerts
  *   - Update external monitoring systems
  *
  * Zionomicon References:
  *   - Chapter 17: Dependency Injection (ZLayer pattern)
  */
class NotificationProcessor extends EventProcessor:

  override val name: String = "NotificationProcessor"

  override def canProcess(event: MessageEvent): Boolean = event match
    case _: MessageEvent.ThreadCreated => true // Notify on new threads
    case _                             => false

  override def process(event: MessageEvent): IO[EventProcessor.Error, Unit] =
    event match
      case MessageEvent.ThreadCreated(thread, timestamp) =>
        ZIO.logInfo("Would send alert for new thread") @@
          LogContext.notifications @@
          LogContext.threadId(thread.id)

      case _ =>
        ZIO.unit

object NotificationProcessor:

  /** ZLayer for dependency injection */
  val layer: ZLayer[Any, Nothing, NotificationProcessor] =
    ZLayer.succeed(new NotificationProcessor)
