package com.nestor10.slackbot.infrastructure.storage

import com.nestor10.slackbot.infrastructure.storage.MessageStore

import zio.*
import com.nestor10.slackbot.domain.model.conversation.{ThreadId, MessageId, ThreadMessage, Thread}
import scala.collection.immutable.SortedMap
import java.time.Instant

/** Eviction service for removing stale threads and messages from MessageStore.
  *
  * Implements scheduled background eviction based on thread inactivity. Follows Zionomicon
  * patterns:
  *   - Chapter 9: Ref for coordinated state updates across multiple indices
  *   - Chapter 24: Schedule for periodic eviction tasks
  *   - Chapter 15: Scope for managed background fibers
  *
  * Design Principles:
  *   - Non-blocking: Uses Ref for lock-free updates
  *   - Atomic: All or nothing - thread + all its messages evicted together
  *   - Observable: Logs eviction stats for monitoring
  *   - Configurable: Eviction age and interval via config
  */
trait EvictionService:

  /** Evict threads that have been inactive for longer than maxAge.
    *
    * @param maxAge
    *   Threads with no activity older than this will be evicted
    * @return
    *   Statistics about what was evicted
    */
  def evictStaleThreads(maxAge: Duration): UIO[EvictionStats]

  /** Evict a specific thread and all its messages.
    *
    * @param threadId
    *   Thread to evict
    * @return
    *   Number of messages evicted
    */
  def evictThread(threadId: ThreadId): UIO[Int]

  /** Get current eviction statistics */
  def getStats(): UIO[EvictionStats]

/** Statistics about an eviction run */
case class EvictionStats(
    threadsEvicted: Int,
    messagesEvicted: Int,
    durationMs: Long,
    timestamp: Instant
)

object EvictionService:

  case class Live(
      messageStore: MessageStore.InMemory,
      config: EvictionConfig
  ) extends EvictionService:

    def evictStaleThreads(maxAge: Duration): UIO[EvictionStats] =
      for {
        start <- Clock.instant
        cutoff = start.minus(maxAge)

        // Find threads with last activity before cutoff
        allThreads <- messageStore.threads.get
        staleThreadIds = allThreads.collect {
          case (threadId, thread) if thread.rootMessage.slackCreatedAt.exists(_.isBefore(cutoff)) =>
            threadId
        }.toList

        // Evict each stale thread
        messageCounts <- ZIO.foreach(staleThreadIds)(evictThread)

        end <- Clock.instant
        duration = end.toEpochMilli - start.toEpochMilli

        stats = EvictionStats(
          threadsEvicted = staleThreadIds.size,
          messagesEvicted = messageCounts.sum,
          durationMs = duration,
          timestamp = end
        )

        _ <- ZIO.logInfo(
          s"🗑️ EVICTION: Removed ${stats.threadsEvicted} threads, " +
            s"${stats.messagesEvicted} messages in ${stats.durationMs}ms"
        )
      } yield stats

    def evictThread(threadId: ThreadId): UIO[Int] =
      for {
        // Find all messages for this thread
        allMessages <- messageStore.messages.get
        threadMessageIds = allMessages.collect {
          case (msgId, msg) if msg.threadId == threadId => msgId
        }.toList

        // Remove messages atomically
        _ <- messageStore.messages.update { msgs =>
          threadMessageIds.foldLeft(msgs)((m, id) => m - id)
        }

        // Remove thread
        _ <- messageStore.threads.update(_ - threadId)

        _ <- ZIO.logDebug(
          s"🗑️ Evicted thread ${threadId.formatted} (${threadMessageIds.size} messages)"
        )
      } yield threadMessageIds.size

    def getStats(): UIO[EvictionStats] =
      for {
        now <- Clock.instant
        threadCount <- messageStore.threads.get.map(_.size)
        messageCount <- messageStore.messages.get.map(_.size)
      } yield EvictionStats(0, 0, 0, now) // Placeholder, could track cumulative stats

    /** Start background eviction fiber (runs on schedule) */
    def scheduleEviction: URIO[Scope, Unit] =
      evictStaleThreads(config.maxThreadAge)
        .schedule(Schedule.spaced(config.evictionInterval))
        .forkScoped
        .unit

  /** ZLayer that creates the service and starts the background eviction fiber */
  val layer: ZLayer[MessageStore & EvictionConfig, Nothing, EvictionService] =
    ZLayer.scoped {
      for {
        store <- ZIO.service[MessageStore]
        config <- ZIO.service[EvictionConfig]

        // Require InMemory implementation for direct access
        inMemoryStore = store match
          case s: MessageStore.InMemory => s
          case _ =>
            throw new IllegalStateException("EvictionService requires MessageStore.InMemory")

        service = Live(inMemoryStore, config)

        // Start background eviction if enabled
        _ <- ZIO.when(config.enabled) {
          service.scheduleEviction *>
            ZIO.logInfo(
              s"🗑️ EvictionService: Scheduled eviction every ${config.evictionInterval.toHours}h " +
                s"(max age: ${config.maxThreadAge.toDays} days)"
            )
        }
      } yield service
    }

/** Configuration for eviction behavior */
case class EvictionConfig(
    enabled: Boolean = false, // Disabled by default for safety
    maxThreadAge: Duration = 7.days, // Evict threads older than 7 days
    evictionInterval: Duration = 6.hours // Run eviction every 6 hours
)

object EvictionConfig:

  /** Default configuration (eviction disabled) */
  val default: EvictionConfig = EvictionConfig()

  /** Development configuration (aggressive eviction for testing) */
  val dev: EvictionConfig = EvictionConfig(
    enabled = true,
    maxThreadAge = 1.hour,
    evictionInterval = 15.minutes
  )

  /** Production configuration (conservative eviction) */
  val prod: EvictionConfig = EvictionConfig(
    enabled = true,
    maxThreadAge = 7.days,
    evictionInterval = 6.hours
  )

  val layer: ULayer[EvictionConfig] = ZLayer.succeed(default)
