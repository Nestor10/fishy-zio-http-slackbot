package com.nestor10.slackbot.domain.model.storage

import zio.json.*
import com.nestor10.slackbot.domain.model.conversation.{ThreadId, UserId, ChannelId}
import java.time.Instant

/** Query parameters for searching messages in the MessageStore. */
case class MessageQuery(
    searchTerm: String,
    threadId: Option[ThreadId] = None,
    channelId: Option[ChannelId] = None,
    author: Option[UserId] = None,
    startTime: Option[Instant] = None,
    endTime: Option[Instant] = None,
    includeDeleted: Boolean = false
) derives JsonCodec

object MessageQuery:

  /** Simple text search across all messages. */
  def byText(searchTerm: String): MessageQuery =
    MessageQuery(searchTerm = searchTerm)

  /** Search within a specific thread. */
  def byThread(threadId: ThreadId, searchTerm: String = ""): MessageQuery =
    MessageQuery(searchTerm = searchTerm, threadId = Some(threadId))

  /** Search within a specific channel. */
  def byChannel(channelId: ChannelId, searchTerm: String = ""): MessageQuery =
    MessageQuery(searchTerm = searchTerm, channelId = Some(channelId))

  /** Search by author. */
  def byAuthor(author: UserId, searchTerm: String = ""): MessageQuery =
    MessageQuery(searchTerm = searchTerm, author = Some(author))

  /** Search within a time range. */
  def byTimeRange(start: Instant, end: Instant, searchTerm: String = ""): MessageQuery =
    MessageQuery(searchTerm = searchTerm, startTime = Some(start), endTime = Some(end))
