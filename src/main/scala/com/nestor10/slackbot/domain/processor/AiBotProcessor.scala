package com.nestor10.slackbot.domain.processor

import com.nestor10.slackbot.conf.AppConfig
import com.nestor10.slackbot.domain.model.conversation.{ChannelId, MessageId, MessageSource, ThreadId, ThreadMessage}
import com.nestor10.slackbot.domain.model.llm.{ChatMessage, ChatRole}
import com.nestor10.slackbot.domain.service.MessageEventBus
import com.nestor10.slackbot.domain.service.MessageEventBus.MessageEvent
import com.nestor10.slackbot.infrastructure.llm.LLMService
import com.nestor10.slackbot.infrastructure.observability.{LLMMetrics, LogContext}
import com.nestor10.slackbot.infrastructure.slack.SlackApiClient
import com.nestor10.slackbot.infrastructure.storage.MessageStore
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant

/** Per-thread worker: queue, fiber, and activity tracking */
case class ThreadWorker(
    queue: Queue[Instant],
    fiber: Fiber.Runtime[Throwable, Unit], // Runtime fiber can be interrupted
    lastActivity: Ref[Instant]
)

/** AI Bot processor with per-thread queue pattern (Actor-like).
  *
  * Implements isolated queues and workers per thread:
  *   - Hub publishes ALL events (ThreadCreated, MessageStored) - stays DUMB ✅
  *   - MessageStore publishes ALL events - stays DUMB ✅
  *   - AiBotProcessor filters and manages per-thread workers - is SMART ✅
  *
  * Pattern (Zionomicon Ch 9 + 11 + 14 + 30):
  *   1. Events arrive with Slack timestamps (slackCreatedAt) 2. canProcess() filters for relevant
  *      events (user messages only) 3. process() gets or creates a dedicated queue + worker for
  *      this thread:
  *      - Each thread has: Queue[Instant], Worker Fiber, Last Activity timestamp
  *      - Timestamp deduplication happens per-thread (isolated state)
  *      - Worker lifecycle: created on first event, killed after 1 hour idle 4. Each worker:
  *      - Takes from its thread's queue (blocking, exclusive to this thread)
  *      - Debounces 100ms
  *      - Checks if timestamp still latest (same thread only, no cross-thread collision)
  *      - Processes thread with full context from MessageStore 5. Cleanup fiber runs every 10
  *        minutes:
  *      - Finds workers idle > 1 hour
  *      - Interrupts fiber, removes from map
  *
  * Why Per-Thread Queues:
  *   - **Perfect isolation**: Thread A's worker never touches Thread B's queue ✅
  *   - **Automatic parallelism**: N active threads = N workers processing concurrently ✅
  *   - **Natural ordering**: Events for same thread processed sequentially in order ✅
  *   - **No semaphore needed**: Each thread has exclusive worker, no collision possible ✅
  *   - **Resource management**: Idle workers cleaned up after 1 hour ✅
  *
  * Example Scenario (3 concurrent threads):
  *
  * T=0: Thread A - Event(ts=1000) → create queue_A, worker_A, offer(1000) T=5: Thread B -
  * Event(ts=2000) → create queue_B, worker_B, offer(2000) T=10: Thread A - Event(ts=1100) → reuse
  * queue_A, offer(1100), slides out 1000 T=100: Worker A: take(1100), sleep(100ms), check, process
  * A ← parallel Worker B: take(2000), sleep(100ms), check, process B ← parallel T=3600s: Cleanup
  * fiber: Worker A idle 1hr → interrupt, remove from map
  *
  * Zionomicon References:
  *   - Chapter 9: Ref for shared state (threadWorkers Map)
  *   - Chapter 11: Queue.sliding for latest-wins per thread
  *   - Chapter 14: Scoped resource management (worker fibers)
  *   - Chapter 17-18: Dependency Injection
  *   - Chapter 30: Debouncing for bursty event sources
  */
