package slacksocket.demo.service

import zio._
import zio.http._
import zio.json._
import zio.telemetry.opentelemetry.tracing.Tracing
import slacksocket.demo.conf.AppConfig
import slacksocket.demo.domain.conversation.{ChannelId, ThreadId, MessageId}

/** Slack API client with just enough to open a Socket Mode connection via apps.connections.open */
trait SlackApiClient {

  /** Request a fresh WebSocket URL for Slack Socket Mode (does NOT open the websocket). */
  def requestSocketUrl: ZIO[Scope, SlackApiClient.Error, SlackApiClient.Connection]

  /** Post a message to a Slack channel/thread.
    *
    * @param channelId
    *   Channel to post to
    * @param text
    *   Message text
    * @param threadTs
    *   Optional thread timestamp to reply in thread
    * @return
    *   Message timestamp of posted message
    */
  def postMessage(
      channelId: ChannelId,
      text: String,
      threadTs: Option[ThreadId]
  ): ZIO[Scope, SlackApiClient.Error, String]

  /** Get bot's own identity from Slack.
    *
    * Uses auth.test endpoint to retrieve bot user ID, username, etc.
    *
    * @return
    *   AuthTestResponse with bot identity information
    */
  def authTest: ZIO[Scope, SlackApiClient.Error, SlackApiClient.AuthTestResponse]

  /** Add a reaction emoji to a message.
    *
    * @param channel
    *   Channel containing the message
    * @param timestamp
    *   Message timestamp
    * @param name
    *   Emoji name (without colons, e.g., "robot_face")
    */
  def addReaction(
      channel: ChannelId,
      timestamp: MessageId,
      name: String
  ): ZIO[Scope, SlackApiClient.Error, Unit]

  /** Remove a reaction emoji from a message.
    *
    * @param channel
    *   Channel containing the message
    * @param timestamp
    *   Message timestamp
    * @param name
    *   Emoji name (without colons, e.g., "robot_face")
    */
  def removeReaction(
      channel: ChannelId,
      timestamp: MessageId,
      name: String
  ): ZIO[Scope, SlackApiClient.Error, Unit]

  /** Fetch all messages in a thread (for crash recovery).
    *
    * Uses conversations.replies API to retrieve thread history from Slack. Enables recovery of
    * threads that were active when bot restarted.
    *
    * @param channel
    *   Channel containing the thread
    * @param threadTs
    *   Thread timestamp (root message timestamp)
    * @return
    *   All messages in the thread, oldest first
    */
  def getConversationReplies(
      channel: ChannelId,
      threadTs: ThreadId
  ): ZIO[Scope, SlackApiClient.Error, SlackApiClient.ConversationRepliesResponse]
}

object SlackApiClient {
  final case class Connection(url: String)

  /** Response from auth.test endpoint - bot's own identity */
  final case class AuthTestResponse(
      ok: Boolean,
      url: String,
      team: String,
      user: String, // Bot username
      team_id: String,
      user_id: String, // Bot user ID - THIS IS WHAT WE NEED!
      bot_id: Option[String],
      error: Option[String]
  )

  /** Response from conversations.replies endpoint - thread history */
  final case class ConversationRepliesResponse(
      ok: Boolean,
      messages: Option[List[ConversationMessage]],
      has_more: Option[Boolean],
      error: Option[String]
  )

  /** Message from conversations.replies - simplified structure */
  final case class ConversationMessage(
      ts: String, // Message timestamp (thread_ts for first message)
      user: Option[String], // User ID (None for bot messages)
      bot_id: Option[String], // Bot ID if bot message
      text: String,
      thread_ts: Option[String] // Parent thread timestamp
  )

  sealed trait Error extends Throwable

  object Error {

    final case class HttpError(status: Status, body: String) extends Error {
      override def getMessage: String = s"Slack API HTTP ${status.code}: ${body}"
    }

    final case class DecodeError(message: String, raw: String) extends Error {
      override def getMessage: String = s"Decode error: $message raw=$raw"
    }

    final case class MissingToken() extends Error {
      override def getMessage: String = "Slack app-level token missing (slackAppToken)"
    }

