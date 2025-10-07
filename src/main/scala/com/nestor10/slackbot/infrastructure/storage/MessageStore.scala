package com.nestor10.slackbot.infrastructure.storage

import com.nestor10.slackbot.domain.model.conversation.{MessageId, Thread, ThreadId, ThreadMessage}
import com.nestor10.slackbot.domain.model.storage.MessageQuery
import com.nestor10.slackbot.domain.service.MessageEventBus
import com.nestor10.slackbot.infrastructure.observability.LogContext
import zio.*
import zio.stream.*

import java.time.Instant

/** Service for persistent storage and retrieval of Thread and ThreadMessage domain objects.
  *
  * Follows Chapter 9 (Ref - Shared State) for in-memory implementation and Chapters 27-28 (ZStream)
  * for query methods that may return many results.
  */
trait MessageStore:
  /** Store a ThreadMessage. Returns the MessageId on success.
    *
    * @param message
    *   The ThreadMessage to store
    * @return
    *   The MessageId of the stored message
    */
  def store(message: ThreadMessage): IO[MessageStore.Error, MessageId]

  /** Store a complete Thread with all its messages.
    *
    * @param thread
    *   The Thread to store
    * @return
    *   The ThreadId of the stored thread
    */
  def storeThread(thread: Thread): IO[MessageStore.Error, ThreadId]

  /** Retrieve a single ThreadMessage by its ID.
    *
    * @param messageId
    *   The ID of the message to retrieve
    * @return
    *   Some(message) if found, None otherwise
    */
  def retrieve(messageId: MessageId): IO[MessageStore.Error, Option[ThreadMessage]]

  /** Retrieve a complete Thread by its ID.
    *
    * @param threadId
    *   The ID of the thread to retrieve
    * @return
    *   The Thread if found
    */
  def retrieveThread(threadId: ThreadId): IO[MessageStore.Error, Thread]

  /** Retrieve all messages within a time range as a stream.
    *
    * @param start
    *   Start of the time range (inclusive)
    * @param end
    *   End of the time range (inclusive)
    * @return
    *   A stream of ThreadMessages in the time range
    */
  def retrieveByTimeRange(
      start: Instant,
      end: Instant
  ): ZStream[Any, MessageStore.Error, ThreadMessage]

  /** Stream all messages in a thread.
    *
    * @param threadId
    *   The ID of the thread
    * @return
    *   A stream of ThreadMessages in the thread
    */
  def streamThread(threadId: ThreadId): ZStream[Any, MessageStore.Error, ThreadMessage]

  /** Search for messages matching the query criteria.
    *
    * @param query
    *   The search query with filters
    * @return
    *   A stream of matching ThreadMessages
    */
  def searchMessages(query: MessageQuery): ZStream[Any, MessageStore.Error, ThreadMessage]

  /** Get storage statistics (total messages and threads).
    *
    * @return
    *   Tuple of (message count, thread count)
    */
  def stats(): UIO[(Int, Int)]

