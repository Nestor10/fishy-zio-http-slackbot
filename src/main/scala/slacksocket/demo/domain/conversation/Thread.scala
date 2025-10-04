package slacksocket.demo.domain.conversation

import zio.json.*
import java.time.Instant
import slacksocket.demo.domain.slack.AppMention

// Core identifiers
case class ThreadId(value: Double) extends AnyVal {

  /** Format as Slack timestamp string (e.g., "1759466226.275309") */
  def formatted: String = f"$value%.6f"
}

object ThreadId:
  given JsonCodec[ThreadId] = JsonCodec.double.transform(ThreadId.apply, _.value)

case class MessageId(value: Double) extends AnyVal {

  /** Format as Slack timestamp string (e.g., "1759466226.275309") */
  def formatted: String = f"$value%.6f"
}

object MessageId:
  given JsonCodec[MessageId] = JsonCodec.double.transform(MessageId.apply, _.value)

case class UserId(value: String) extends AnyVal

object UserId:
  given JsonCodec[UserId] = JsonCodec.string.transform(UserId.apply, _.value)

case class ChannelId(value: String) extends AnyVal

object ChannelId:
  given JsonCodec[ChannelId] = JsonCodec.string.transform(ChannelId.apply, _.value)

// Bot identity - the central perspective of our domain
case class BotIdentity(
    userId: UserId,
    username: String,
    displayName: Option[String] = None
) derives JsonCodec

// Message source - where did this message come from?
sealed trait MessageSource derives JsonCodec

object MessageSource:
  case object SlackUser extends MessageSource
  case object SlackBot extends MessageSource
  case object Self extends MessageSource // This bot
  case class ExternalService(serviceName: String) extends MessageSource

// Reaction on a message
case class MessageReaction(
    emoji: String,
    users: Set[UserId],
    addedAt: Instant
) derives JsonCodec

// Core message in a thread
case class ThreadMessage(
    id: MessageId,
    threadId: ThreadId,
    source: MessageSource,
    author: UserId,
    content: String,

    // Domain object lifecycle - when WE created/updated this object
    createdAt: Instant, // When we first created this domain object
    updatedAt: Instant, // When we last modified this domain object (edits, reactions, etc.)

    // Slack-specific fields (when from Slack)
    slackCreatedAt: Option[Instant] = None, // When message was originally created in Slack
    slackEventId: Option[Double] = None, // For deduplication

    // Message state
    isDeleted: Boolean = false,
    editHistory: List[MessageEdit] = List.empty,
    reactions: Map[String, MessageReaction] = Map.empty,

    // Extensibility for services
    metadata: Map[String, String] = Map.empty
) derives JsonCodec {

  def isFromBot(botIdentity: BotIdentity): Boolean =
    source == MessageSource.Self || author == botIdentity.userId

  def isFromUser: Boolean = source == MessageSource.SlackUser

  def isFromExternalService: Boolean = source match {
    case MessageSource.ExternalService(_) => true
    case _                                => false
  }

  // Slack-specific utilities
  def isFromSlack: Boolean = slackCreatedAt.isDefined

  def slackAge: Option[java.time.Duration] =
    slackCreatedAt.map(java.time.Duration.between(_, java.time.Instant.now()))

  def addReaction(emoji: String, userId: UserId, at: Instant): ThreadMessage = {
    val updated = reactions.get(emoji) match {
      case Some(existing) => existing.copy(users = existing.users + userId)
      case None           => MessageReaction(emoji, Set(userId), at)
    }
    copy(
      reactions = reactions + (emoji -> updated),
      updatedAt = Instant.now() // Update our domain object timestamp
    )
  }

  def removeReaction(emoji: String, userId: UserId): ThreadMessage =
    reactions.get(emoji) match {
      case Some(existing) =>
        val updatedUsers = existing.users - userId
        val newReactions = if (updatedUsers.isEmpty) {
          reactions - emoji
        } else {
          reactions + (emoji -> existing.copy(users = updatedUsers))
        }
        copy(
          reactions = newReactions,
          updatedAt = Instant.now() // Update our domain object timestamp
        )
      case None => this
    }

  def markDeleted: ThreadMessage = copy(
    isDeleted = true,
    updatedAt = Instant.now() // Update our domain object timestamp
  )

  def editContent(newContent: String, editedAt: Instant): ThreadMessage = {
    val edit = MessageEdit(content, newContent, editedAt)
    copy(
      content = newContent,
      editHistory = editHistory :+ edit,
      updatedAt = Instant.now() // Update our domain object timestamp
    )
  }
}

