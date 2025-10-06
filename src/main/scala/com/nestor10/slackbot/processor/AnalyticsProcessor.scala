package com.nestor10.slackbot.processor

import zio.*
import com.nestor10.slackbot.service.MessageEventBus.MessageEvent

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
) extends MessageProcessor:

  override val name: String = "AnalyticsProcessor"

  override def canProcess(event: MessageEvent): Boolean = true // Track all events

  override def process(event: MessageEvent): IO[MessageProcessor.Error, Unit] =
    event match
      case MessageEvent.ThreadCreated(thread, _) =>
        threadsCreated.update(_ + 1) *>
          ZIO.logInfo(s"📊 ANALYTICS: Thread created - total: ${thread.id.formatted}")

      case MessageEvent.MessageStored(message, _) =>
        messagesStored.update(_ + 1) *>
          ZIO.logInfo(s"📊 ANALYTICS: Message stored in thread ${message.threadId.formatted}")

      case MessageEvent.ThreadUpdated(thread, _) =>
        threadsUpdated.update(_ + 1) *>
          ZIO.logInfo(s"📊 ANALYTICS: Thread updated - ${thread.id.formatted}")

      case MessageEvent.MessageUpdated(messageId, _, _, _) =>
        messagesUpdated.update(_ + 1) *>
          ZIO.logInfo(s"📊 ANALYTICS: Message updated - ${messageId.formatted}")

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
