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

    case class Live(messageStore: MessageStore, eventBus: MessageEventBus)
        extends MessageProcessorService {

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
                val botIdentity = BotIdentity(UserId("BOT123"), "demo-bot") // TODO: Get from config

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
                      // Publish ThreadCreated event
                      eventBus.publish(
                        MessageEventBus.MessageEvent.ThreadCreated(thread, Instant.now())
                      ) *>
                      ZIO.logInfo(s"📢 EVENT_PUBLISHED: ThreadCreated(${thread.id.formatted})") *>
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

          case message: Message =>
            // 🔍 DIAGNOSTIC: Log all message fields to understand bot message structure
            ZIO.logInfo(
              s"""📋 MESSAGE_DIAGNOSTIC:
                 |  user: ${message.user}
                 |  channel: ${message.channel}
                 |  channel_type: ${message.channel_type}
                 |  text: ${message.text}
                 |  ts: ${message.ts}
                 |  event_ts: ${message.event_ts}
                 |  subtype: ${message.subtype}
                 |  thread_ts: ${message.thread_ts}
                 |  parent_user_id: ${message.parent_user_id}""".stripMargin
            ) *>
              // FILTER: Ignore bot messages to prevent infinite loops
              (message.subtype match {
                case Some("bot_message") =>
                  ZIO.logDebug(
                    s"🤖 BOT_MESSAGE_IGNORED: channel=${message.channel} (preventing loop)"
                  ) *>
                    ZIO.succeed(event)

                case _ =>
                  // Pattern match on channel_type to determine message context
                  message.channel_type match {
                    case "channel" =>
                      val isReply = message.thread_ts.isDefined
                      val logPrefix = if (isReply) "🧵 THREAD_REPLY" else "💬 CHANNEL_MESSAGE"
                      ZIO.logInfo(
                        s"$logPrefix: envelope=${event.envelope_id} user=${message.user
                            .getOrElse("unknown")} channel=${message.channel}"
                      ) *>
                        message.text.fold(ZIO.unit)(text =>
                          ZIO.logInfo(s"💬 MESSAGE_TEXT: $text")
                        ) *>
                        message.thread_ts.fold(ZIO.unit)(threadTs =>
                          ZIO.logInfo(s"🧵 THREAD_TS: $threadTs")
                        ) *>
                        // MVP: Store thread replies if we have the thread stored
                        (message.thread_ts match {
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
                                      s"💾 THREAD_REPLY_STORED: thread=${threadId.formatted} message=${msgId.formatted} author=${threadMessage.author.value}"
                                    ) *>
                                      // Publish MessageStored event
                                      eventBus.publish(
                                        MessageEventBus.MessageEvent.MessageStored(
                                          threadMessage,
                                          Instant.now()
                                        )
                                      ) *>
                                      ZIO.logInfo(
                                        s"📢 EVENT_PUBLISHED: MessageStored(${msgId.formatted})"
                                      )
                                  }
                                  .tapError(error =>
                                    ZIO.logError(s"❌ FAILED_TO_STORE_REPLY: ${error.getMessage}")
                                  )
                                  .catchAll(_ => ZIO.unit) // Continue even if storage fails
                              }
                              .catchAll { error =>
                                // Thread not found - this is expected for non-bot threads
                                ZIO.logDebug(
                                  s"🔍 THREAD_NOT_TRACKED: thread=${threadId.formatted} (not a bot thread)"
                                )
                              }
                          case None =>
                            // Not a thread reply, just a regular channel message
                            ZIO.unit
                        }) *>
                        ZIO.succeed(event)

                    case "im" =>
                      val isReply = message.thread_ts.isDefined
                      val logPrefix = if (isReply) "🧵 DM_THREAD_REPLY" else "📩 DIRECT_MESSAGE"
                      ZIO.logInfo(
                        s"$logPrefix: envelope=${event.envelope_id} user=${message.user
                            .getOrElse("unknown")} channel=${message.channel}"
                      ) *>
                        message.text.fold(ZIO.unit)(text => ZIO.logInfo(s"📩 DM_TEXT: $text")) *>
                        message.thread_ts.fold(ZIO.unit)(threadTs =>
                          ZIO.logInfo(s"🧵 THREAD_TS: $threadTs")
                        ) *>
                        // MVP: Store thread replies if we have the thread stored
                        (message.thread_ts match {
                          case Some(threadTsDouble) =>
                            val threadId = ThreadId(threadTsDouble)
                            messageStore
                              .retrieveThread(threadId)
                              .flatMap { thread =>
                                val botIdentity = thread.botIdentity
                                val threadMessage =
                                  ThreadMessage.fromSlackMessage(message, threadId, botIdentity)

                                messageStore
                                  .store(threadMessage)
                                  .flatMap { msgId =>
                                    ZIO.logInfo(
                                      s"💾 THREAD_REPLY_STORED: thread=${threadId.formatted} message=${msgId.formatted} author=${threadMessage.author.value}"
                                    ) *>
                                      // Publish MessageStored event
                                      eventBus.publish(
                                        MessageEventBus.MessageEvent.MessageStored(
                                          threadMessage,
                                          Instant.now()
                                        )
                                      ) *>
                                      ZIO.logInfo(
                                        s"📢 EVENT_PUBLISHED: MessageStored(${msgId.formatted})"
                                      )
                                  }
                                  .tapError(error =>
                                    ZIO.logError(s"❌ FAILED_TO_STORE_REPLY: ${error.getMessage}")
                                  )
                                  .catchAll(_ => ZIO.unit)
                              }
                              .catchAll { error =>
                                ZIO.logDebug(
                                  s"🔍 THREAD_NOT_TRACKED: thread=${threadId.formatted} (not a bot thread)"
                                )
                              }
                          case None =>
                            ZIO.unit
                        }) *>
                        ZIO.succeed(event)

                    case "group" =>
                      val isReply = message.thread_ts.isDefined
                      val logPrefix = if (isReply) "🧵 GROUP_THREAD_REPLY" else "👥 GROUP_MESSAGE"
                      ZIO.logInfo(
                        s"$logPrefix: envelope=${event.envelope_id} user=${message.user
                            .getOrElse("unknown")} channel=${message.channel}"
                      ) *>
                        message.text.fold(ZIO.unit)(text => ZIO.logInfo(s"👥 GROUP_TEXT: $text")) *>
                        message.thread_ts.fold(ZIO.unit)(threadTs =>
                          ZIO.logInfo(s"🧵 THREAD_TS: $threadTs")
                        ) *>
                        // MVP: Store thread replies if we have the thread stored
                        (message.thread_ts match {
                          case Some(threadTsDouble) =>
                            val threadId = ThreadId(threadTsDouble)
                            messageStore
                              .retrieveThread(threadId)
                              .flatMap { thread =>
                                val botIdentity = thread.botIdentity
                                val threadMessage =
                                  ThreadMessage.fromSlackMessage(message, threadId, botIdentity)

                                messageStore
                                  .store(threadMessage)
                                  .flatMap { msgId =>
                                    ZIO.logInfo(
                                      s"💾 THREAD_REPLY_STORED: thread=${threadId.formatted} message=${msgId.formatted} author=${threadMessage.author.value}"
                                    ) *>
                                      // Publish MessageStored event
                                      eventBus.publish(
                                        MessageEventBus.MessageEvent.MessageStored(
                                          threadMessage,
                                          Instant.now()
                                        )
                                      ) *>
                                      ZIO.logInfo(
                                        s"📢 EVENT_PUBLISHED: MessageStored(${msgId.formatted})"
                                      )
                                  }
                                  .tapError(error =>
                                    ZIO.logError(s"❌ FAILED_TO_STORE_REPLY: ${error.getMessage}")
                                  )
                                  .catchAll(_ => ZIO.unit)
                              }
                              .catchAll { error =>
                                ZIO.logDebug(
                                  s"🔍 THREAD_NOT_TRACKED: thread=${threadId.formatted} (not a bot thread)"
                                )
                              }
                          case None =>
                            ZIO.unit
                        }) *>
                        ZIO.succeed(event)

                    case "mpim" =>
                      val isReply = message.thread_ts.isDefined
                      val logPrefix = if (isReply) "🧵 MPIM_THREAD_REPLY" else "👥 MULTI_PERSON_IM"
                      ZIO.logInfo(
                        s"$logPrefix: envelope=${event.envelope_id} user=${message.user
                            .getOrElse("unknown")} channel=${message.channel}"
                      ) *>
                        message.text.fold(ZIO.unit)(text => ZIO.logInfo(s"👥 MPIM_TEXT: $text")) *>
                        message.thread_ts.fold(ZIO.unit)(threadTs =>
                          ZIO.logInfo(s"🧵 THREAD_TS: $threadTs")
                        ) *>
                        // MVP: Store thread replies if we have the thread stored (same logic as channel)
                        (message.thread_ts match {
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
                                      s"💾 THREAD_REPLY_STORED: thread=${threadId.formatted} message=${msgId.formatted} author=${threadMessage.author.value}"
                                    ) // *>
                                    // Publish MessageStored event
                                    // eventBus.publish(
                                    //   MessageEventBus.MessageEvent.MessageStored(
                                    //     threadMessage,
                                    //     Instant.now()
                                    //   )
                                    // ) *>
                                    // ZIO.logInfo(
                                    //   s"📢 EVENT_PUBLISHED: MessageStored(${msgId.formatted})"
                                    // )
                                  }
                                  .tapError(error =>
                                    ZIO.logError(s"❌ FAILED_TO_STORE_REPLY: ${error.getMessage}")
                                  )
                                  .catchAll(_ => ZIO.unit) // Continue even if storage fails
                              }
                              .catchAll { error =>
                                // Thread not found - this is expected for non-bot threads
                                ZIO.logDebug(
                                  s"🔍 THREAD_NOT_TRACKED: thread=${threadId.formatted} (not a bot thread)"
                                )
                              }
                          case None =>
                            // Not a thread reply, just a regular MPIM message
                            ZIO.unit
                        }) *>
                        ZIO.succeed(event)

                    case otherType =>
                      ZIO.logInfo(
                        s"❓ UNKNOWN_MESSAGE_TYPE: envelope=${event.envelope_id} channel_type=$otherType"
                      ) *>
                        ZIO.succeed(event)
                  }
              })

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

    val layer: ZLayer[MessageStore & MessageEventBus, Nothing, MessageProcessorService] =
      ZLayer.fromZIO {
        for {
          store <- ZIO.service[MessageStore]
          bus <- ZIO.service[MessageEventBus]
        } yield Live(store, bus)
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