object ThreadMessage:

  /** Factory method to create a ThreadMessage from a Slack Message event.
    *
    * Use this for thread replies - assumes the thread already exists. For thread creation from
    * AppMention, use Thread.fromAppMention instead.
    */
  def fromSlackMessage(
      slackMessage: slacksocket.demo.domain.slack.Message,
      threadId: ThreadId,
      botIdentity: BotIdentity
  ): ThreadMessage = {
    val messageId = MessageId(slackMessage.ts)
    val authorId = UserId(slackMessage.user.getOrElse("unknown"))
    val content = slackMessage.text.getOrElse("")
    val now = Instant.now() // Domain object creation time

    val source = if (authorId == botIdentity.userId) {
      MessageSource.Self
    } else {
      slackMessage.subtype match {
        case Some("bot_message") =>
          MessageSource.SlackBot
        case _ =>
          MessageSource.SlackUser
      }
    }

    ThreadMessage(
      id = messageId,
      threadId = threadId,
      source = source,
      author = authorId,
      content = content,
      createdAt = now, // When we created this domain object
      updatedAt = now, // Initially same as created
      slackCreatedAt = Some(Thread.slackTimestampToInstant(slackMessage.ts)),
      slackEventId = Some(slackMessage.event_ts)
    )
  }

// Track message edits
case class MessageEdit(
    originalContent: String,
    newContent: String,
    editedAt: Instant
) derives JsonCodec

// Thread state and metadata
case class ThreadState(
    isActive: Boolean = true,
    participantCount: Int = 0,
    lastHumanActivity: Option[Instant] = None,
    lastBotActivity: Option[Instant] = None,
    tags: Set[String] = Set.empty
) derives JsonCodec

// Main thread domain object - bot-centric view of a Slack thread
case class Thread(
    id: ThreadId,
    channelId: ChannelId,

    // Bot context
    botIdentity: BotIdentity,

    // Thread root message (the message that started the thread)
    rootMessage: ThreadMessage,

    // All messages in chronological order (including root)
    messages: List[ThreadMessage],

    // Thread metadata and state
    state: ThreadState,

    // Tracking
    createdAt: Instant,
    lastUpdatedAt: Instant,

    // Extensibility
    serviceMetadata: Map[String, Map[String, String]] = Map.empty // service -> key -> value
) derives JsonCodec {

  def addMessage(message: ThreadMessage): Thread = {
    require(message.threadId == id, "Message must belong to this thread")

    val updatedMessages = messages :+ message
    val updatedState = updateStateFromNewMessage(message)

    copy(
      messages = updatedMessages,
      state = updatedState,
      lastUpdatedAt = message.createdAt // Use message creation time for thread update
    )
  }

  def updateMessage(messageId: MessageId, updater: ThreadMessage => ThreadMessage): Thread = {
    val updatedMessages = messages.map { msg =>
      if (msg.id == messageId) updater(msg) else msg
    }
    copy(
      messages = updatedMessages,
      lastUpdatedAt = Instant.now()
    )
  }

  def deleteMessage(messageId: MessageId): Thread =
    updateMessage(messageId, _.markDeleted)

  def addReactionToMessage(messageId: MessageId, emoji: String, userId: UserId): Thread = {
    val now = Instant.now()
    updateMessage(messageId, _.addReaction(emoji, userId, now))
  }

  def removeReactionFromMessage(messageId: MessageId, emoji: String, userId: UserId): Thread =
    updateMessage(messageId, _.removeReaction(emoji, userId))

  // Bot-specific queries
  def botMessages: List[ThreadMessage] =
    messages.filter(_.isFromBot(botIdentity))

  def userMessages: List[ThreadMessage] =
    messages.filter(_.isFromUser)

  def externalServiceMessages: List[ThreadMessage] =
    messages.filter(_.isFromExternalService)

  def lastBotMessage: Option[ThreadMessage] =
    botMessages.lastOption

  def lastUserMessage: Option[ThreadMessage] =
    userMessages.lastOption

  def hasUnreadUserMessages: Boolean =
    (lastUserMessage, lastBotMessage) match {
      case (Some(userMsg), Some(botMsg)) => userMsg.createdAt.isAfter(botMsg.createdAt)
      case (Some(_), None)               => true
      case _                             => false
    }

  def participants: Set[UserId] =
    messages.map(_.author).toSet

  // Service integration
  def addServiceMetadata(serviceName: String, key: String, value: String): Thread = {
    val serviceData = serviceMetadata.getOrElse(serviceName, Map.empty)
    val updatedServiceData = serviceData + (key -> value)
    copy(serviceMetadata = serviceMetadata + (serviceName -> updatedServiceData))
  }

  def getServiceMetadata(serviceName: String, key: String): Option[String] =
    serviceMetadata.get(serviceName).flatMap(_.get(key))

  private def updateStateFromNewMessage(message: ThreadMessage): ThreadState = {
    val now = message.createdAt // Use when the message was originally created
    val isBot = message.isFromBot(botIdentity)

    state.copy(
      participantCount = participants.size + 1, // +1 for the new message author
      lastHumanActivity = if (!isBot) Some(now) else state.lastHumanActivity,
      lastBotActivity = if (isBot) Some(now) else state.lastBotActivity
    )
  }
}

