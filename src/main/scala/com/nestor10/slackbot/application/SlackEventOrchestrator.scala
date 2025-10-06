package com.nestor10.slackbot.application

import zio.*
import com.nestor10.slackbot.domain.model.socket.InboundQueue
import com.nestor10.slackbot.domain.model.slack.{
  BusinessMessage,
  EventsApiMessage,
  InteractiveMessage,
  SlashCommand,
  AppMention,
  Message,
  ReactionAdded,
  ChannelCreated,
  TeamJoin,
  AppHomeOpened,
  UnknownEvent
}
import com.nestor10.slackbot.domain.model.conversation.{
  Thread,
  ThreadMessage,
  ThreadId,
  ChannelId,
  BotIdentity,
  UserId
}
import java.time.Instant
import com.nestor10.slackbot.infrastructure.storage.MessageStore
import com.nestor10.slackbot.infrastructure.slack.BotIdentityService
import com.nestor10.slackbot.infrastructure.slack.SlackApiClient

trait SlackEventOrchestrator:
  def processMessage(message: BusinessMessage): UIO[BusinessMessage]

object SlackEventOrchestrator:

  object Live:

    // Phase 7b: Simplified to only depend on MessageStore (Option 2 pattern)
    // MessageStore now handles event publishing
    // Phase 10: Added SlackApiClient for thread recovery
    case class Live(
        messageStore: MessageStore,
        botIdentityService: BotIdentityService,
        slackApiClient: SlackApiClient
    ) extends SlackEventOrchestrator {

      /** Recover a thread from Slack's conversation history.
        *
        * This is used when the bot restarts and encounters a thread reply for a thread not in
        * memory. We fetch the full thread history from Slack, verify it was created by an
        * @mention,
        *   rebuild the Thread domain object, and store it along with all messages.
        *
        * @param channelId
        *   The channel where the thread exists
        * @param threadId
        *   The thread timestamp
        * @return
        *   The recovered Thread, or fails if API call fails or thread invalid
        */
      private def recoverThreadFromSlack(
          channelId: ChannelId,
          threadId: ThreadId
      ): IO[Throwable, Thread] =
        ZIO.scoped {
          for {
            _ <- ZIO.logInfo(
              s"🔄 THREAD_RECOVERY: Fetching thread ${threadId.formatted} from Slack API"
            )
            botIdentity <- botIdentityService.getBotIdentity.orDie
            response <- slackApiClient
              .getConversationReplies(channelId, threadId)
              .mapError(err => new RuntimeException(s"Slack API error: ${err.getMessage}"))
            slackMessages <- ZIO
              .fromOption(response.messages)
              .orElseFail(new RuntimeException("No messages in thread"))
            threadAndMessages <- ZIO
              .fromOption(
                Thread.fromSlackMessages(
                  slackMessages,
                  botIdentity.userId.value,
                  channelId,
                  botIdentity
                )
              )
              .orElseFail(
                new RuntimeException(
                  "Thread recovery failed: first message doesn't mention bot"
                )
              )
            (thread, messages) = threadAndMessages
            // Store the thread metadata
            _ <- messageStore
              .storeThread(thread)
              .mapError(err => new RuntimeException(s"Storage error: ${err.getMessage}"))
            // Store each individual message
            _ <- ZIO.foreach(messages) { msg =>
              messageStore
                .store(msg)
                .mapError(err =>
                  new RuntimeException(s"Failed to store message: ${err.getMessage}")
                )
            }
            _ <- ZIO.logInfo(
              s"✅ THREAD_RECOVERED: id=${threadId.formatted} with ${messages.size} messages from Slack"
            )
          } yield thread
        }

      /** Helper to handle thread reply storage for any channel type. The channel_type
        * (channel/im/group/mpim) is Slack transport detail - our domain model doesn't care about it
        * since storage logic is identical.
        *
        * Phase 10: Now attempts thread recovery from Slack if thread not found in memory.
        */
      private def handleThreadReply(
          message: Message,
          channelType: String,
          envelopeId: String
      ): UIO[Unit] =
        message.thread_ts match {
          case Some(threadTsDouble) =>
            val threadId = ThreadId(threadTsDouble)
            val channelId = ChannelId(message.channel)

            // Try to retrieve thread, with recovery fallback
            for {
              threadOpt <- messageStore.retrieveThread(threadId).option
              thread <- threadOpt match {
                case Some(t) =>
                  // Fast path: Thread already in memory
                  ZIO.succeed(Some(t))
                case None =>
                  // Slow path: Thread not in memory, try to recover from Slack
                  ZIO.logInfo(
                    s"🔍 THREAD_NOT_IN_MEMORY: Attempting recovery for ${threadId.formatted}"
                  ) *>
                    recoverThreadFromSlack(channelId, threadId)
                      .tapError(err =>
                        ZIO.logWarning(
                          s"⚠️ THREAD_RECOVERY_FAILED: ${err.getMessage} - skipping message"
                        )
                      )
                      .option // Convert failure to None
              }
              _ <- thread match {
                case Some(t) =>
                  // We have the thread! Create and store the message
                  val botIdentity = t.botIdentity
                  val threadMessage =
                    ThreadMessage.fromSlackMessage(message, threadId, botIdentity)

                  messageStore
                    .store(threadMessage)
                    .flatMap { msgId =>
                      ZIO.logInfo(
                        s"💾 THREAD_REPLY_STORED: thread=${threadId.formatted} message=${msgId.formatted} author=${threadMessage.author.value} channel_type=$channelType"
                      )
                    }
                    .tapError(error =>
                      ZIO.logError(s"❌ FAILED_TO_STORE_REPLY: ${error.getMessage}")
                    )
                    .catchAll(_ => ZIO.unit) // Continue even if storage fails
                case None =>
                  // Recovery failed, skip this message
                  ZIO.unit
              }
            } yield ()
          case None =>
            // Not a thread reply, just a regular message
            ZIO.unit
        }

      override def processMessage(message: BusinessMessage): UIO[BusinessMessage] =
        message match {
          case event: EventsApiMessage =>
            ZIO.logInfo(s"📢 EventsApiMessage: ${event.payload.`type`}") *>
              processEventsApiMessage(event)
          case interactive: InteractiveMessage =>
            processInteractiveMessage(interactive)
          case slash: SlashCommand =>
            processSlashCommand(slash)
        }

      private def processEventsApiMessage(event: EventsApiMessage): UIO[BusinessMessage] =
        // Pattern match on the specific Slack event type
        event.payload.event match {
          case appMention: AppMention =>
            ZIO.logInfo(
              s"📢 APP_MENTION: envelope=${event.envelope_id} user=${appMention.user} channel=${appMention.channel} thread=${appMention.thread_ts
                  .getOrElse("new")}"
            ) *>
              ZIO.logInfo(s"📢 APP_MENTION_TEXT: ${appMention.text}") *> {

                // Use domain model to determine if we should act on this mention
                val channelId = ChannelId(appMention.channel)

                // Get bot identity from service
                // Note: BotIdentityService fails fast at startup if it can't fetch identity,
                // so we know this will always succeed here (identity is cached)
                botIdentityService.getBotIdentity.orDie // Convert Error to defect - should never fail after startup
                  .flatMap { botIdentity =>
                    Thread.fromAppMention(appMention, channelId, botIdentity) match {
                      case Some(thread) =>
                        ZIO.logInfo(
                          s"🆕 NEW_THREAD_CREATED: id=${thread.id.formatted} channel=${channelId.value}"
                        ) *>
                          // Store the thread in MessageStore
                          messageStore
                            .storeThread(thread)
                            .tapError(error =>
                              ZIO.logError(s"❌ FAILED_TO_STORE_THREAD: ${error.getMessage}")
                            )
                            .catchAll(_ => ZIO.unit) // Log error but don't fail message processing
                          *>
                          ZIO.logInfo(
                            s"💾 THREAD_STORED: id=${thread.id.formatted}"
                          ) *>
                          // Phase 7b: MessageStore now publishes ThreadCreated event (Option 2)
                          // Verify storage by retrieving what we just stored
                          messageStore
                            .retrieveThread(thread.id)
                            .flatMap { retrievedThread =>
                              ZIO.logInfo(
                                s"✅ STORAGE_VERIFIED: Retrieved thread ${retrievedThread.id.formatted}"
                              )
                            }
                            .tapError(error =>
                              ZIO.logError(s"⚠️ STORAGE_VERIFICATION_FAILED: ${error.getMessage}")
                            )
                            .catchAll(_ => ZIO.unit) *>
                          ZIO.succeed(event)

                      case None =>
                        ZIO.logInfo(s"❌ THREAD_MENTION_DISCARDED: mention in existing thread") *>
                          // This mention was in an existing thread - we ignore it per bot behavior
                          ZIO.succeed(event)
                    }
                  }
              }

          case message: Message =>
            // Phase 7c: No filtering in MessageProcessor - processors filter via canProcess()
            // Store ALL messages (including bot's own) for complete conversation history

            // Log the message with appropriate icon based on channel type and thread status
            val isReply = message.thread_ts.isDefined
            val logIcon = message.channel_type match {
              case "im"    => if (isReply) "🧵 DM_THREAD_REPLY" else "📩 DIRECT_MESSAGE"
              case "group" => if (isReply) "🧵 GROUP_THREAD_REPLY" else "👥 GROUP_MESSAGE"
              case "mpim"  => if (isReply) "🧵 MPIM_THREAD_REPLY" else "👥 MULTI_PERSON_IM"
              case _       => if (isReply) "🧵 THREAD_REPLY" else "� CHANNEL_MESSAGE"
            }

            ZIO.logInfo(
              s"$logIcon: envelope=${event.envelope_id} user=${message.user
                  .getOrElse("unknown")} channel=${message.channel}"
            ) *>
              message.text.fold(ZIO.unit)(text => ZIO.logInfo(s"� MESSAGE_TEXT: $text")) *>
              message.thread_ts.fold(ZIO.unit)(threadTs =>
                ZIO.logInfo(s"🧵 THREAD_TS: $threadTs")
              ) *>
              // Handle thread reply storage (identical logic for all channel types)
              handleThreadReply(message, message.channel_type, event.envelope_id) *>
              ZIO.succeed(event)

          case reactionAdded: ReactionAdded =>
            ZIO.logInfo(
              s"👍 REACTION_ADDED: envelope=${event.envelope_id} user=${reactionAdded.user} reaction=${reactionAdded.reaction}"
            ) *>
              // Handle reaction - analytics, gamification, etc.
              // TODO: Add reaction tracking, engagement metrics, etc.
              ZIO.succeed(event)

          case channelCreated: ChannelCreated =>
            ZIO.logInfo(
              s"🆕 CHANNEL_CREATED: envelope=${event.envelope_id} channel=${channelCreated.channel.name}"
            ) *>
              // Handle new channel - auto-join bot, welcome message, etc.
              // TODO: Add channel setup, permissions check, etc.
              ZIO.succeed(event)

          case teamJoin: TeamJoin =>
            ZIO.logInfo(
              s"👋 TEAM_JOIN: envelope=${event.envelope_id} user=${teamJoin.user}"
            ) *>
              // Handle new team member - onboarding, welcome, etc.
              // TODO: Add user onboarding, welcome DM, etc.
              ZIO.succeed(event)

          case appHomeOpened: AppHomeOpened =>
            ZIO.logInfo(
              s"🏠 APP_HOME_OPENED: envelope=${event.envelope_id} user=${appHomeOpened.user} tab=${appHomeOpened.tab}"
            ) *>
              // Handle app home visit - show personalized content
              // TODO: Add personalized home view, user stats, etc.
              ZIO.succeed(event)

          case unknown: UnknownEvent =>
            ZIO.logInfo(
              s"❓ UNKNOWN_EVENT: envelope=${event.envelope_id} type=${unknown.`type`}"
            ) *>
              // Log unknown events for debugging
              // TODO: Add telemetry for unknown event types
              ZIO.succeed(event)

          case other =>
            ZIO.logInfo(
              s"📨 OTHER_EVENT: envelope=${event.envelope_id} type=${other.getClass.getSimpleName}"
            ) *>
              // Handle other event types generically
              // TODO: Add generic event handling, logging, etc.
              ZIO.succeed(event)
        }

      private def processInteractiveMessage(interactive: InteractiveMessage): UIO[BusinessMessage] =
        ZIO.logInfo(s"🔘 INBOUND_INTERACTIVE: envelope=${interactive.envelope_id}") *>
          // Interactive message pipeline:
          // - Parse interaction
          // - Generate response
          // - Update UI state
          // TODO: Add button clicks, modal submissions, etc.
          ZIO.succeed(interactive)

      private def processSlashCommand(slash: SlashCommand): UIO[BusinessMessage] =
        ZIO.logInfo(s"⚡ INBOUND_SLASH: envelope=${slash.envelope_id}") *>
          // Command processing pipeline:
          // - Parse command
          // - Execute business logic
          // - Format response
          // TODO: Add command routing, validation, response generation
          ZIO.succeed(slash)

    }

    // Phase 7b: Simplified layer to only depend on MessageStore (Option 2 pattern)
    // Phase 8: Added BotIdentityService dependency for real bot user ID
    // Phase 10: Added SlackApiClient for thread recovery
    val layer: ZLayer[
      MessageStore & BotIdentityService & SlackApiClient,
      Nothing,
      SlackEventOrchestrator
    ] =
      ZLayer.fromZIO {
        for {
          store <- ZIO.service[MessageStore]
          botIdentity <- ZIO.service[BotIdentityService]
          slackApi <- ZIO.service[SlackApiClient]
        } yield Live(store, botIdentity, slackApi)
      }

  object Stub:

    val layer: ZLayer[Any, Nothing, SlackEventOrchestrator] = ZLayer {
      ZIO.succeed(new SlackEventOrchestrator {
        override def processMessage(message: BusinessMessage): UIO[BusinessMessage] =
          ZIO.logInfo(
            s"📡 STUB: SlackEventOrchestrator.processMessage called for ${message.getClass.getSimpleName}"
          ) *>
            ZIO.succeed(message)
      })
    }
