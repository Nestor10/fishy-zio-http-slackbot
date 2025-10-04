package slacksocket.demo.service

import zio._
import zio.http._
import zio.json._
import slacksocket.demo.conf.AppConfig
import slacksocket.demo.domain.conversation.{ChannelId, ThreadId}

/** Slack API client with just enough to open a Socket Mode connection via apps.connections.open */
trait SlackApiClient {

  /** Request a fresh WebSocket URL for Slack Socket Mode (does NOT open the websocket). */
  def requestSocketUrl: IO[SlackApiClient.Error, SlackApiClient.Connection]

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
  ): IO[SlackApiClient.Error, String]
}

object SlackApiClient {
  final case class Connection(url: String)

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

  object Live {

    final case class Service(client: Client, appToken: String, botToken: String)
        extends SlackApiClient {

      override def requestSocketUrl: IO[Error, Connection] =
        (for {
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
        } yield Connection(url)).provideLayer(ZLayer.succeed(Scope.global)).mapError {
          case e: Error => e
          case t        => DecodeError(t.getMessage, t.toString)
        }

      override def postMessage(
          channelId: ChannelId,
          text: String,
          threadTs: Option[ThreadId]
      ): IO[Error, String] =
        (for {
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
          _ <- ZIO.logInfo(s"✅ Posted message with ts=$ts")
        } yield ts).provideLayer(ZLayer.succeed(Scope.global)).mapError {
          case e: Error     => e
          case t: Throwable => DecodeError(s"Request failed: ${t.getMessage}", t.toString)
        }
    }

    val layer: ZLayer[Client, Throwable, SlackApiClient] =
      ZLayer.fromZIO {
        for {
          client <- ZIO.service[Client]
          cfg <- ZIO
            .config(AppConfig.config)
            .mapError(e => new RuntimeException(s"Config error: ${e.getMessage}", e))
        } yield Service(client, cfg.slackAppToken, cfg.slackBotToken)
      }
  }

  object Stub {

    val layer: ULayer[SlackApiClient] = ZLayer.succeed {
      new SlackApiClient {
        override def requestSocketUrl: ZIO[Any, SlackApiClient.Error, SlackApiClient.Connection] =
          ZIO.logInfo("🌐 STUB: SlackApiClient.requestSocketUrl called") *>
            ZIO.succeed(SlackApiClient.Connection("wss://stub-url.com"))

        override def postMessage(
            channelId: ChannelId,
            text: String,
            threadTs: Option[ThreadId]
        ): IO[SlackApiClient.Error, String] =
          ZIO.logInfo(
            s"🌐 STUB: postMessage to ${channelId.value}${threadTs.map(t => s" thread ${t.formatted}").getOrElse("")}: $text"
          ) *>
            ZIO.succeed("1234567890.123456")
      }
    }
  }
}
