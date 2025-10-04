package slacksocket.demo.processor

import zio.*
import slacksocket.demo.service.{MessageEventBus, MessageStore, LLMService, SlackApiClient}
import slacksocket.demo.service.MessageEventBus.MessageEvent
import slacksocket.demo.domain.llm.{ChatMessage, ChatRole}
import slacksocket.demo.domain.conversation.ThreadMessage
import slacksocket.demo.conf.AppConfig

/** AI Bot processor for handling thread-based conversations.
  *
  * Integrates with LLM service to generate intelligent responses to Slack messages. Maintains
  * conversation context by retrieving thread history from MessageStore.
  *
  * Zionomicon References:
  *   - Chapter 17-18: Dependency Injection (Multi-service dependencies)
  *   - Chapter 3: Error Model (Error handling across service boundaries)
  */
class AiBotProcessor(
    llmService: LLMService,
    messageStore: MessageStore,
    slackClient: SlackApiClient,
    config: AppConfig
) extends MessageProcessor:

  override val name: String = "AiBotProcessor"

  override def canProcess(event: MessageEvent): Boolean = event match
    case MessageEvent.ThreadCreated(_, _)       => true // Always acknowledge new threads
    case MessageEvent.MessageStored(message, _) =>
      // Phase 7c: Filter bot messages to prevent infinite loop (Option 2 pattern)
      // Only process user messages - don't respond to our own messages
      message.source != slacksocket.demo.domain.conversation.MessageSource.Self
    case _ => false

  override def process(event: MessageEvent): IO[MessageProcessor.Error, Unit] =
    event match
      case MessageEvent.ThreadCreated(thread, timestamp) =>
        // When a new thread is created from @mention, we need to:
        // 1. Post quick acknowledgment
        // 2. Generate and post AI response to the initial message
        // Note: generateAndPostResponse retrieves full thread history from MessageStore,
        // including the acknowledgment we just posted
        (for {
          _ <- acknowledgeThread(thread)
          // Pass root message - generateAndPostResponse will fetch full thread history
          _ <- generateAndPostResponse(thread.rootMessage)
        } yield ()).catchAll { error =>
          ZIO.logError(s"🤖 AI_BOT: Failed to process new thread: ${error.getMessage}") *>
            ZIO.unit // Don't crash processor on errors
        }

      case MessageEvent.MessageStored(message, timestamp) =>
        // User replied in thread - generate AI response with full conversation context
        generateAndPostResponse(message).catchAll { error =>
          ZIO.logError(s"🤖 AI_BOT: Failed to generate response: ${error.getMessage}") *>
            ZIO.unit // Don't crash processor on errors
        }

      case _ =>
        ZIO.unit

  /** Post immediate acknowledgment when a new thread is created */
  private def acknowledgeThread(
      thread: slacksocket.demo.domain.conversation.Thread
  ): Task[Unit] =
    for {
      _ <- ZIO.logInfo(
        s"🤖 AI_BOT: Acknowledging new thread ${thread.id.formatted}"
      )

      // Post quick acknowledgment
      _ <- slackClient
        .postMessage(
          channelId = thread.channelId,
          text = "👋 Got it! Let me think about that...",
          threadTs = Some(thread.id)
        )
        .mapError(e => new RuntimeException(s"Slack API error: ${e.getMessage}"))

      _ <- ZIO.logInfo(
        s"🤖 AI_BOT: Posted acknowledgment to thread ${thread.id.formatted}"
      )

    } yield ()

  /** Generate AI response and post it to Slack thread */
  private def generateAndPostResponse(message: ThreadMessage): Task[Unit] =
    for {
      // Get thread history for context
      thread <- messageStore
        .retrieveThread(message.threadId)
        .mapError(e => new RuntimeException(s"Failed to retrieve thread: ${e.getMessage}"))

      // Build conversation context: system prompt + all messages in thread
      systemMessage = ChatMessage.system(config.llm.systemPrompt)
      threadMessages = thread.messages.map { msg =>
        if msg.source == slacksocket.demo.domain.conversation.MessageSource.Self then
          ChatMessage.assistant(msg.content)
        else ChatMessage.user(msg.content)
      }
      allMessages = systemMessage :: threadMessages

      _ <- ZIO.logInfo(
        s"🤖 AI_BOT: Generating response for thread ${message.threadId.formatted} (${threadMessages.size} messages in context)"
      )

      // DIAGNOSTIC: Log the full request being sent to LLM
      _ <- ZIO.logInfo(
        s"🤖 AI_BOT_DIAGNOSTIC: LLM Request - model=${config.llm.model} temp=${config.llm.temperature} maxTokens=${config.llm.maxTokens}"
      )
      _ <- ZIO.logInfo(
        s"🤖 AI_BOT_DIAGNOSTIC: System prompt: ${config.llm.systemPrompt}"
      )
      _ <- ZIO.foreach(threadMessages.zipWithIndex) { case (msg, idx) =>
        ZIO.logInfo(
          s"🤖 AI_BOT_DIAGNOSTIC: Message[$idx] role=${msg.role} content=${msg.content.take(100)}"
        )
      }

      // Call LLM
      response <- llmService
        .chat(
          messages = allMessages,
          model = config.llm.model,
          temperature = config.llm.temperature,
          maxTokens = config.llm.maxTokens
        )
        .mapError(e => new RuntimeException(s"LLM error: $e"))

      _ <- ZIO.logInfo(s"🤖 AI_BOT: Generated response (${response.length} chars)")

      // Post response to Slack thread
      _ <- slackClient
        .postMessage(
          channelId = thread.channelId,
          text = response,
          threadTs = Some(message.threadId)
        )
        .mapError(e => new RuntimeException(s"Slack API error: ${e.getMessage}"))

      _ <- ZIO.logInfo(s"🤖 AI_BOT: Posted response to thread ${message.threadId.formatted}")

    } yield ()

object AiBotProcessor:

  /** ZLayer for dependency injection */
  val layer
      : ZLayer[LLMService & MessageStore & SlackApiClient & AppConfig, Nothing, AiBotProcessor] =
    ZLayer.fromFunction(AiBotProcessor.apply)
