package com.nestor10.slackbot.processor

import zio.*
import com.nestor10.slackbot.domain.service.MessageEventBus.MessageEvent

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
class NotificationProcessor extends MessageProcessor:

  override val name: String = "NotificationProcessor"

  override def canProcess(event: MessageEvent): Boolean = event match
    case _: MessageEvent.ThreadCreated => true // Notify on new threads
    case _                             => false

  override def process(event: MessageEvent): IO[MessageProcessor.Error, Unit] =
    event match
      case MessageEvent.ThreadCreated(thread, timestamp) =>
        ZIO.logInfo(
          s"📬 NOTIFICATION: Would send alert for new thread ${thread.id.formatted} at $timestamp"
        )

      case _ =>
        ZIO.unit

object NotificationProcessor:

  /** ZLayer for dependency injection */
  val layer: ZLayer[Any, Nothing, NotificationProcessor] =
    ZLayer.succeed(new NotificationProcessor)
