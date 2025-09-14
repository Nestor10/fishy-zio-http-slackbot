package slacksocket.demo.domain

import zio.json.*

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

// Inner event types
@jsonDiscriminator("type")
sealed trait SlackEvent

object SlackEvent:
  implicit val decoder: JsonDecoder[SlackEvent] = DeriveJsonDecoder.gen[SlackEvent]
  implicit val encoder: JsonEncoder[SlackEvent] = DeriveJsonEncoder.gen[SlackEvent]

// App and Bot Events
@jsonHint("app_mention")
case class AppMention(
    user: String,
    text: String,
    ts: String,
    channel: String,
    event_ts: String
) extends SlackEvent

@jsonHint("app_home_opened")
case class AppHomeOpened(
    user: String,
    channel: String,
    event_ts: String,
    tab: String,
    view: Option[String] // View object can be complex, modeled as String for brevity
) extends SlackEvent

@jsonHint("app_uninstalled")
case class AppUninstalled(event_ts: String) extends SlackEvent

// Channel and Group Events
@jsonHint("channel_created")
case class ChannelCreated(
    channel: ChannelCreated.ChannelInfo
) extends SlackEvent

object ChannelCreated:
  case class ChannelInfo(id: String, name: String, created: Long, creator: String)

  object ChannelInfo:
    implicit val decoder: JsonDecoder[ChannelInfo] = DeriveJsonDecoder.gen[ChannelInfo]
    implicit val encoder: JsonEncoder[ChannelInfo] = DeriveJsonEncoder.gen[ChannelInfo]

  implicit val decoder: JsonDecoder[ChannelCreated] = DeriveJsonDecoder.gen[ChannelCreated]
  implicit val encoder: JsonEncoder[ChannelCreated] = DeriveJsonEncoder.gen[ChannelCreated]

@jsonHint("channel_archive")
case class ChannelArchive(channel: String, user: String) extends SlackEvent

@jsonHint("channel_deleted")
case class ChannelDeleted(channel: String) extends SlackEvent

@jsonHint("channel_rename")
case class ChannelRename(channel: ChannelRename.ChannelInfo) extends SlackEvent

object ChannelRename:
  case class ChannelInfo(id: String, name: String, created: Long)

  object ChannelInfo:
    implicit val decoder: JsonDecoder[ChannelInfo] = DeriveJsonDecoder.gen[ChannelInfo]
    implicit val encoder: JsonEncoder[ChannelInfo] = DeriveJsonEncoder.gen[ChannelInfo]

  implicit val decoder: JsonDecoder[ChannelRename] = DeriveJsonDecoder.gen[ChannelRename]
  implicit val encoder: JsonEncoder[ChannelRename] = DeriveJsonEncoder.gen[ChannelRename]

// Message Events
@jsonHint("message.channels")
case class MessageChannels(
    channel: String,
    user: String,
    text: String,
    ts: String,
    event_ts: String,
    channel_type: String
) extends SlackEvent

@jsonHint("message.groups")
case class MessageGroups(
    channel: String,
    user: String,
    text: String,
    ts: String,
    event_ts: String,
    channel_type: String
) extends SlackEvent

@jsonHint("message.im")
case class MessageIm(
    channel: String,
    user: String,
    text: String,
    ts: String,
    event_ts: String,
    channel_type: String
) extends SlackEvent

@jsonHint("message.mpim")
case class MessageMpim(
    channel: String,
    user: String,
    text: String,
    ts: String,
    event_ts: String,
    channel_type: String
) extends SlackEvent

// Reaction Events
@jsonHint("reaction_added")
case class ReactionAdded(
    user: String,
    reaction: String,
    item_user: String,
    item: ReactionAdded.ReactionItem,
    event_ts: String
) extends SlackEvent

object ReactionAdded:

  @jsonDiscriminator("type")
  sealed trait ReactionItem

  object ReactionItem:
    implicit val decoder: JsonDecoder[ReactionItem] = DeriveJsonDecoder.gen[ReactionItem]
    implicit val encoder: JsonEncoder[ReactionItem] = DeriveJsonEncoder.gen[ReactionItem]

  @jsonHint("message")
  case class MessageItem(channel: String, ts: String) extends ReactionItem

  object MessageItem:
    implicit val decoder: JsonDecoder[MessageItem] = DeriveJsonDecoder.gen[MessageItem]
    implicit val encoder: JsonEncoder[MessageItem] = DeriveJsonEncoder.gen[MessageItem]

  @jsonHint("file")
  case class FileItem(file: String) extends ReactionItem

  object FileItem:
    implicit val decoder: JsonDecoder[FileItem] = DeriveJsonDecoder.gen[FileItem]
    implicit val encoder: JsonEncoder[FileItem] = DeriveJsonEncoder.gen[FileItem]

  @jsonHint("file_comment")
  case class FileCommentItem(file: String, file_comment: String) extends ReactionItem

  object FileCommentItem:
    implicit val decoder: JsonDecoder[FileCommentItem] = DeriveJsonDecoder.gen[FileCommentItem]
    implicit val encoder: JsonEncoder[FileCommentItem] = DeriveJsonEncoder.gen[FileCommentItem]

  implicit val decoder: JsonDecoder[ReactionAdded] = DeriveJsonDecoder.gen[ReactionAdded]
  implicit val encoder: JsonEncoder[ReactionAdded] = DeriveJsonEncoder.gen[ReactionAdded]

@jsonHint("reaction_removed")
case class ReactionRemoved(
    user: String,
    reaction: String,
    item_user: String,
    item: ReactionAdded.ReactionItem, // Can reuse the item model from ReactionAdded
    event_ts: String
) extends SlackEvent

// User and Team Events
@jsonHint("team_join")
case class TeamJoin(user: String) extends SlackEvent // User object is complex, simplified here

@jsonHint("user_change")
case class UserChange(user: String) extends SlackEvent // User object is complex, simplified here

// Fallback for Unknown Events
@jsonNoExtraFields // Ensures we don't accidentally match a known event
case class UnknownEvent(
    `type`: String,
    @jsonField("event_ts") eventTs: String
) extends SlackEvent

object UnknownEvent:
  implicit val decoder: JsonDecoder[UnknownEvent] = DeriveJsonDecoder.gen[UnknownEvent]

// Acknowledgment response
case class AckResponse(
    envelope_id: String,
    payload: Option[String] = None // Optional payload if acceptsResponsePayload is true
)

object AckResponse:
  implicit val encoder: JsonEncoder[AckResponse] = DeriveJsonEncoder.gen[AckResponse]