    final case class ApiError(error: String) extends Error {
      override def getMessage: String = s"Slack API error: $error"
    }
  }
  import Error._

  private final case class OpenResponse(ok: Boolean, url: Option[String], error: Option[String])
  private given JsonDecoder[OpenResponse] = DeriveJsonDecoder.gen[OpenResponse]

  given JsonDecoder[AuthTestResponse] = DeriveJsonDecoder.gen[AuthTestResponse]

  given JsonDecoder[ConversationMessage] = DeriveJsonDecoder.gen[ConversationMessage]

  given JsonDecoder[ConversationRepliesResponse] =
    DeriveJsonDecoder.gen[ConversationRepliesResponse]

  private final case class PostMessageRequest(
      channel: String,
      text: String,
      thread_ts: Option[String]
  )
  private given JsonEncoder[PostMessageRequest] = DeriveJsonEncoder.gen[PostMessageRequest]

  private final case class PostMessageResponse(
      ok: Boolean,
      ts: Option[String],
      error: Option[String]
  )
  private given JsonDecoder[PostMessageResponse] = DeriveJsonDecoder.gen[PostMessageResponse]

  private final case class ReactionRequest(
      channel: String,
      timestamp: String,
      name: String
  )
  private given JsonEncoder[ReactionRequest] = DeriveJsonEncoder.gen[ReactionRequest]

  private final case class ReactionResponse(
      ok: Boolean,
      error: Option[String]
  )
  private given JsonDecoder[ReactionResponse] = DeriveJsonDecoder.gen[ReactionResponse]

  object Live {