class AiBotProcessor(
    llmService: LLMService,
    messageStore: MessageStore,
    slackClient: SlackApiClient,
    config: AppConfig,
    tracing: Tracing,
    llmMetrics: LLMMetrics,
    threadWorkers: Ref[Map[ThreadId, ThreadWorker]], // Per-thread queue + worker + activity
    lastProcessedTimestamp: Ref[Map[ThreadId, Instant]] // Track last timestamp per thread
) extends EventProcessor:

  import AiBotProcessor.*

  override val name: String = "AiBotProcessor"

  override def canProcess(event: MessageEvent): Boolean = event match
    case MessageEvent.ThreadCreated(_, _)       => true
    case MessageEvent.MessageStored(message, _) =>
      // Only process user messages - filter out our own messages
      message.source != MessageSource.Self
    case _ => false

  override def process(event: MessageEvent): IO[EventProcessor.Error, Unit] =
    // Extract threadId and timestamp from event
    val (threadId, timestampOpt) = event match
      case MessageEvent.ThreadCreated(thread, _) =>
        (thread.id, thread.rootMessage.slackCreatedAt)
      case MessageEvent.MessageStored(message, _) =>
        (message.threadId, message.slackCreatedAt)
      case _ =>
        return ZIO.unit // Should never happen due to canProcess filter

    // Skip events without timestamp (can't compare)
    (timestampOpt match
      case None =>
        ZIO.logWarning("Skipping event (no Slack timestamp)") @@
          LogContext.aiBot @@
          LogContext.threadId(threadId)

      case Some(timestamp) =>
        // Check if this event is newer than what we've already processed for THIS thread
        lastProcessedTimestamp.get.flatMap { processedMap =>
          processedMap.get(threadId) match
            case Some(lastProcessed) if timestamp.toEpochMilli <= lastProcessed.toEpochMilli =>
              // This event is older or equal to what we already processed - skip it
              ZIO.logDebug("Skipping stale event") @@
                LogContext.aiBot @@
                LogContext.threadId(threadId) @@
                ZIOAspect.annotated("event_ts", timestamp.toEpochMilli.toString) @@
                ZIOAspect.annotated("processed_ts", lastProcessed.toEpochMilli.toString)

            case _ =>
              // New event - get or create worker for this thread, then enqueue
              for {
                _ <- lastProcessedTimestamp.update(_ + (threadId -> timestamp))
                worker <- getOrCreateWorker(threadId)
                _ <- worker.queue.offer(timestamp)
                _ <- worker.lastActivity.set(Instant.now) // Update activity timestamp
                _ <- ZIO.logDebug("Enqueued work") @@
                  LogContext.aiBot @@
                  LogContext.threadId(threadId) @@
                  ZIOAspect.annotated("timestamp", timestamp.toEpochMilli.toString)
              } yield ()
        }
    ).mapError(e => EventProcessor.Error.ProcessingFailed(e, name)).as(())

  /** Get existing worker for thread, or create new one if doesn't exist */
  private def getOrCreateWorker(threadId: ThreadId): Task[ThreadWorker] =
    threadWorkers.get.flatMap { workers =>
      workers.get(threadId) match
        case Some(existing) =>
          ZIO.succeed(existing)

        case None =>
          for {
            // Create new queue (sliding, capacity 1 - latest wins for this thread)
            queue <- Queue.sliding[Instant](1)

            // Track last activity for cleanup
            lastActivity <- Ref.make(Instant.now)

            // Fork dedicated worker for this thread
            fiber <- consumeWorkForThread(threadId, queue).forkDaemon

            // Create worker object
            worker = ThreadWorker(queue, fiber, lastActivity)

            // Add to map
            _ <- threadWorkers.update(_ + (threadId -> worker))

            // Update active workers gauge (Phase 13)
            workersCount <- threadWorkers.get.map(_.size)
            _ <- llmMetrics.setActiveWorkers(workersCount)

            _ <- ZIO.logInfo("Created new worker") @@
              LogContext.aiBot @@
              LogContext.threadId(threadId)
          } yield worker
    }

  /** Recursive monitoring loop: check if still latest every 10ms while LLM runs. This recursive
    * pattern is the cleanest approach because both fiber.poll and verifyStillLatest are effectful
    * operations (not pure Boolean checks).
    *
    * Returns true if should post response (LLM completed and still latest), false if superseded
    * (LLM interrupted).
    */
  private def monitorLoop(
      llmFiber: Fiber.Runtime[Throwable, String],
      threadId: ThreadId,
      heldTimestamp: Instant
  ): Task[Boolean] =
    llmFiber.poll.flatMap {
      case Some(_) =>
        // LLM finished - return true to signal "should post"
        ZIO.succeed(true)

      case None =>
        // LLM still running - check if we're still latest
        verifyStillLatest(threadId, heldTimestamp).flatMap {
          case true =>
            // Still latest - sleep and check again
            ZIO.sleep(10.millis) *> monitorLoop(llmFiber, threadId, heldTimestamp)

          case false =>
            // Superseded - interrupt LLM and return false
            llmFiber.interrupt *>
              llmMetrics.recordInterruption("superseded_by_newer_message") *>
              tracing.addEvent("llm_interrupted") *>
              tracing.setAttribute("llm.interrupted_reason", "superseded_by_newer_message") *>
              ZIO.logInfo("Interrupted LLM - superseded during generation") @@
              LogContext.aiBot @@
              LogContext.threadId(threadId) @@
              LogContext.reason("superseded_by_newer_message") *>
              ZIO.succeed(false) // Signal "do not post"
        }
    }

  /** Consumer fiber for a specific thread - processes its queue exclusively. Uses continuous
    * monitoring pattern: starts LLM call immediately, then continuously checks if still latest
    * while LLM is running. Interrupts LLM if superseded.
    */
  private def consumeWorkForThread(threadId: ThreadId, queue: Queue[Instant]): Task[Unit] =
    queue.take.flatMap { timestamp =>
      (for {
        _ <- tracing.setAttribute("thread.id", threadId.formatted)
        _ <- tracing.setAttribute("timestamp", timestamp.toEpochMilli.toString)
        _ <- tracing.addEvent("work_dequeued")

        _ <- ZIO.logInfo("Worker dequeued work") @@
          LogContext.aiBot @@
          LogContext.threadId(threadId) @@
          ZIOAspect.annotated("timestamp", timestamp.toEpochMilli.toString)

        // Start LLM call immediately (no debounce delay!)
        llmFiber <- generateResponse(threadId).fork

        // Continuous monitor: check if still latest every 10ms while LLM runs
        // Returns true if should post, false if interrupted
        shouldPost <- monitorLoop(llmFiber, threadId, timestamp).catchAll { error =>
          // If monitor fails, interrupt LLM and don't post
          llmFiber.interrupt *>
            llmMetrics.recordInterruption("monitor_error") *>
            ZIO.logInfo("Monitor failed") @@
            LogContext.aiBot @@
            LogContext.threadId(threadId) @@
            LogContext.errorType(error.getClass.getSimpleName) *>
            ZIO.succeed(false)
        }

        // Only post if monitor says we should (LLM completed and still latest)
        _ <-
          if (shouldPost) {
            llmFiber.join
              .flatMap { response =>
                postResponse(threadId, response)
              }
              .catchAll { error =>
                ZIO.logError("Failed to post response") @@
                  LogContext.aiBot @@
                  LogContext.threadId(threadId) @@
                  LogContext.errorType(error.getClass.getSimpleName)
              }
          } else {
            // Already interrupted and logged by monitor
            ZIO.unit
          }

        _ <- tracing.addEvent("work_completed")
      } yield ()) @@ tracing.aspects.root(
        s"aibot.process_thread"
      ) // ROOT SPAN - children will nest under this
    }.forever

  /** Verify if we still hold the latest timestamp for this thread */
  private def verifyStillLatest(threadId: ThreadId, heldTimestamp: Instant): Task[Boolean] =
    lastProcessedTimestamp.get.map { timestampMap =>
      timestampMap.get(threadId) match
        case Some(latest) => latest.toEpochMilli <= heldTimestamp.toEpochMilli
        case None         => true // No newer timestamp, we're latest
    }

  /** Generate LLM response without posting (for continuous monitoring pattern) */
  private def generateResponse(threadId: ThreadId): Task[String] =
    (for {
      startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

      _ <- tracing.setAttribute("thread.id", threadId.formatted)
      _ <- tracing.setAttribute("llm.model", config.llm.model)
      _ <- tracing.addEvent("llm_request_start")

      _ <- ZIO.logInfo("Generating response") @@
        LogContext.aiBot @@
        LogContext.threadId(threadId) @@
        LogContext.llmModel(config.llm.model)

      // Get full thread history for context
      thread <- messageStore
        .retrieveThread(threadId)
        .mapError(e => new RuntimeException(s"Failed to retrieve thread: ${e.getMessage}"))

      // Get all messages in thread chronologically
      threadMessages <- messageStore
        .streamThread(threadId)
        .runCollect
        .mapError(e => new RuntimeException(s"Failed to get thread messages: ${e.getMessage}"))

      // Build conversation context
      systemMessage = ChatMessage.system(config.llm.systemPrompt)
      conversationMessages = threadMessages.map { msg =>
        if msg.source == MessageSource.Self then ChatMessage.assistant(msg.content)
        else ChatMessage.user(msg.content)
      }.toList
      allMessages = systemMessage :: conversationMessages

      _ <- tracing.setAttribute("llm.context_messages", allMessages.size.toString)

      _ <- ZIO.logInfo("Calling LLM") @@
        LogContext.aiBot @@
        LogContext.threadId(threadId) @@
        ZIOAspect.annotated("context_messages", conversationMessages.size.toString)

      // DIAGNOSTIC: Log conversation being sent to LLM
      _ <- ZIO.foreach(allMessages.zipWithIndex) { case (msg, idx) =>
        ZIO.logInfo("LLM context message") @@
          LogContext.aiBot @@
          LogContext.threadId(threadId) @@
          ZIOAspect.annotated("index", idx.toString) @@
          ZIOAspect.annotated("role", msg.role.toString) @@
          ZIOAspect.annotated("content_preview", msg.content.take(100))
      }

      // Call LLM (this can take several seconds - interruptible!)
      response <- llmService
        .chat(
          messages = allMessages,
          model = config.llm.model,
          temperature = config.llm.temperature,
          maxTokens = config.llm.maxTokens
        )
        .mapError(e => new RuntimeException(s"LLM error: $e"))

      endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      latency = endTime - startTime
      latencySeconds = latency.toDouble / 1000.0

      // Record metrics (Phase 13) - infer provider from baseUrl
      provider =
        if config.llm.baseUrl.contains("openai") then "openai"
        else if config.llm.baseUrl.contains("anthropic") then "anthropic"
        else if config.llm.baseUrl.contains("azure") then "azure"
        else "ollama"

      _ <- llmMetrics.recordRequest(provider, config.llm.model)
      _ <- llmMetrics.recordLatency(provider, config.llm.model, latencySeconds)

      _ <- tracing.setAttribute("llm.latency_ms", latency.toString)
      _ <- tracing.setAttribute("llm.response_length", response.length.toString)
      _ <- tracing.addEvent("llm_request_complete")

      _ <- ZIO.logInfo("Generated response") @@
        LogContext.aiBot @@
        LogContext.threadId(threadId) @@
        ZIOAspect.annotated("response_chars", response.length.toString) @@
        LogContext.latency(latency)

      // Guard against empty responses
      _ <- ZIO.when(response.trim.isEmpty) {
        ZIO.fail(new RuntimeException("LLM returned empty response"))
      }
    } yield response) @@ tracing.aspects.span("llm.generate_response")

  /** Post response to Slack (extracted for clarity) */
  private def postResponse(threadId: ThreadId, response: String): Task[Unit] =
    (for {
      _ <- tracing.setAttribute("thread.id", threadId.formatted)
      _ <- tracing.setAttribute("slack.api", "postMessage")

      thread <- messageStore
        .retrieveThread(threadId)
        .mapError(e => new RuntimeException(s"Failed to retrieve thread: ${e.getMessage}"))

      _ <- tracing.setAttribute("channel.id", thread.channelId.value)

      _ <- ZIO.scoped {
        slackClient
          .postMessage(
            channelId = thread.channelId,
            text = response,
            threadTs = Some(threadId)
          )
          .mapError(e => new RuntimeException(s"Slack API error: ${e.getMessage}"))
      }

      _ <- tracing.addEvent("slack_message_posted")
      _ <- ZIO.logInfo("Posted response") @@
        LogContext.aiBot @@
        LogContext.threadId(threadId) @@
        LogContext.channelId(thread.channelId)
    } yield ()) @@ tracing.aspects.span("slack.post_message")

  /** Cleanup fiber: removes idle workers (> 1 hour no activity) */
  private def cleanupIdleWorkers: Task[Unit] =
    (for {
      now <- Clock.instant
      workers <- threadWorkers.get

      // Find workers idle for more than 1 hour
      idleWorkers <- ZIO
        .foreach(workers.toList) { case (threadId, worker) =>
          worker.lastActivity.get.map { lastActivity =>
            val idleDuration = java.time.Duration.between(lastActivity, now)
            if idleDuration.toHours >= 1 then Some(threadId) else None
          }
        }
        .map(_.flatten)

      // Interrupt and remove idle workers
      _ <- ZIO.foreach(idleWorkers) { threadId =>
        workers.get(threadId) match
          case Some(worker) =>
            for {
              _ <- worker.fiber.interrupt
              _ <- threadWorkers.update(_ - threadId)
              _ <- lastProcessedTimestamp.update(_ - threadId)
              _ <- ZIO.logInfo("Cleaned up idle worker") @@
                LogContext.aiBot @@
                LogContext.threadId(threadId) @@
                LogContext.reason("idle_timeout")
            } yield ()
          case None =>
            ZIO.unit
      }

      // Update active workers gauge after cleanup (Phase 13)
      _ <- ZIO.when(idleWorkers.nonEmpty) {
        for {
          workersCount <- threadWorkers.get.map(_.size)
          _ <- llmMetrics.setActiveWorkers(workersCount)
          _ <- ZIO.logInfo("Cleanup complete") @@
            LogContext.aiBot @@
            ZIOAspect.annotated("workers_removed", idleWorkers.size.toString)
        } yield ()
      }

      // Sleep 10 minutes before next cleanup
      _ <- ZIO.sleep(10.minutes)
    } yield ()).forever

