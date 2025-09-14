package slacksocket.demo.service

import zio._
import zio.http._
import zio.json._
import slacksocket.demo.conf.AppConfig

/** Slack API client with just enough to open a Socket Mode connection via apps.connections.open */
trait SlackApiClient {

  /** Request a fresh WebSocket URL for Slack Socket Mode (does NOT open the websocket). */
  def requestSocketUrl: IO[SlackApiClient.Error, SlackApiClient.Connection]
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

  object Live {

    final case class Service(client: Client, token: String) extends SlackApiClient {

      override def requestSocketUrl: IO[Error, Connection] =
        (for {
          tok <- ZIO.succeed(token)
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
    }

    val layer: ZLayer[Client, Throwable, SlackApiClient] =
      ZLayer.fromZIO {
        for {
          client <- ZIO.service[Client]
          cfg <- ZIO
            .config(AppConfig.config)
            .mapError(e => new RuntimeException(s"Config error: ${e.getMessage}", e))
        } yield Service(client, cfg.slackAppToken)
      }
  }

  object Stub {

    val layer: ULayer[SlackApiClient] = ZLayer.succeed {
      new SlackApiClient {
        override def requestSocketUrl: ZIO[Any, SlackApiClient.Error, SlackApiClient.Connection] =
          ZIO.logInfo("🌐 STUB: SlackApiClient.requestSocketUrl called") *>
            ZIO.succeed(SlackApiClient.Connection("wss://stub-url.com"))
      }
    }
  }
}