    final case class Service(client: Client, appToken: String, botToken: String, tracing: Tracing)
        extends SlackApiClient {

      override def requestSocketUrl: ZIO[Scope, Error, Connection] =
        ((for {
          startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

          _ <- tracing.setAttribute("slack.api", "apps.connections.open")
          _ <- tracing.addEvent("slack_api_request_start")

          tok <- ZIO.succeed(appToken)
          _ <- ZIO.logInfo("Requesting WebSocket URL from Slack API")
          req = Request(
            method = Method.POST,
            url = URL.decode("https://slack.com/api/apps.connections.open").toOption.get,
            headers = Headers(
              "Authorization" -> s"Bearer $tok",
              "Content-Type" -> "application/x-www-form-urlencoded"
            )
          )
          resp <- client.request(req)
          body <- resp.body.asString
          _ <- ZIO.fail(HttpError(resp.status, body)).when(!resp.status.isSuccess)
          parsed <- ZIO.fromEither(
            body.fromJson[OpenResponse].left.map(msg => DecodeError(msg, body))
          )
          _ <- ZIO.fail(ApiError(parsed.error.getOrElse("unknown"))).when(!parsed.ok)
          url <- ZIO.fromOption(parsed.url).orElseFail(DecodeError("Missing url field", body))

          endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          latency = endTime - startTime

          _ <- tracing.setAttribute("slack.api.latency_ms", latency.toString)
          _ <- tracing.setAttribute("slack.websocket.url", url)
          _ <- tracing.addEvent("slack_api_response_received")

        } yield Connection(url)) @@ tracing.aspects.span("slack.api.connections_open")).mapError {
          case e: Error => e
          case t        => DecodeError(t.getMessage, t.toString)
        }

      override def postMessage(
          channelId: ChannelId,
          text: String,
          threadTs: Option[ThreadId]
      ): ZIO[Scope, Error, String] =
        ((for {
          startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

          _ <- tracing.setAttribute("slack.api", "chat.postMessage")
          _ <- tracing.setAttribute("slack.channel", channelId.value)
          _ <- ZIO.whenCase(threadTs) { case Some(ts) =>
            tracing.setAttribute("slack.thread_ts", ts.formatted)
          }
          _ <- tracing.addEvent("slack_api_request_start")

          _ <- ZIO.logInfo(
            s"Posting message to channel ${channelId.value}${threadTs.map(t => s" thread ${t.formatted}").getOrElse("")}"
          )
          reqBody = PostMessageRequest(
            channel = channelId.value,
            text = text,
            thread_ts = threadTs.map(_.formatted)
          )
          bodyJson = reqBody.toJson
          req = Request(
            method = Method.POST,
            url = URL.decode("https://slack.com/api/chat.postMessage").toOption.get,
            headers = Headers(
              "Authorization" -> s"Bearer $botToken",
              "Content-Type" -> "application/json; charset=utf-8"
            ),
            body = Body.fromString(bodyJson)
          )
          resp <- client.request(req)
          body <- resp.body.asString
          _ <- ZIO.fail(HttpError(resp.status, body)).when(!resp.status.isSuccess)
          parsed <- ZIO.fromEither(
            body.fromJson[PostMessageResponse].left.map(msg => DecodeError(msg, body))
          )
          _ <- ZIO.fail(ApiError(parsed.error.getOrElse("unknown"))).when(!parsed.ok)
          ts <- ZIO
            .fromOption(parsed.ts)
            .orElseFail(DecodeError("Missing ts field in response", body))

          endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          latency = endTime - startTime

          _ <- tracing.setAttribute("slack.api.latency_ms", latency.toString)
          _ <- tracing.setAttribute("slack.message_ts", ts)
          _ <- tracing.addEvent("slack_api_response_received")

          _ <- ZIO.logInfo(s"✅ Posted message with ts=$ts")
        } yield ts) @@ tracing.aspects.span("slack.api.post_message")).mapError {
          case e: Error     => e
          case t: Throwable => DecodeError(s"Request failed: ${t.getMessage}", t.toString)
        }

      override def authTest: ZIO[Scope, Error, AuthTestResponse] =
        ((for {
          startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

          _ <- tracing.setAttribute("slack.api", "auth.test")
          _ <- tracing.addEvent("slack_api_request_start")

          _ <- ZIO.logInfo("🔍 Calling auth.test to get bot identity")
          req = Request(
            method = Method.POST,
            url = URL.decode("https://slack.com/api/auth.test").toOption.get,
            headers = Headers(
              "Authorization" -> s"Bearer $botToken",
              "Content-Type" -> "application/x-www-form-urlencoded"
            )
          )
          resp <- client.request(req)
          body <- resp.body.asString
          _ <- ZIO.fail(HttpError(resp.status, body)).when(!resp.status.isSuccess)
          parsed <- ZIO.fromEither(
            body.fromJson[AuthTestResponse].left.map(msg => DecodeError(msg, body))
          )
          _ <- ZIO.fail(ApiError(parsed.error.getOrElse("unknown"))).when(!parsed.ok)

          endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          latency = endTime - startTime

          _ <- tracing.setAttribute("slack.api.latency_ms", latency.toString)
          _ <- tracing.setAttribute("slack.bot.user_id", parsed.user_id)
          _ <- tracing.setAttribute("slack.bot.username", parsed.user)
          _ <- tracing.addEvent("slack_api_response_received")

          _ <- ZIO.logInfo(s"✅ auth.test success: user_id=${parsed.user_id} user=${parsed.user}")
        } yield parsed) @@ tracing.aspects.span("slack.api.auth_test")).mapError {
          case e: Error     => e
          case t: Throwable => DecodeError(s"Request failed: ${t.getMessage}", t.toString)
        }

      override def getConversationReplies(
          channel: ChannelId,
          threadTs: ThreadId
      ): ZIO[Scope, Error, ConversationRepliesResponse] =
        ((for {
          startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

          _ <- tracing.setAttribute("slack.api", "conversations.replies")
          _ <- tracing.setAttribute("slack.channel", channel.value)
          _ <- tracing.setAttribute("slack.thread_ts", threadTs.formatted)
          _ <- tracing.addEvent("slack_api_request_start")

          _ <- ZIO.logInfo(
            s"🔄 Fetching thread history: channel=${channel.value} ts=${threadTs.formatted}"
          )
          // Build query parameters
          params = s"channel=${channel.value}&ts=${threadTs.formatted}&limit=100"
          req = Request(
            method = Method.GET,
            url = URL
              .decode(s"https://slack.com/api/conversations.replies?$params")
              .toOption
              .get,
            headers = Headers(
              "Authorization" -> s"Bearer $botToken",
              "Content-Type" -> "application/x-www-form-urlencoded"
            )
          )
          resp <- client.request(req)
          body <- resp.body.asString
          _ <- ZIO.fail(HttpError(resp.status, body)).when(!resp.status.isSuccess)
          parsed <- ZIO.fromEither(
            body.fromJson[ConversationRepliesResponse].left.map(msg => DecodeError(msg, body))
          )
          _ <- ZIO.fail(ApiError(parsed.error.getOrElse("unknown"))).when(!parsed.ok)

          endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          latency = endTime - startTime
          msgCount = parsed.messages.map(_.size).getOrElse(0)

          _ <- tracing.setAttribute("slack.api.latency_ms", latency.toString)
          _ <- tracing.setAttribute("slack.thread.message_count", msgCount.toString)
          _ <- tracing.addEvent("slack_api_response_received")

          _ <- ZIO.logInfo(s"✅ Fetched $msgCount messages from thread")
        } yield parsed) @@ tracing.aspects.span("slack.api.conversations_replies")).mapError {
          case e: Error     => e
          case t: Throwable => DecodeError(s"Request failed: ${t.getMessage}", t.toString)
        }

      override def addReaction(
          channel: ChannelId,
          timestamp: MessageId,
          name: String
      ): ZIO[Scope, Error, Unit] = {
        val reqBody = ReactionRequest(
          channel = channel.value,
          timestamp = timestamp.formatted,
          name = name
        )
        val bodyJson = reqBody.toJson
        val req = Request(
          method = Method.POST,
          url = URL.decode("https://slack.com/api/reactions.add").toOption.get,
          headers = Headers(
            "Authorization" -> s"Bearer $botToken",
            "Content-Type" -> "application/json; charset=utf-8"
          ),
          body = Body.fromString(bodyJson)
        )

        ((for {
          startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

          _ <- tracing.setAttribute("slack.api", "reactions.add")
          _ <- tracing.setAttribute("slack.channel", channel.value)
          _ <- tracing.setAttribute("slack.message_ts", timestamp.formatted)
          _ <- tracing.setAttribute("slack.reaction.emoji", name)
          _ <- tracing.addEvent("slack_api_request_start")

          resp <- client.request(req)
          body <- resp.body.asString
          _ <- ZIO.fail(HttpError(resp.status, body)).when(!resp.status.isSuccess)
          parsed <- ZIO.fromEither(
            body.fromJson[ReactionResponse].left.map(msg => DecodeError(msg, body))
          )
          _ <- ZIO.fail(ApiError(parsed.error.getOrElse("unknown"))).when(!parsed.ok)

          endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          latency = endTime - startTime

          _ <- tracing.setAttribute("slack.api.latency_ms", latency.toString)
          _ <- tracing.addEvent("slack_api_response_received")

        } yield ()) @@ tracing.aspects.span("slack.api.reactions_add")).mapError {
          case e: Error     => e
          case t: Throwable => DecodeError(s"Request failed: ${t.getMessage}", t.toString)
        }
      }

      override def removeReaction(
          channel: ChannelId,
          timestamp: MessageId,
          name: String
      ): ZIO[Scope, Error, Unit] = {
        val reqBody = ReactionRequest(
          channel = channel.value,
          timestamp = timestamp.formatted,
          name = name
        )
        val bodyJson = reqBody.toJson
        val req = Request(
          method = Method.POST,
          url = URL.decode("https://slack.com/api/reactions.remove").toOption.get,
          headers = Headers(
            "Authorization" -> s"Bearer $botToken",
            "Content-Type" -> "application/json; charset=utf-8"
          ),
          body = Body.fromString(bodyJson)
        )

        ((for {
          startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

          _ <- tracing.setAttribute("slack.api", "reactions.remove")
          _ <- tracing.setAttribute("slack.channel", channel.value)
          _ <- tracing.setAttribute("slack.message_ts", timestamp.formatted)
          _ <- tracing.setAttribute("slack.reaction.emoji", name)
          _ <- tracing.addEvent("slack_api_request_start")

          resp <- client.request(req)
          body <- resp.body.asString
          _ <- ZIO.fail(HttpError(resp.status, body)).when(!resp.status.isSuccess)
          parsed <- ZIO.fromEither(
            body.fromJson[ReactionResponse].left.map(msg => DecodeError(msg, body))
          )
          _ <- ZIO.fail(ApiError(parsed.error.getOrElse("unknown"))).when(!parsed.ok)

          endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          latency = endTime - startTime

          _ <- tracing.setAttribute("slack.api.latency_ms", latency.toString)
          _ <- tracing.addEvent("slack_api_response_received")

        } yield ()) @@ tracing.aspects.span("slack.api.reactions_remove")).mapError {
          case e: Error     => e
          case t: Throwable => DecodeError(s"Request failed: ${t.getMessage}", t.toString)
        }
      }
    }