object AiBotProcessor:

  /** ZLayer for dependency injection with per-thread queues and workers */
  val layer: ZLayer[
    LLMService & MessageStore & SlackApiClient & AppConfig & Tracing & LLMMetrics,
    Nothing,
    AiBotProcessor
  ] =
    ZLayer.scoped {
      for {
        llm <- ZIO.service[LLMService]
        store <- ZIO.service[MessageStore]
        slack <- ZIO.service[SlackApiClient]
        config <- ZIO.service[AppConfig]
        tracing <- ZIO.service[Tracing]
        metrics <- ZIO.service[LLMMetrics]

        // Map of thread workers (queue + fiber + last activity per thread)
        workersMap <- Ref.make(Map.empty[ThreadId, ThreadWorker])

        // Track last processed timestamp per thread (for deduplication)
        timestampMap <- Ref.make(Map.empty[ThreadId, Instant])

        // Create processor
        processor = AiBotProcessor(
          llm,
          store,
          slack,
          config,
          tracing,
          metrics,
          workersMap,
          timestampMap
        )

        // Fork cleanup fiber in background (will be interrupted when scope closes)
        // Runs every 10 minutes to remove workers idle > 1 hour
        _ <- processor.cleanupIdleWorkers.forkScoped

        _ <- ZIO.logInfo(
          "Processor initialized with per-thread queues (workers auto-cleanup after 1hr idle)"
        ) @@
          LogContext.aiBot
      } yield processor
    }
