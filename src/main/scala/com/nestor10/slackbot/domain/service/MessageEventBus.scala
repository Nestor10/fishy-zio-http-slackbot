package com.nestor10.slackbot.domain.service

import com.nestor10.slackbot.domain.model.conversation.{MessageId, Thread, ThreadMessage}
import zio.*
import zio.stream.*

import java.time.Instant

/** Event-driven notification system for message and thread changes.
  *
  * Uses Hub (Chapter 12: Broadcasting) to enable multiple consumers to react to storage events.
  * Follows Chapter 15 (Scope) for safe subscription management.
  */
trait MessageEventBus:

  /** Publish an event to all subscribers.
    *
    * @param event
    *   The event to publish
    */
  def publish(event: MessageEventBus.MessageEvent): UIO[Unit]

  /** Subscribe to all events. Returns a scoped Dequeue.
    *
    * IMPORTANT: Must be used within ZIO.scoped to ensure proper cleanup. The Dequeue is
    * automatically closed when the scope exits.
    *
    * @return
    *   A Dequeue of events that is valid for the scope duration
    */
  def subscribe: ZIO[Scope, Nothing, Dequeue[MessageEventBus.MessageEvent]]

  /** Subscribe to events as a stream (convenience wrapper).
    *
    * @return
    *   A stream of all events
    */
  def subscribeStream: ZStream[Any, Nothing, MessageEventBus.MessageEvent]

  /** Subscribe to filtered events as a stream.
    *
    * @param filter
    *   Predicate to filter events
    * @return
    *   A stream of filtered events
    */
  def subscribeFiltered(
      filter: MessageEventBus.MessageEvent => Boolean
  ): ZStream[Any, Nothing, MessageEventBus.MessageEvent]

object MessageEventBus:

  /** Domain events for message and thread changes. */
  sealed trait MessageEvent

  // Export for easier importing
  export MessageEvent.*

  object MessageEvent:

    /** A new message was stored. */
    case class MessageStored(
        message: ThreadMessage,
        timestamp: Instant
    ) extends MessageEvent

    /** An existing message was updated (edit, reaction, etc.). */
    case class MessageUpdated(
        messageId: MessageId,
        oldMessage: ThreadMessage,
        newMessage: ThreadMessage,
        timestamp: Instant
    ) extends MessageEvent

    /** A thread was updated (new message added, state changed). */
    case class ThreadUpdated(
        thread: Thread,
        timestamp: Instant
    ) extends MessageEvent

    /** A new thread was created. */
    case class ThreadCreated(
        thread: Thread,
        timestamp: Instant
    ) extends MessageEvent

  /** Live implementation using Hub for broadcasting. Follows Chapter 12: Hub - Broadcasting. */
  case class Live(hub: Hub[MessageEvent]) extends MessageEventBus:

    def publish(event: MessageEvent): UIO[Unit] =
      hub.publish(event).unit

    def subscribe: ZIO[Scope, Nothing, Dequeue[MessageEvent]] =
      hub.subscribe

    def subscribeStream: ZStream[Any, Nothing, MessageEvent] =
      ZStream.fromHub(hub)

    def subscribeFiltered(
        filter: MessageEvent => Boolean
    ): ZStream[Any, Nothing, MessageEvent] =
      ZStream.fromHub(hub).filter(filter)

  end Live

  object Live:

    /** ZLayer for MessageEventBus.Live. Uses bounded Hub with backpressure.
      *
      * Follows Chapter 12 (Hub) and Chapter 15 (Scope) patterns. Hub size of 256 provides good
      * balance between memory and throughput.
      */
    val layer: ZLayer[Any, Nothing, MessageEventBus] =
      ZLayer.scoped {
        for {
          hub <- Hub.bounded[MessageEvent](256)
          _ <- ZIO.logInfo("MessageEventBus initialized with Hub capacity 256")
        } yield Live(hub)
      }

  // Accessor methods following Chapter 19: Contextual Data Types

  /** Publish an event to all subscribers.
    *
    * @param event
    *   The event to publish
    */
  def publish(event: MessageEvent): ZIO[MessageEventBus, Nothing, Unit] =
    ZIO.serviceWithZIO[MessageEventBus](_.publish(event))

  /** Subscribe to all events. Must be used within ZIO.scoped.
    *
    * @return
    *   A scoped Dequeue of events
    */
  def subscribe: ZIO[MessageEventBus & Scope, Nothing, Dequeue[MessageEvent]] =
    ZIO.serviceWithZIO[MessageEventBus](_.subscribe)

  /** Subscribe to events as a stream.
    *
    * @return
    *   A stream of all events
    */
  def subscribeStream: ZStream[MessageEventBus, Nothing, MessageEvent] =
    ZStream.serviceWithStream[MessageEventBus](_.subscribeStream)

  /** Subscribe to filtered events as a stream.
    *
    * @param filter
    *   Predicate to filter events
    * @return
    *   A stream of filtered events
    */
  def subscribeFiltered(
      filter: MessageEvent => Boolean
  ): ZStream[MessageEventBus, Nothing, MessageEvent] =
    ZStream.serviceWithStream[MessageEventBus](_.subscribeFiltered(filter))