    val layer: ZLayer[Client & Tracing & Scope, Throwable, SlackApiClient] =
      ZLayer.fromZIO {
        for {
          client <- ZIO.service[Client]
          tracing <- ZIO.service[Tracing]
          cfg <- ZIO
            .config(AppConfig.config)
            .mapError(e => new RuntimeException(s"Config error: ${e.getMessage}", e))
        } yield Service(client, cfg.slackAppToken, cfg.slackBotToken, tracing)
      }
  }

  object Stub {

    val layer: ULayer[SlackApiClient] = ZLayer.succeed {
      new SlackApiClient {
        override def requestSocketUrl: ZIO[Scope, SlackApiClient.Error, SlackApiClient.Connection] =
          ZIO.logInfo("🌐 STUB: SlackApiClient.requestSocketUrl called") *>
            ZIO.succeed(SlackApiClient.Connection("wss://stub-url.com"))

        override def postMessage(
            channelId: ChannelId,
            text: String,
            threadTs: Option[ThreadId]
        ): ZIO[Scope, SlackApiClient.Error, String] =
          ZIO.logInfo(
            s"🌐 STUB: postMessage to ${channelId.value}${threadTs.map(t => s" thread ${t.formatted}").getOrElse("")}: $text"
          ) *>
            ZIO.succeed("1234567890.123456")

        override def authTest: ZIO[Scope, SlackApiClient.Error, SlackApiClient.AuthTestResponse] =
          ZIO.logInfo("🌐 STUB: authTest called") *>
            ZIO.succeed(
              SlackApiClient.AuthTestResponse(
                ok = true,
                url = "https://stub.slack.com",
                team = "Stub Team",
                user = "stub-bot",
                team_id = "T123",
                user_id = "U123STUBBOT",
                bot_id = Some("B123"),
                error = None
              )
            )

        override def addReaction(
            channel: ChannelId,
            timestamp: MessageId,
            name: String
        ): ZIO[Scope, SlackApiClient.Error, Unit] =
          ZIO.logInfo(
            s"🌐 STUB: addReaction :$name: to message ${timestamp.formatted} in ${channel.value}"
          )

        override def removeReaction(
            channel: ChannelId,
            timestamp: MessageId,
            name: String
        ): ZIO[Scope, SlackApiClient.Error, Unit] =
          ZIO.logInfo(
            s"🌐 STUB: removeReaction :$name: from message ${timestamp.formatted} in ${channel.value}"
          )

        override def getConversationReplies(
            channel: ChannelId,
            threadTs: ThreadId
        ): ZIO[Scope, SlackApiClient.Error, SlackApiClient.ConversationRepliesResponse] =
          ZIO.logInfo(
            s"🌐 STUB: getConversationReplies channel=${channel.value} ts=${threadTs.formatted}"
          ) *>
            ZIO.succeed(
              SlackApiClient.ConversationRepliesResponse(
                ok = true,
                messages = Some(
                  List(
                    SlackApiClient.ConversationMessage(
                      ts = threadTs.formatted,
                      user = Some("U123USER"),
                      bot_id = None,
                      text = "Hey <@U123STUBBOT> can you help?",
                      thread_ts = Some(threadTs.formatted)
                    ),
                    SlackApiClient.ConversationMessage(
                      ts = "1234567890.123457",
                      user = None,
                      bot_id = Some("B123"),
                      text = "Sure! How can I assist?",
                      thread_ts = Some(threadTs.formatted)
                    )
                  )
                ),
                has_more = Some(false),
                error = None
              )
            )
      }
    }
  }
}
