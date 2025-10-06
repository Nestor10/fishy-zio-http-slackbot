package com.nestor10.slackbot.domain.model.slack

import zio.json.*

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
    ts: Double, // Slack timestamp with microsecond precision
    channel: String,
    event_ts: Double, // When the event occurred
    thread_ts: Option[Double] // Present when mention occurs in a thread
) extends SlackEvent

@jsonHint("app_home_opened")
case class AppHomeOpened(
    user: String,
    channel: String,
    event_ts: Double, // When the event occurred
    tab: String,
    view: Option[String] // View object can be complex, modeled as String for brevity
) extends SlackEvent

@jsonHint("app_uninstalled")
case class AppUninstalled(event_ts: Double) extends SlackEvent

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

// Message Events - unified message type with channel_type discrimination
@jsonHint("message")
case class Message(
    channel: String,
    user: Option[String], // Can be None for bot messages
    text: Option[String], // Can be None for certain message types
    ts: Double, // Message timestamp with microsecond precision
    event_ts: Double, // When the event occurred
    channel_type: String, // "channel", "group", "im", "mpim"
    subtype: Option[String], // "bot_message", "file_share", etc.
    thread_ts: Option[Double], // For threaded messages/replies
    parent_user_id: Option[String] // For replies in threads
) extends SlackEvent

// Reaction Events
@jsonHint("reaction_added")
case class ReactionAdded(
    user: String,
    reaction: String,
    item_user: String,
    item: ReactionAdded.ReactionItem,
    event_ts: Double // When the reaction event occurred
) extends SlackEvent

object ReactionAdded:

  @jsonDiscriminator("type")
  sealed trait ReactionItem

  object ReactionItem:
    implicit val decoder: JsonDecoder[ReactionItem] = DeriveJsonDecoder.gen[ReactionItem]
    implicit val encoder: JsonEncoder[ReactionItem] = DeriveJsonEncoder.gen[ReactionItem]

  @jsonHint("message")
  case class MessageItem(channel: String, ts: Double) extends ReactionItem

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
    event_ts: Double // When the reaction removal event occurred
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
    @jsonField("event_ts") eventTs: Double // When the unknown event occurred
) extends SlackEvent

object UnknownEvent:
  implicit val decoder: JsonDecoder[UnknownEvent] = DeriveJsonDecoder.gen[UnknownEvent]
