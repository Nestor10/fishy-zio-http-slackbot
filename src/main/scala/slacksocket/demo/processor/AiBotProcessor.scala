package slacksocket.demo.processor

import zio.*
import slacksocket.demo.service.{MessageEventBus, MessageStore, LLMService, SlackApiClient}
import slacksocket.demo.service.MessageEventBus.MessageEvent
import slacksocket.demo.domain.llm.{ChatMessage, ChatRole}
import slacksocket.demo.domain.conversation.{ThreadMessage, ChannelId, MessageId}
import slacksocket.demo.conf.AppConfig

/** AI Bot processor with concurrent message handling.
  *
  * Implements a "latest-wins" pattern with visual feedback via reactions:
  *   - 🤖 (robot_face): Message is currently being processed (immutable, runs to completion)
  *   - 🕐 (clock1): Message is waiting to be processed (mutable, can be replaced)
  *   - ⬇️ (arrow_down): Message was skipped (replaced while waiting)
  *   - ✅ (white_check_mark): Message was answered
  *
  * State Machine:
  *   - PROCESSING: Once started, never interrupted. New messages don't affect it.
  *   - WAITING: Only one message can wait. New message replaces it (old becomes SKIPPED).
  *   - SKIPPED: Terminal state for messages that were waiting but got replaced.
  *
  * Zionomicon References:
  *   - Chapter 9: Ref for shared state (ProcessingState)
  *   - Chapter 11: Queue work distribution (implicit via Ref.modify pattern)
  *   - Chapter 17-18: Dependency Injection (Multi-service dependencies)
  */
