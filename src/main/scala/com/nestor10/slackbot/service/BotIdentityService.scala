package com.nestor10.slackbot.service

import zio.*
import com.nestor10.slackbot.domain.conversation.{BotIdentity, UserId}

/** Service for managing bot's own identity.
  *
  * Fetches the bot's user ID and username from Slack API once at startup, then caches it. This is
  * critical for correctly identifying our own messages vs user messages vs other bots.
  *
  * Zionomicon References:
  *   - Chapter 17: Dependency Injection (Service pattern with cached state)
  *   - Chapter 9: Ref for shared state (Cached identity)
  */
trait BotIdentityService:
  def getBotIdentity: IO[BotIdentityService.Error, BotIdentity]

object BotIdentityService:

  sealed trait Error extends Exception
  case class SlackApiError(message: String) extends Error

  case class Live(
      slackClient: SlackApiClient,
      cachedIdentity: Ref[Option[BotIdentity]]
  ) extends BotIdentityService:

    override def getBotIdentity: IO[Error, BotIdentity] =
      cachedIdentity.get.flatMap {
        case Some(identity) =>
          // Already cached - return immediately
          ZIO.succeed(identity)

        case None =>
          // Not cached yet - fetch from Slack API
          ZIO.scoped {
            for {
              _ <- ZIO.logInfo("🤖 BOT_IDENTITY: Fetching bot identity from Slack API...")

              response <- slackClient.authTest
                .mapError(e => SlackApiError(s"Failed to fetch bot identity: ${e.getMessage}"))

              identity = BotIdentity(
                userId = UserId(response.user_id),
                username = response.user,
                displayName = None // auth.test doesn't provide display name
              )

              _ <- cachedIdentity.set(Some(identity))

              _ <- ZIO.logInfo(
                s"✅ BOT_IDENTITY: Cached bot identity - userId=${identity.userId.value} username=${identity.username}"
              )

            } yield identity
          }
      }

  object Live:

    /** Layer that fails fast if bot identity cannot be fetched from Slack.
      *
      * Uses ZLayer.scoped pattern (Zionomicon Ch 15 + 18) to eagerly initialize the service:
      *   - Fetches bot identity from Slack API during layer construction
      *   - Fails fast if fetch fails (prevents app from starting with broken state)
      *   - Memoized: layer built once, identity cached for entire app lifetime
      *
      * This is intentional - if we can't get our own bot identity, the application is fundamentally
      * broken and should not start.
      */
    val layer: ZLayer[SlackApiClient, Error, BotIdentityService] =
      ZLayer.scoped {
        for {
          client <- ZIO.service[SlackApiClient]
          cache <- Ref.make[Option[BotIdentity]](None)
          service = Live(client, cache)

          // Eagerly fetch bot identity at startup - FAIL FAST if this fails
          _ <- ZIO.logInfo("🤖 BOT_IDENTITY: Fetching bot identity at startup (fail-fast)...")
          identity <- service.getBotIdentity
          _ <- ZIO.logInfo(
            s"✅ BOT_IDENTITY: Startup fetch complete - userId=${identity.userId.value} username=${identity.username}"
          )

        } yield service
      }
