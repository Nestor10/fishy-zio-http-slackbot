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
import slacksocket.demo.domain.conversation.{Thread, ChannelId, BotIdentity, UserId}

trait MessageProcessor:
  def processMessage(message: BusinessMessage): UIO[BusinessMessage]

object MessageProcessor:

  object Live:

    case class Live() extends MessageProcessor {

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
                      s"🆕 NEW_THREAD_CREATED: id=${thread.id.value} channel=${channelId.value}"
                    ) *>
                      // TODO: Pass thread to ThreadHub service for state management
                      ZIO.succeed(event)

                  case None =>
                    ZIO.logInfo(s"❌ THREAD_MENTION_DISCARDED: mention in existing thread") *>
                      // This mention was in an existing thread - we ignore it per bot behavior
                      ZIO.succeed(event)
                }
              }

          case message: Message =>
            // Pattern match on channel_type to determine message context
            message.channel_type match {
              case "channel" =>
                val isReply = message.thread_ts.isDefined
                val logPrefix = if (isReply) "🧵 THREAD_REPLY" else "💬 CHANNEL_MESSAGE"
                ZIO.logInfo(
                  s"$logPrefix: envelope=${event.envelope_id} user=${message.user
                      .getOrElse("unknown")} channel=${message.channel}"
                ) *>
                  message.text.fold(ZIO.unit)(text => ZIO.logInfo(s"💬 MESSAGE_TEXT: $text")) *>
                  message.thread_ts.fold(ZIO.unit)(threadTs =>
                    ZIO.logInfo(s"🧵 THREAD_TS: $threadTs")
                  ) *>
                  // Handle channel message or thread reply
                  ZIO.succeed(event)

              case "im" =>
                ZIO.logInfo(
                  s"📩 DIRECT_MESSAGE: envelope=${event.envelope_id} user=${message.user
                      .getOrElse("unknown")} channel=${message.channel}"
                ) *>
                  message.text.fold(ZIO.unit)(text => ZIO.logInfo(s"📩 DM_TEXT: $text")) *>
                  // Handle direct message - might be more important
                  ZIO.succeed(event)

              case "group" =>
                ZIO.logInfo(
                  s"👥 GROUP_MESSAGE: envelope=${event.envelope_id} user=${message.user
                      .getOrElse("unknown")} channel=${message.channel}"
                ) *>
                  message.text.fold(ZIO.unit)(text => ZIO.logInfo(s"👥 GROUP_TEXT: $text")) *>
                  // Handle private group message
                  ZIO.succeed(event)

              case "mpim" =>
                ZIO.logInfo(
                  s"👥 MULTI_PERSON_IM: envelope=${event.envelope_id} user=${message.user
                      .getOrElse("unknown")} channel=${message.channel}"
                ) *>
                  message.text.fold(ZIO.unit)(text => ZIO.logInfo(s"👥 MPIM_TEXT: $text")) *>
                  // Handle multi-person IM
                  ZIO.succeed(event)

              case otherType =>
                ZIO.logInfo(
                  s"❓ UNKNOWN_MESSAGE_TYPE: envelope=${event.envelope_id} channel_type=$otherType"
                ) *>
                  ZIO.succeed(event)
            }

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

    val layer: ZLayer[Any, Nothing, MessageProcessor] =
      ZLayer.succeed(Live())

  object Stub:

    val layer: ZLayer[Any, Nothing, MessageProcessor] = ZLayer {
      ZIO.succeed(new MessageProcessor {
        override def processMessage(message: BusinessMessage): UIO[BusinessMessage] =
          ZIO.logInfo(
            s"📡 STUB: MessageProcessor.processMessage called for ${message.getClass.getSimpleName}"
          ) *>
            ZIO.succeed(message)
      })
    }