class AiBotProcessor(
    llmService: LLMService,
    messageStore: MessageStore,
    slackClient: SlackApiClient,
    config: AppConfig,
    processingState: Ref[AiBotProcessor.ProcessingState]
) extends MessageProcessor:

  import AiBotProcessor.*

  override val name: String = "AiBotProcessor"

  override def canProcess(event: MessageEvent): Boolean = event match
    case MessageEvent.ThreadCreated(_, _)       => true
    case MessageEvent.MessageStored(message, _) =>
      // Only process user messages - filter out our own messages
      message.source != slacksocket.demo.domain.conversation.MessageSource.Self
    case _ => false

  override def process(event: MessageEvent): IO[MessageProcessor.Error, Unit] =
    event match
      case MessageEvent.ThreadCreated(thread, timestamp) =>
        // Initial @mention - acknowledge and queue for processing
        handleMessage(thread.rootMessage, isInitialMention = true)
          .catchAll { error =>
            ZIO.logError(s"🤖 AI_BOT: Failed to handle thread creation: ${error.getMessage}") *>
              ZIO.unit
          }

      case MessageEvent.MessageStored(message, timestamp) =>
        // User replied in thread - queue for processing
        handleMessage(message, isInitialMention = false)
          .catchAll { error =>
            ZIO.logError(s"🤖 AI_BOT: Failed to handle message: ${error.getMessage}") *>
              ZIO.unit
          }

      case _ =>
        ZIO.unit

  /** Handle incoming message with state machine transitions */
  private def handleMessage(
      message: ThreadMessage,
      isInitialMention: Boolean
  ): Task[Unit] =
    processingState.modify { state =>
      state match
        // Case 1: Nothing processing, nothing waiting - START PROCESSING
        case ProcessingState(None, None) =>
          val actions =
            addReaction(message, "robot_face") *>
              ZIO.when(isInitialMention)(quickAcknowledge(message)) *>
              startProcessing(message)
          (actions, ProcessingState(Some(message), None))

        // Case 2: Processing active, no waiting - ADD TO WAITING
        case ProcessingState(Some(proc), None) =>
          val actions = addReaction(message, "clock1")
          (actions, ProcessingState(Some(proc), Some(message)))

        // Case 3: Processing active, waiting exists - REPLACE WAITING (skip old)
        case ProcessingState(Some(proc), Some(oldWaiting)) =>
          val actions =
            removeReaction(oldWaiting, "clock1") *>
              addReaction(oldWaiting, "arrow_down") *>
              addReaction(message, "clock1")
          (actions, ProcessingState(Some(proc), Some(message)))

        // Case 4: Nothing processing but waiting exists (race condition recovery)
        case ProcessingState(None, Some(waiting)) =>
          val actions =
            removeReaction(waiting, "clock1") *>
              addReaction(waiting, "robot_face") *>
              addReaction(message, "clock1") *>
              startProcessing(waiting)
          (actions, ProcessingState(Some(waiting), Some(message)))
    }.flatten

  /** Start processing a message in a background fiber */
  private def startProcessing(message: ThreadMessage): UIO[Unit] =
    processMessage(message).forkDaemon.unit

  /** Process a single message (runs to completion, never interrupted) */
  private def processMessage(message: ThreadMessage): Task[Unit] =
    (for {
      _ <- ZIO.logInfo(s"🤖 AI_BOT: Processing message ${message.id.formatted}")

      // Get full thread history for context
      thread <- messageStore
        .retrieveThread(message.threadId)
        .mapError(e => new RuntimeException(s"Failed to retrieve thread: ${e.getMessage}"))

      // Build conversation context
      systemMessage = ChatMessage.system(config.llm.systemPrompt)
      threadMessages = thread.messages.map { msg =>
        if msg.source == slacksocket.demo.domain.conversation.MessageSource.Self then
          ChatMessage.assistant(msg.content)
        else ChatMessage.user(msg.content)
      }
      allMessages = systemMessage :: threadMessages

      _ <- ZIO.logInfo(
        s"🤖 AI_BOT: Generating response (${threadMessages.size} messages in context)"
      )

      // DIAGNOSTIC: Log conversation being sent to LLM
      _ <- ZIO.foreach(allMessages.zipWithIndex) { case (msg, idx) =>
        ZIO.logInfo(s"🤖 AI_BOT_CTX[$idx]: ${msg.role} - ${msg.content.take(100)}")
      }

      // Call LLM (this can take several seconds)
      response <- llmService
        .chat(
          messages = allMessages,
          model = config.llm.model,
          temperature = config.llm.temperature,
          maxTokens = config.llm.maxTokens
        )
        .mapError(e => new RuntimeException(s"LLM error: $e"))

      _ <- ZIO.logInfo(s"🤖 AI_BOT: Generated response (${response.length} chars)")

      // Guard against empty responses
      _ <- ZIO.when(response.trim.isEmpty) {
        ZIO.fail(new RuntimeException("LLM returned empty response"))
      }

      // Post response to Slack thread
      _ <- slackClient
        .postMessage(
          channelId = thread.channelId,
          text = response,
          threadTs = Some(message.threadId)
        )
        .mapError(e => new RuntimeException(s"Slack API error: ${e.getMessage}"))

      // Mark complete with checkmark
      _ <- removeReaction(message, "robot_face")
      _ <- addReaction(message, "white_check_mark")

      _ <- ZIO.logInfo(s"🤖 AI_BOT: Completed processing ${message.id.formatted}")

      // Check if there's a waiting message to process next
      _ <- processNext
    } yield ()).catchAll { error =>
      // On error, still mark complete but log the error
      ZIO.logError(s"🤖 AI_BOT: Processing failed: ${error.getMessage}") *>
        removeReaction(message, "robot_face") *>
        addReaction(message, "x") *> // ❌ for errors
        processNext
    }

  /** Check state for next message to process */
  private def processNext: UIO[Unit] =
    processingState.modify { state =>
      state.waiting match
        case None =>
          // No waiting message - go idle
          (ZIO.unit, ProcessingState(None, None))

        case Some(nextMessage) =>
          // Start processing the waiting message
          val actions =
            removeReaction(nextMessage, "clock1") *>
              addReaction(nextMessage, "robot_face") *>
              processMessage(nextMessage).forkDaemon.unit
          (actions, ProcessingState(Some(nextMessage), None))
    }.flatten

  /** Post quick acknowledgment for initial @mentions */
  private def quickAcknowledge(message: ThreadMessage): Task[Unit] =
    for {
      thread <- messageStore
        .retrieveThread(message.threadId)
        .mapError(e => new RuntimeException(s"Failed to retrieve thread: ${e.getMessage}"))

      _ <- slackClient
        .postMessage(
          channelId = thread.channelId,
          text = "👋 Got it! Let me think about that...",
          threadTs = Some(thread.id)
        )
        .mapError(e => new RuntimeException(s"Slack API error: ${e.getMessage}"))

      _ <- ZIO.logInfo(s"🤖 AI_BOT: Posted acknowledgment")
    } yield ()

  /** Helper: Add reaction with error handling */
  private def addReaction(message: ThreadMessage, emoji: String): UIO[Unit] =
    for {
      thread <- messageStore
        .retrieveThread(message.threadId)
        .orDie // Should never fail - message was just processed
      _ <- slackClient
        .addReaction(
          channel = thread.channelId,
          timestamp = message.id,
          name = emoji
        )
        .catchAll(err => ZIO.logWarning(s"Failed to add reaction :$emoji:: $err"))
    } yield ()

  /** Helper: Remove reaction with error handling */
  private def removeReaction(message: ThreadMessage, emoji: String): UIO[Unit] =
    for {
      thread <- messageStore
        .retrieveThread(message.threadId)
        .orDie
      _ <- slackClient
        .removeReaction(
          channel = thread.channelId,
          timestamp = message.id,
          name = emoji
        )
        .catchAll(err => ZIO.logWarning(s"Failed to remove reaction :$emoji:: $err"))
    } yield ()

object AiBotProcessor:

  /** Processing state for concurrent message handling.
    *
    *   - processing: Message currently being processed (immutable, runs to completion)
    *   - waiting: Message waiting to be processed (mutable, can be replaced by newer message)
    */
  case class ProcessingState(
      processing: Option[ThreadMessage],
      waiting: Option[ThreadMessage]
  )

  /** ZLayer for dependency injection with processing state */
  val layer: ZLayer[
    LLMService & MessageStore & SlackApiClient & AppConfig,
    Nothing,
    AiBotProcessor
  ] =
    ZLayer.fromZIO {
      for {
        llm <- ZIO.service[LLMService]
        store <- ZIO.service[MessageStore]
        slack <- ZIO.service[SlackApiClient]
        config <- ZIO.service[AppConfig]
        state <- Ref.make(ProcessingState(None, None))
      } yield AiBotProcessor(llm, store, slack, config, state)
    }
