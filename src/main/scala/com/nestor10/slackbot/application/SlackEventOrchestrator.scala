package com.nestor10.slackbot.application

import com.nestor10.slackbot.domain.model.conversation.{
  BotIdentity,
  ChannelId,
  Thread,
  ThreadId,
  ThreadMessage,
  UserId
}
import com.nestor10.slackbot.domain.model.slack.{
  AppHomeOpened,
  AppMention,
  BusinessMessage,
  ChannelCreated,
  EventsApiMessage,
  InteractiveMessage,
  Message,
  ReactionAdded,
  SlashCommand,
  TeamJoin,
  UnknownEvent
}
import com.nestor10.slackbot.domain.model.socket.InboundQueue
import com.nestor10.slackbot.infrastructure.observability.LogContext
import com.nestor10.slackbot.infrastructure.slack.{BotIdentityService, SlackApiClient}
import com.nestor10.slackbot.infrastructure.storage.MessageStore
import zio.*

import java.time.Instant

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
              s"Fetching thread ${threadId.formatted} from Slack API"
            ) @@
              LogContext.orchestrator @@
              LogContext.threadId(threadId) @@
              LogContext.channelId(channelId) @@
              LogContext.operation("thread_recovery")
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
            _ <- messageStore
              .storeThread(thread)
              .mapError(err => new RuntimeException(s"Storage error: ${err.getMessage}"))
            _ <- ZIO.foreach(messages) { msg =>
              messageStore
                .store(msg)
                .mapError(err =>
                  new RuntimeException(s"Failed to store message: ${err.getMessage}")
                )
            }
            _ <- ZIO.logInfo(
              s"Thread recovered with ${messages.size} messages from Slack"
            ) @@
              LogContext.orchestrator @@
              LogContext.threadId(threadId) @@
              LogContext.operation("thread_recovery")
          } yield thread
        }

      /** Helper to handle thread reply storage for any channel type. The channel_type
        * (channel/im/group/mpim) is Slack transport detail - our domain model doesn't care about it
        * since storage logic is identical.
        *
        * Phase 10: Now attempts thread recovery from Slack if thread not found in memory.
        *
        * @note
        *   Stores ALL messages (including the bot's own) for complete conversation history.
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

            for {
              threadOpt <- messageStore.retrieveThread(threadId).option
              thread <- threadOpt match {
                case Some(t) =>
                  ZIO.succeed(Some(t))
                case None =>
                  ZIO.logInfo(
                    s"Thread not in memory - attempting recovery for ${threadId.formatted}"
                  ) @@
                    LogContext.orchestrator @@
                    LogContext.threadId(threadId) @@
                    LogContext.operation("thread_recovery") *>
                    recoverThreadFromSlack(channelId, threadId)
                      .tapError(err =>
                        ZIO.logWarning(
                          s"Thread recovery failed: ${err.getMessage} - skipping message"
                        ) @@
                          LogContext.orchestrator @@
                          LogContext.threadId(threadId) @@
                          LogContext.errorType(err.getClass.getSimpleName)
                      )
                      .option
              }
              _ <- thread match {
                case Some(t) =>
                  val botIdentity = t.botIdentity
                  val threadMessage =
                    ThreadMessage.fromSlackMessage(message, threadId, botIdentity)

                  messageStore
                    .store(threadMessage)
                    .flatMap { msgId =>
                      ZIO.logInfo(
                        s"Thread reply stored - message=${msgId.formatted} author=${threadMessage.author.value} channel_type=$channelType"
                      ) @@
                        LogContext.orchestrator @@
                        LogContext.threadId(threadId) @@
                        LogContext.messageId(msgId) @@
                        LogContext.userId(threadMessage.author)
                    }
                    .tapError(error =>
                      ZIO.logError(s"Failed to store reply: ${error.getMessage}") @@
                        LogContext.orchestrator @@
                        LogContext.threadId(threadId) @@
                        LogContext.errorType(error.getClass.getSimpleName)
                    )
                    .catchAll(_ => ZIO.unit)
                case None =>
                  ZIO.unit
              }
            } yield ()
          case None =>
            ZIO.unit
        }

      override def processMessage(message: BusinessMessage): UIO[BusinessMessage] =
        message match {
          case event: EventsApiMessage =>
            ZIO.logInfo(s"EventsApiMessage: ${event.payload.`type`}") @@
              LogContext.orchestrator *>
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
              s"App mention - envelope=${event.envelope_id} user=${appMention.user} channel=${appMention.channel} thread=${appMention.thread_ts
                  .getOrElse("new")}"
            ) @@
              LogContext.orchestrator @@
              LogContext.userId(UserId(appMention.user)) @@
              LogContext.channelId(ChannelId(appMention.channel)) *>
              ZIO.logInfo(s"App mention text: ${appMention.text}") @@
              LogContext.orchestrator *> {

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
                          s"New thread created - id=${thread.id.formatted} channel=${channelId.value}"
                        ) @@
                          LogContext.orchestrator @@
                          LogContext.threadId(thread.id) @@
                          LogContext.channelId(channelId) *>
                          messageStore
                            .storeThread(thread)
                            .tapError(error =>
                              ZIO.logError(s"Failed to store thread: ${error.getMessage}") @@
                                LogContext.orchestrator @@
                                LogContext.threadId(thread.id) @@
                                LogContext.errorType(error.getClass.getSimpleName)
                            )
                            .catchAll(_ => ZIO.unit)
                          *>
                          ZIO.logInfo(
                            s"Thread stored - id=${thread.id.formatted}"
                          ) @@
                          LogContext.orchestrator @@
                          LogContext.threadId(thread.id) *>
                          messageStore
                            .retrieveThread(thread.id)
                            .flatMap { retrievedThread =>
                              ZIO.logInfo(
                                s"Storage verified - retrieved thread ${retrievedThread.id.formatted}"
                              ) @@
                                LogContext.orchestrator @@
                                LogContext.threadId(retrievedThread.id)
                            }
                            .tapError(error =>
                              ZIO.logError(s"Storage verification failed: ${error.getMessage}") @@
                                LogContext.orchestrator @@
                                LogContext.errorType(error.getClass.getSimpleName)
                            )
                            .catchAll(_ => ZIO.unit) *>
                          ZIO.succeed(event)

                      case None =>
                        ZIO.logInfo("Thread mention discarded - mention in existing thread") @@
                          LogContext.orchestrator *>
                          ZIO.succeed(event)
                    }
                  }
              }

          case message: Message =>
            val isReply = message.thread_ts.isDefined
            val messageType = message.channel_type match {
              case "im"    => if (isReply) "dm_thread_reply" else "direct_message"
              case "group" => if (isReply) "group_thread_reply" else "group_message"
              case "mpim"  => if (isReply) "mpim_thread_reply" else "multi_person_im"
              case _       => if (isReply) "thread_reply" else "channel_message"
            }

            ZIO.logInfo(
              s"Message received - type=$messageType envelope=${event.envelope_id} user=${message.user
                  .getOrElse("unknown")} channel=${message.channel}"
            ) @@
              LogContext.orchestrator @@
              LogContext.channelId(ChannelId(message.channel)) @@
              LogContext.operation(messageType) *>
              message.text.fold(ZIO.unit)(text =>
                ZIO.logInfo(s"Message text: $text") @@ LogContext.orchestrator
              ) *>
              message.thread_ts.fold(ZIO.unit)(threadTs =>
                ZIO.logInfo(s"Thread TS: $threadTs") @@
                  LogContext.orchestrator @@
                  LogContext.threadId(ThreadId(threadTs))
              ) *>
              handleThreadReply(message, message.channel_type, event.envelope_id) *>
              ZIO.succeed(event)

          case reactionAdded: ReactionAdded =>
            ZIO.logInfo(
              s"Reaction added - envelope=${event.envelope_id} user=${reactionAdded.user} reaction=${reactionAdded.reaction}"
            ) @@
              LogContext.orchestrator @@
              LogContext.userId(UserId(reactionAdded.user)) *>
              // TODO: Add reaction tracking, engagement metrics, etc.
              ZIO.succeed(event)

          case channelCreated: ChannelCreated =>
            ZIO.logInfo(
              s"Channel created - envelope=${event.envelope_id} channel=${channelCreated.channel.name}"
            ) @@
              LogContext.orchestrator *>
              // TODO: Add channel setup, permissions check, etc.
              ZIO.succeed(event)

          case teamJoin: TeamJoin =>
            ZIO.logInfo(
              s"Team join - envelope=${event.envelope_id} user=${teamJoin.user}"
            ) @@
              LogContext.orchestrator @@
              LogContext.userId(UserId(teamJoin.user)) *>
              // TODO: Add user onboarding, welcome DM, etc.
              ZIO.succeed(event)

          case appHomeOpened: AppHomeOpened =>
            ZIO.logInfo(
              s"App home opened - envelope=${event.envelope_id} user=${appHomeOpened.user} tab=${appHomeOpened.tab}"
            ) @@
              LogContext.orchestrator @@
              LogContext.userId(UserId(appHomeOpened.user)) *>
              // TODO: Add personalized home view, user stats, etc.
              ZIO.succeed(event)

          case unknown: UnknownEvent =>
            ZIO.logInfo(
              s"Unknown event - envelope=${event.envelope_id} type=${unknown.`type`}"
            ) @@
              LogContext.orchestrator *>
              // TODO: Add telemetry for unknown event types
              ZIO.succeed(event)

          case other =>
            ZIO.logInfo(
              s"Other event - envelope=${event.envelope_id} type=${other.getClass.getSimpleName}"
            ) @@
              LogContext.orchestrator *>
              // TODO: Add generic event handling, logging, etc.
              ZIO.succeed(event)
        }

      private def processInteractiveMessage(interactive: InteractiveMessage): UIO[BusinessMessage] =
        ZIO.logInfo(s"Inbound interactive message - envelope=${interactive.envelope_id}") @@
          LogContext.orchestrator *>
          // Interactive message pipeline:
          // - Parse interaction
          // - Generate response
          // - Update UI state
          // TODO: Add button clicks, modal submissions, etc.
          ZIO.succeed(interactive)

      private def processSlashCommand(slash: SlashCommand): UIO[BusinessMessage] =
        ZIO.logInfo(s"Inbound slash command - envelope=${slash.envelope_id}") @@
          LogContext.orchestrator *>
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
            s"STUB: SlackEventOrchestrator.processMessage called for ${message.getClass.getSimpleName}"
          ) @@
            LogContext.orchestrator *>
            ZIO.succeed(message)
      })
    }
