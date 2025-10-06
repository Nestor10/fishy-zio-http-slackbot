package com.nestor10.slackbot.domain.conversation

import zio.json.*
import java.time.Instant

// Domain events for reactive services - but subscription/broadcasting handled by ThreadHub service
sealed trait ThreadEvent derives JsonCodec {
  def threadId: ThreadId
  def timestamp: Instant
}

object ThreadEvent:

  // Thread lifecycle events
  case class ThreadCreated(
      threadId: ThreadId,
      channelId: ChannelId,
      rootMessage: ThreadMessage,
      timestamp: Instant
  ) extends ThreadEvent

  case class ThreadArchived(
      threadId: ThreadId,
      archivedAt: Instant,
      timestamp: Instant
  ) extends ThreadEvent

  // Message events
  case class MessageAdded(
      threadId: ThreadId,
      message: ThreadMessage,
      timestamp: Instant
  ) extends ThreadEvent {
    def isFromBot(botIdentity: BotIdentity): Boolean = message.isFromBot(botIdentity)
    def isFromUser: Boolean = message.isFromUser
    def isFromExternalService: Boolean = message.isFromExternalService
  }

  case class MessageUpdated(
      threadId: ThreadId,
      messageId: MessageId,
      oldContent: String,
      newContent: String,
      timestamp: Instant
  ) extends ThreadEvent

  case class MessageDeleted(
      threadId: ThreadId,
      messageId: MessageId,
      timestamp: Instant
  ) extends ThreadEvent

  // Reaction events
  case class ReactionAdded(
      threadId: ThreadId,
      messageId: MessageId,
      emoji: String,
      userId: UserId,
      timestamp: Instant
  ) extends ThreadEvent

  case class ReactionRemoved(
      threadId: ThreadId,
      messageId: MessageId,
      emoji: String,
      userId: UserId,
      timestamp: Instant
  ) extends ThreadEvent

  // State change events
  case class ThreadStateChanged(
      threadId: ThreadId,
      oldState: ThreadState,
      newState: ThreadState,
      timestamp: Instant
  ) extends ThreadEvent

  case class ThreadMetadataUpdated(
      threadId: ThreadId,
      serviceName: String,
      key: String,
      oldValue: Option[String],
      newValue: String,
      timestamp: Instant
  ) extends ThreadEvent