// Factory for creating threads from Slack messages
object Thread:

  // Utility to convert Slack timestamp (Double) to Instant
  def slackTimestampToInstant(slackTs: Double): Instant = {
    val seconds = slackTs.toLong
    val nanos = ((slackTs - seconds) * 1_000_000_000L).toLong
    Instant.ofEpochSecond(seconds, nanos)
  }

  /** Creates a new Thread from an AppMention event. Only creates a thread if the mention is NOT
    * part of an existing thread.
    *
    * @param appMention
    *   The AppMention event from Slack
    * @param channelId
    *   The channel where the mention occurred
    * @param botIdentity
    *   The bot's identity for message filtering
    * @return
    *   Some(Thread) if this is a new thread mention, None if it's a thread reply
    */
  def fromAppMention(
      appMention: AppMention,
      channelId: ChannelId,
      botIdentity: BotIdentity
  ): Option[Thread] =
    // Only create threads for mentions that are NOT in existing threads
    appMention.thread_ts match {
      case Some(_) =>
        // This mention is in an existing thread - discard it
        None
      case None =>
        // This is a new mention in the channel - create a new thread
        val threadId = ThreadId(appMention.ts)
        val messageId = MessageId(appMention.ts)
        val authorId = UserId(appMention.user)
        val content = appMention.text
        val now = Instant.now() // Domain object creation time

        val threadMessage = ThreadMessage(
          id = messageId,
          threadId = threadId,
          source = MessageSource.SlackUser,
          author = authorId,
          content = content,
          createdAt = now, // When we created this domain object
          updatedAt = now, // Initially same as created
          slackCreatedAt = Some(slackTimestampToInstant(appMention.ts)),
          slackEventId = Some(appMention.event_ts)
        )

        Some(
          Thread(
            id = threadId,
            channelId = channelId,
            botIdentity = botIdentity,
            rootMessage = threadMessage,
            messages = List(threadMessage),
            state = ThreadState(
              participantCount = 1,
              lastHumanActivity = Some(now),
              lastBotActivity = None
            ),
            createdAt = now,
            lastUpdatedAt = now
          )
        )
    }

  def fromSlackMessage(
      channelId: ChannelId,
      slackMessage: slacksocket.demo.domain.slack.Message,
      botIdentity: BotIdentity
  ): Thread = {

    // Determine if this is a thread start or continuation
    val threadId = slackMessage.thread_ts
      .map(ThreadId.apply)
      .getOrElse(ThreadId(slackMessage.ts))

    val messageId = MessageId(slackMessage.ts)
    val authorId = UserId(slackMessage.user.getOrElse("unknown"))
    val content = slackMessage.text.getOrElse("")
    val now = Instant.now() // Domain object creation time

    val source = if (authorId == botIdentity.userId) {
      MessageSource.Self
    } else {
      slackMessage.subtype match {
        case Some("bot_message") => MessageSource.SlackBot
        case _                   => MessageSource.SlackUser
      }
    }

    val threadMessage = ThreadMessage(
      id = messageId,
      threadId = threadId,
      source = source,
      author = authorId,
      content = content,
      createdAt = now, // When we created this domain object
      updatedAt = now, // Initially same as created
      slackCreatedAt = Some(slackTimestampToInstant(slackMessage.ts)),
      slackEventId = None // Will be set by the service layer
    )

    Thread(
      id = threadId,
      channelId = channelId,
      botIdentity = botIdentity,
      rootMessage = threadMessage,
      messages = List(threadMessage),
      state = ThreadState(
        participantCount = 1,
        lastHumanActivity = if (source == MessageSource.SlackUser) Some(now) else None,
        lastBotActivity = if (source == MessageSource.Self) Some(now) else None
      ),
      createdAt = now,
      lastUpdatedAt = now
    )
  }

  // Factory for external services to create messages
  def createServiceMessage(
      threadId: ThreadId,
      serviceName: String,
      content: String,
      serviceUserId: UserId = UserId("system")
  ): ThreadMessage = {
    val now = Instant.now()
    // Generate a timestamp-based ID for service messages (non-Slack)
    val serviceMessageId = now.getEpochSecond.toDouble + (now.getNano / 1_000_000_000.0)

    ThreadMessage(
      id = MessageId(serviceMessageId),
      threadId = threadId,
      source = MessageSource.ExternalService(serviceName),
      author = serviceUserId,
      content = content,
      createdAt = now, // When we created this domain object
      updatedAt = now // Initially same as created
    )
  }