object MessageStore:

  /** Error types for MessageStore operations. Follows Chapter 3: Error Model. */
  sealed trait Error extends Throwable

  object Error:

    /** Storage operation failed due to an underlying error. */
    case class StorageError(cause: Throwable) extends Error:
      override def getMessage: String = s"Storage error: ${cause.getMessage}"

    /** Requested message or thread was not found. */
    case class NotFound(id: String) extends Error:
      override def getMessage: String = s"Not found: $id"

    /** Invalid input to a storage operation. */
    case class ValidationError(message: String) extends Error:
      override def getMessage: String = s"Validation error: $message"

  /** In-memory implementation using Ref for fiber-safe state management. Follows Chapter 9: Ref -
    * Shared State.
    *
    * Phase 7a: Now publishes domain events on successful storage operations (Option 2 pattern).
    * Events are published atomically with storage to ensure they reflect actual state changes.
    */
  case class InMemory(
      messages: Ref[Map[MessageId, ThreadMessage]],
      threads: Ref[Map[ThreadId, Thread]],
      eventBus: MessageEventBus // ← NEW: Dependency for publishing domain events
  ) extends MessageStore:

    def store(message: ThreadMessage): IO[Error, MessageId] =
      for {
        _ <- messages.update(_ + (message.id -> message))
        _ <- ZIO.logDebug("Stored message") @@
          LogContext.storage @@
          LogContext.messageId(message.id)
        // Phase 7a: Publish MessageStored event (Option 2 pattern)
        _ <- eventBus.publish(MessageEventBus.MessageStored(message, Instant.now))
      } yield message.id

    def storeThread(thread: Thread): IO[Error, ThreadId] =
      for {
        // Store the thread metadata (root message is already stored separately when thread created)
        _ <- threads.update(_ + (thread.id -> thread))
        _ <- ZIO.logInfo("Stored thread") @@
          LogContext.storage @@
          LogContext.threadId(thread.id)
        // Phase 7a: Publish ThreadCreated event (Option 2 pattern)
        // Hub stays DUMB - publishes ALL events, processor filters
        _ <- eventBus.publish(MessageEventBus.ThreadCreated(thread, Instant.now))
      } yield thread.id

    def retrieve(messageId: MessageId): IO[Error, Option[ThreadMessage]] =
      messages.get.map(_.get(messageId))

    def retrieveThread(threadId: ThreadId): IO[Error, Thread] =
      for {
        threadsMap <- threads.get
        // Just return the thread metadata - messages are queried separately
        thread <- ZIO
          .fromOption(threadsMap.get(threadId))
          .orElseFail(Error.NotFound(s"Thread(${threadId.value})"))
      } yield thread

    def retrieveByTimeRange(
        start: Instant,
        end: Instant
    ): ZStream[Any, Error, ThreadMessage] =
      ZStream.unwrap {
        messages.get.map { msgs =>
          ZStream
            .fromIterable(msgs.values)
            .filter { msg =>
              !msg.createdAt.isBefore(start) &&
              !msg.createdAt.isAfter(end)
            }
        }
      }

    def streamThread(threadId: ThreadId): ZStream[Any, Error, ThreadMessage] =
      ZStream.unwrap {
        messages.get.map { msgsMap =>
          val threadMsgs = msgsMap.values
            .filter(_.threadId == threadId)
            .toList
            .sortBy(
              _.slackCreatedAt.map(_.toEpochMilli).getOrElse(0L)
            ) // Chronological order by Slack timestamp
          ZStream.fromIterable(threadMsgs)
        }
      }

    def searchMessages(query: MessageQuery): ZStream[Any, Error, ThreadMessage] =
      ZStream.unwrap {
        messages.get.map { msgs =>
          ZStream
            .fromIterable(msgs.values)
            .filter { msg =>
              // Text search
              val matchesText =
                query.searchTerm.isEmpty || msg.content.contains(query.searchTerm)

              // Thread filter
              val matchesThread =
                query.threadId.isEmpty || query.threadId.contains(msg.threadId)

              // Channel filter (requires retrieving thread - simplified for now)
              val matchesChannel = query.channelId.isEmpty

              // Author filter
              val matchesAuthor = query.author.isEmpty || query.author.contains(msg.author)

              // Time range filter
              val matchesTimeRange = (query.startTime, query.endTime) match {
                case (Some(start), Some(end)) =>
                  !msg.createdAt.isBefore(start) && !msg.createdAt.isAfter(end)
                case (Some(start), None) => !msg.createdAt.isBefore(start)
                case (None, Some(end))   => !msg.createdAt.isAfter(end)
                case (None, None)        => true
              }

              // Deleted filter
              val matchesDeleted = query.includeDeleted || !msg.isDeleted

              matchesText && matchesThread && matchesChannel && matchesAuthor && matchesTimeRange && matchesDeleted
            }
        }
      }

    def stats(): UIO[(Int, Int)] =
      for {
        msgCount <- messages.get.map(_.size)
        threadCount <- threads.get.map(_.size)
      } yield (msgCount, threadCount)

  end InMemory

  object InMemory:

    /** ZLayer for InMemory implementation. Follows Chapter 17: Dependency Injection.
      *
      * Phase 7a: Now depends on MessageEventBus for publishing domain events (Option 2 pattern).
      */
    val layer: ZLayer[MessageEventBus, Nothing, MessageStore] =
      ZLayer.fromZIO {
        for {
          messages <- Ref.make(Map.empty[MessageId, ThreadMessage])
          threads <- Ref.make(Map.empty[ThreadId, Thread])
          eventBus <- ZIO.service[MessageEventBus] // ← NEW: Get MessageEventBus dependency
          _ <- ZIO.logInfo("MessageStore initialized (with event publishing)") @@
            LogContext.storage
        } yield InMemory(messages, threads, eventBus)
      }

  // Accessor methods following Chapter 19: Contextual Data Types
  // These provide ergonomic service usage without manual .service calls

  /** Store a ThreadMessage.
    *
    * @param message
    *   The message to store
    */
  def store(message: ThreadMessage): ZIO[MessageStore, Error, MessageId] =
    ZIO.serviceWithZIO[MessageStore](_.store(message))

  /** Store a complete Thread.
    *
    * @param thread
    *   The thread to store
    */
  def storeThread(thread: Thread): ZIO[MessageStore, Error, ThreadId] =
    ZIO.serviceWithZIO[MessageStore](_.storeThread(thread))

  /** Retrieve a ThreadMessage by ID.
    *
    * @param messageId
    *   The message ID
    */
  def retrieve(messageId: MessageId): ZIO[MessageStore, Error, Option[ThreadMessage]] =
    ZIO.serviceWithZIO[MessageStore](_.retrieve(messageId))

  /** Retrieve a Thread by ID.
    *
    * @param threadId
    *   The thread ID
    */
  def retrieveThread(threadId: ThreadId): ZIO[MessageStore, Error, Thread] =
    ZIO.serviceWithZIO[MessageStore](_.retrieveThread(threadId))

  /** Retrieve messages within a time range.
    *
    * @param start
    *   Start of range (inclusive)
    * @param end
    *   End of range (inclusive)
    */
  def retrieveByTimeRange(
      start: Instant,
      end: Instant
  ): ZStream[MessageStore, Error, ThreadMessage] =
    ZStream.serviceWithStream[MessageStore](_.retrieveByTimeRange(start, end))

  /** Stream all messages in a thread.
    *
    * @param threadId
    *   The thread ID
    */
  def streamThread(threadId: ThreadId): ZStream[MessageStore, Error, ThreadMessage] =
    ZStream.serviceWithStream[MessageStore](_.streamThread(threadId))

  /** Search messages matching query criteria.
    *
    * @param query
    *   The search query
    */
  def searchMessages(query: MessageQuery): ZStream[MessageStore, Error, ThreadMessage] =
    ZStream.serviceWithStream[MessageStore](_.searchMessages(query))

  /** Get storage statistics.
    *
    * @return
    *   Tuple of (message count, thread count)
    */
  def stats(): ZIO[MessageStore, Nothing, (Int, Int)] =
    ZIO.serviceWithZIO[MessageStore](_.stats())
