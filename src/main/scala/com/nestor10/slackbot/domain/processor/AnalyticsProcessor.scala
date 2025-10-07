package com.nestor10.slackbot.domain.processor

import com.nestor10.slackbot.domain.service.MessageEventBus.MessageEvent
import com.nestor10.slackbot.infrastructure.observability.LogContext
import zio.*

/** Analytics processor for tracking metrics and statistics.
  *
  * Tracks:
  *   - Thread creation count
  *   - Message count
  *   - Thread update count
  *   - Message update count
  *
  * Uses Ref for atomic counter updates.
  *
  * Zionomicon References:
  *   - Chapter 9: Ref (atomic shared state for counters)
  *   - Chapter 17: Dependency Injection
  */
class AnalyticsProcessor(
    threadsCreated: Ref[Long],
    messagesStored: Ref[Long],
    threadsUpdated: Ref[Long],
    messagesUpdated: Ref[Long]
) extends EventProcessor:

  override val name: String = "AnalyticsProcessor"

  override def canProcess(event: MessageEvent): Boolean = true // Track all events

  override def process(event: MessageEvent): IO[EventProcessor.Error, Unit] =
    event match
      case MessageEvent.ThreadCreated(thread, _) =>
        threadsCreated.update(_ + 1) *>
          ZIO.logInfo("Thread created") @@
          LogContext.analytics @@
          LogContext.threadId(thread.id)

      case MessageEvent.MessageStored(message, _) =>
        messagesStored.update(_ + 1) *>
          ZIO.logInfo("Message stored") @@
          LogContext.analytics @@
          LogContext.threadId(message.threadId)

      case MessageEvent.ThreadUpdated(thread, _) =>
        threadsUpdated.update(_ + 1) *>
          ZIO.logInfo("Thread updated") @@
          LogContext.analytics @@
          LogContext.threadId(thread.id)

      case MessageEvent.MessageUpdated(messageId, _, _, _) =>
        messagesUpdated.update(_ + 1) *>
          ZIO.logInfo("Message updated") @@
          LogContext.analytics @@
          LogContext.messageId(messageId)

      /** Get current statistics */
  def getStats: UIO[(Long, Long, Long, Long)] =
    for {
      tc <- threadsCreated.get
      ms <- messagesStored.get
      tu <- threadsUpdated.get
      mu <- messagesUpdated.get
    } yield (tc, ms, tu, mu)

object AnalyticsProcessor:

  /** ZLayer for dependency injection */
  val layer: ZLayer[Any, Nothing, AnalyticsProcessor] =
    ZLayer.fromZIO {
      for {
        tc <- Ref.make(0L)
        ms <- Ref.make(0L)
        tu <- Ref.make(0L)
        mu <- Ref.make(0L)
      } yield new AnalyticsProcessor(tc, ms, tu, mu)
    }
