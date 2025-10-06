package com.nestor10.slackbot.domain.slack

import zio.json.*

// Business-relevant messages that need processing
type BusinessMessage = EventsApiMessage | InteractiveMessage | SlashCommand

// Root message type for all Slack Socket Mode messages
@jsonDiscriminator("type")
sealed trait SlackSocketMessage

object SlackSocketMessage:
  implicit val decoder: JsonDecoder[SlackSocketMessage] = DeriveJsonDecoder.gen[SlackSocketMessage]

// Connection lifecycle messages
@jsonHint("hello")
case class Hello(
    connection_info: Hello.ConnectionInfo,
    num_connections: Int,
    debug_info: Hello.DebugInfo
) extends SlackSocketMessage

object Hello:
  case class ConnectionInfo(app_id: String)

  object ConnectionInfo:
    implicit val decoder: JsonDecoder[ConnectionInfo] = DeriveJsonDecoder.gen[ConnectionInfo]

  case class DebugInfo(
      host: String,
      started: Option[String],
      build_number: Option[Int],
      approximate_connection_time: Int
  )

  object DebugInfo:
    implicit val decoder: JsonDecoder[DebugInfo] = DeriveJsonDecoder.gen[DebugInfo]

  implicit val decoder: JsonDecoder[Hello] = DeriveJsonDecoder.gen[Hello]

@jsonHint("disconnect")
case class Disconnect(
    reason: String,
    debug_info: Disconnect.DebugInfo
) extends SlackSocketMessage

object Disconnect:
  case class DebugInfo(host: String)

  object DebugInfo:
    implicit val decoder: JsonDecoder[DebugInfo] = DeriveJsonDecoder.gen[DebugInfo]

  implicit val decoder: JsonDecoder[Disconnect] = DeriveJsonDecoder.gen[Disconnect]

// Envelope messages
@jsonHint("events_api")
case class EventsApiMessage(
    envelope_id: String,
    payload: EventPayload,
    accepts_response_payload: Boolean
) extends SlackSocketMessage

object EventsApiMessage:
  implicit val decoder: JsonDecoder[EventsApiMessage] = DeriveJsonDecoder.gen[EventsApiMessage]

@jsonHint("interactive")
case class InteractiveMessage(
    envelope_id: String
    // In a complete implementation, this would have a `payload` field
    // with its own sealed trait hierarchy for interactive components.
) extends SlackSocketMessage

object InteractiveMessage:
  implicit val decoder: JsonDecoder[InteractiveMessage] = DeriveJsonDecoder.gen[InteractiveMessage]

@jsonHint("slash_commands")
case class SlashCommand(
    envelope_id: String
    // This would also have a `payload` field for command details.
) extends SlackSocketMessage

object SlashCommand:
  implicit val decoder: JsonDecoder[SlashCommand] = DeriveJsonDecoder.gen[SlashCommand]

// Event payload wrapper
case class EventPayload(
    token: String,
    team_id: String,
    api_app_id: String,
    event: SlackEvent, // This is our second, nested ADT
    `type`: String, // "event_callback"
    event_id: String,
    event_time: Long,
    authorizations: List[EventPayload.Authorization],
    is_ext_shared_channel: Option[Boolean],
    event_context: String
)

object EventPayload:

  case class Authorization(
      enterprise_id: Option[String],
      team_id: String,
      user_id: String,
      is_bot: Boolean,
      is_enterprise_install: Option[Boolean]
  )

  object Authorization:
    implicit val decoder: JsonDecoder[Authorization] = DeriveJsonDecoder.gen[Authorization]
    implicit val encoder: JsonEncoder[Authorization] = DeriveJsonEncoder.gen[Authorization]

  implicit val decoder: JsonDecoder[EventPayload] = DeriveJsonDecoder.gen[EventPayload]
  implicit val encoder: JsonEncoder[EventPayload] = DeriveJsonEncoder.gen[EventPayload]
