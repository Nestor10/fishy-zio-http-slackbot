package slacksocket.demo.service

import zio.*
import slacksocket.demo.domain.socket.InboundQueue
import slacksocket.demo.domain.slack.{
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
import slacksocket.demo.domain.conversation.{
  Thread,
  ThreadMessage,
  ThreadId,
  ChannelId,
  BotIdentity,
  UserId
}
import java.time.Instant

trait MessageProcessorService:
  def processMessage(message: BusinessMessage): UIO[BusinessMessage]

object MessageProcessorService:

  object Live:

    // Phase 7b: Simplified to only depend on MessageStore (Option 2 pattern)
    // MessageStore now handles event publishing
    case class Live(messageStore: MessageStore, botIdentityService: BotIdentityService)
        extends MessageProcessorService {

      /** Helper to handle thread reply storage for any channel type. The channel_type
        * (channel/im/group/mpim) is Slack transport detail - our domain model doesn't care about it
        * since storage logic is identical.
        */
      private def handleThreadReply(
          message: Message,
          channelType: String,
          envelopeId: String
      ): UIO[Unit] =
        message.thread_ts match {
          case Some(threadTsDouble) =>
            val threadId = ThreadId(threadTsDouble)
            // Check if we have this thread stored
            messageStore
              .retrieveThread(threadId)
              .flatMap { thread =>
                // We have the thread! Create and store the message
                val botIdentity = thread.botIdentity
                val threadMessage =
                  ThreadMessage.fromSlackMessage(message, threadId, botIdentity)

                messageStore
                  .store(threadMessage)
                  .flatMap { msgId =>
                    ZIO.logInfo(
                      s"💾 THREAD_REPLY_STORED: thread=${threadId.formatted} message=${msgId.formatted} author=${threadMessage.author.value} channel_type=$channelType"
                    )
                    // Phase 7b: MessageStore now publishes MessageStored event (Option 2)
                  }
                  .tapError(error => ZIO.logError(s"❌ FAILED_TO_STORE_REPLY: ${error.getMessage}"))
                  .catchAll(_ => ZIO.unit) // Continue even if storage fails
              }
              .catchAll { error =>
                // Thread not found - this is expected for non-bot threads
                ZIO.logDebug(
                  s"🔍 THREAD_NOT_TRACKED: thread=${threadId.formatted} (not a bot thread)"
                )
              }
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
                            s"💾 THREAD_STORED: id=${thread.id.formatted} messages=${thread.messages.size}"
                          ) *>
                          // Phase 7b: MessageStore now publishes ThreadCreated event (Option 2)
                          // Verify storage by retrieving what we just stored
                          messageStore
                            .retrieveThread(thread.id)
                            .flatMap { retrievedThread =>
                              ZIO.logInfo(
                                s"✅ STORAGE_VERIFIED: Retrieved thread ${retrievedThread.id.formatted} with ${retrievedThread.messages.size} messages"
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
    val layer: ZLayer[MessageStore & BotIdentityService, Nothing, MessageProcessorService] =
      ZLayer.fromZIO {
        for {
          store <- ZIO.service[MessageStore]
          botIdentity <- ZIO.service[BotIdentityService]
        } yield Live(store, botIdentity)
      }

  object Stub:

    val layer: ZLayer[Any, Nothing, MessageProcessorService] = ZLayer {
      ZIO.succeed(new MessageProcessorService {
        override def processMessage(message: BusinessMessage): UIO[BusinessMessage] =
          ZIO.logInfo(
            s"📡 STUB: MessageProcessorService.processMessage called for ${message.getClass.getSimpleName}"
          ) *>
            ZIO.succeed(message)
      })
    }
