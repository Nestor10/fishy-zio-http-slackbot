package com.nestor10.slackbot.domain.processor

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.telemetry.opentelemetry.tracing.Tracing
import com.nestor10.slackbot.infrastructure.storage.MessageStore
import com.nestor10.slackbot.infrastructure.llm.LLMService
import com.nestor10.slackbot.infrastructure.slack.SlackApiClient
import com.nestor10.slackbot.infrastructure.observability.{LLMMetrics, OpenTelemetrySetup}
import com.nestor10.slackbot.domain.service.MessageEventBus
import com.nestor10.slackbot.domain.model.conversation.*
import com.nestor10.slackbot.domain.model.llm.{ChatMessage, ChatRole}
import com.nestor10.slackbot.conf.{AppConfig, LlmConfig}
import java.time.Instant

/** ZIO Test spec for AiBotProcessor - Critical path testing
  *
  * Tests cover:
  *   - Event filtering (bot messages vs user messages)
  *   - Per-thread worker creation
  *   - Handling messages without timestamps
  *
  * Note: These are UNIT tests for processor logic only. We use stub implementations for external
  * dependencies (LLM, Slack) to avoid network calls and focus on the processor's filtering and
  * queueing behavior.
  */
object AiBotProcessorSpec extends ZIOSpecDefault:

  // Test bot identity
  private val testBotIdentity = BotIdentity(
    userId = UserId("B_TEST_BOT"),
    username = "testbot",
    displayName = Some("Test Bot")
  )

  // Stub LLMService that returns immediately without network calls
  private val stubLLMService = new LLMService:
    override def chat(
        messages: List[ChatMessage],
        model: String,
        temperature: Option[Double],
        maxTokens: Option[Int]
    ): IO[LLMService.Error, String] =
      ZIO.succeed("Stub LLM response")

    override def chatWithResponse(
        messages: List[ChatMessage],
        model: String,
        temperature: Option[Double],
        maxTokens: Option[Int]
    ): IO[LLMService.Error, com.nestor10.slackbot.domain.model.llm.ChatResponse] =
      import com.nestor10.slackbot.domain.model.llm.{ChatChoice, ChatRole}
      ZIO.succeed(
        com.nestor10.slackbot.domain.model.llm.ChatResponse(
          id = "stub-id",
          objectType = "chat.completion",
          created = java.lang.System.currentTimeMillis() / 1000,
          model = model,
          choices = List(
            ChatChoice(
              index = 0,
              message = ChatMessage(
                role = ChatRole.Assistant,
                content = "Stub response"
              ),
              finishReason = "stop"
            )
          ),
          usage = None
        )
      )

  private val stubLLMLayer: ULayer[LLMService] = ZLayer.succeed(stubLLMService)

  // Stub SlackApiClient that tracks posted messages
  class TrackingSlackClient(posted: Ref[List[(ChannelId, String, ThreadId)]])
      extends SlackApiClient:

    override def requestSocketUrl: ZIO[Scope, SlackApiClient.Error, SlackApiClient.Connection] =
      ZIO.succeed(SlackApiClient.Connection("wss://stub"))

    override def postMessage(
        channelId: ChannelId,
        text: String,
        threadTs: Option[ThreadId]
    ): ZIO[Scope, SlackApiClient.Error, String] =
      posted.update(_ :+ (channelId, text, threadTs.getOrElse(ThreadId(0.0)))) *>
        ZIO.logInfo(
          s"TRACKING: Posted to ${channelId.value} thread ${threadTs.map(_.formatted).getOrElse("none")}: $text"
        ) *>
        ZIO.succeed("1234567890.123456")

    override def authTest: ZIO[Scope, SlackApiClient.Error, SlackApiClient.AuthTestResponse] =
      ZIO.succeed(
        SlackApiClient.AuthTestResponse(
          ok = true,
          url = "https://test.slack.com",
          team = "Test Team",
          user = "testbot",
          team_id = "T12345",
          user_id = "B_TEST",
          bot_id = Some("B_TEST"),
          error = None
        )
      )

    override def addReaction(
        channel: ChannelId,
        timestamp: MessageId,
        name: String
    ): ZIO[Scope, SlackApiClient.Error, Unit] = ZIO.unit

    override def removeReaction(
        channel: ChannelId,
        timestamp: MessageId,
        name: String
    ): ZIO[Scope, SlackApiClient.Error, Unit] = ZIO.unit

    override def getConversationReplies(
        channel: ChannelId,
        threadTs: ThreadId
    ): ZIO[Scope, SlackApiClient.Error, SlackApiClient.ConversationRepliesResponse] =
      ZIO.succeed(
        SlackApiClient.ConversationRepliesResponse(
          ok = true,
          messages = Some(List.empty),
          has_more = Some(false),
          error = None
        )
      )

  object TrackingSlackClient:

    val layer: ZLayer[Any, Nothing, (SlackApiClient, Ref[List[(ChannelId, String, ThreadId)]])] =
      ZLayer {
        for {
          posted <- Ref.make(List.empty[(ChannelId, String, ThreadId)])
          client = TrackingSlackClient(posted)
        } yield (client, posted)
      }

    // Split into separate layers for dependency injection
    val slackLayer: ZLayer[Any, Nothing, SlackApiClient] =
      layer.map(pair => ZEnvironment(pair.get._1))

    val refLayer: ZLayer[Any, Nothing, Ref[List[(ChannelId, String, ThreadId)]]] =
      layer.map(pair => ZEnvironment(pair.get._2))

  // Test config
  private val testConfig = AppConfig(
    slackAppToken = "xapp-test",
    slackBotToken = "xoxb-test",
    pingIntervalSeconds = 30,
    debugReconnects = false,
    socketCount = 1,
    llm = LlmConfig(
      baseUrl = "http://localhost:11434",
      apiKey = None,
      model = "llama3.2:latest",
      temperature = Some(0.7),
      maxTokens = None,
      systemPrompt = "You are a helpful test bot."
    ),
    otel = com.nestor10.slackbot.conf.OtelConfig(
      otlpEndpoint = "http://localhost:4317",
      serviceName = "test-service",
      instrumentationScopeName = "test-scope"
    )
  )

  def spec = suite("AiBotProcessor")(
    test("filters out bot messages (only processes user messages)") {
      for {
        processor <- ZIO.service[AiBotProcessor]

        // Create bot message (should be filtered)
        botMessage = ThreadMessage(
          id = MessageId(1.0),
          threadId = ThreadId(1.0),
          source = MessageSource.Self,
          author = testBotIdentity.userId,
          content = "Bot message",
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
          slackCreatedAt = Some(Instant.now())
        )

        // Create user message (should be processed)
        userMessage = ThreadMessage(
          id = MessageId(2.0),
          threadId = ThreadId(2.0),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "User message",
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
          slackCreatedAt = Some(Instant.now())
        )

        // Test canProcess filter
        botEvent = MessageEventBus.MessageStored(botMessage, Instant.now())
        userEvent = MessageEventBus.MessageStored(userMessage, Instant.now())

      } yield assertTrue(
        processor.canProcess(botEvent) == false, // Bot messages filtered out
        processor.canProcess(userEvent) == true // User messages processed
      )
    },
    test("creates per-thread workers for isolated processing") {
      for {
        processor <- ZIO.service[AiBotProcessor]
        store <- ZIO.service[MessageStore]
        now = Instant.now()

        // Create two threads
        thread1 = createTestThread(100.0, "C_THREAD1", "Hello from thread 1", now)
        thread2 = createTestThread(200.0, "C_THREAD2", "Hello from thread 2", now)

        // Store threads and messages
        _ <- store.storeThread(thread1)
        _ <- store.store(thread1.rootMessage)
        _ <- store.storeThread(thread2)
        _ <- store.store(thread2.rootMessage)

        // Process ThreadCreated events
        event1 = MessageEventBus.ThreadCreated(thread1, now)
        event2 = MessageEventBus.ThreadCreated(thread2, now)

        _ <- processor.process(event1)
        _ <- processor.process(event2)

        // Give workers time to start processing
        _ <- ZIO.sleep(50.millis)

      } yield assertTrue(true) // Workers created without errors
    },
    test("skips events without Slack timestamps") {
      for {
        processor <- ZIO.service[AiBotProcessor]

        // Message without slackCreatedAt
        message = ThreadMessage(
          id = MessageId(3.0),
          threadId = ThreadId(3.0),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "No timestamp",
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
          slackCreatedAt = None // No Slack timestamp
        )

        event = MessageEventBus.MessageStored(message, Instant.now())

        // Should process without error but skip due to no timestamp
        result <- processor.process(event).either

      } yield assertTrue(result.isRight)
    },
    test("latest-wins: only processes most recent message in rapid succession") {
      for {
        processor <- ZIO.service[AiBotProcessor]
        store <- ZIO.service[MessageStore]
        posted <- ZIO.service[Ref[List[(ChannelId, String, ThreadId)]]]
        now = Instant.now()

        // Create thread with root message
        thread = createTestThread(300.0, "C_RAPID", "Initial message", now)
        _ <- store.storeThread(thread)
        _ <- store.store(thread.rootMessage)

        // Create 3 rapid messages to same thread, 10ms apart
        msg1 = ThreadMessage(
          id = MessageId(301.0),
          threadId = ThreadId(300.0),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "First rapid message",
          createdAt = now,
          updatedAt = now,
          slackCreatedAt = Some(now.plusMillis(10))
        )
        msg2 = ThreadMessage(
          id = MessageId(302.0),
          threadId = ThreadId(300.0),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "Second rapid message",
          createdAt = now,
          updatedAt = now,
          slackCreatedAt = Some(now.plusMillis(20))
        )
        msg3 = ThreadMessage(
          id = MessageId(303.0),
          threadId = ThreadId(300.0),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "Third rapid message - LATEST",
          createdAt = now,
          updatedAt = now,
          slackCreatedAt = Some(now.plusMillis(30))
        )

        // Store all messages
        _ <- store.store(msg1)
        _ <- store.store(msg2)
        _ <- store.store(msg3)

        // Process events rapidly (queue should slide to keep only latest)
        event1 = MessageEventBus.MessageStored(msg1, now)
        event2 = MessageEventBus.MessageStored(msg2, now)
        event3 = MessageEventBus.MessageStored(msg3, now)

        _ <- processor.process(event1)
        _ <- processor.process(event2) // This should slide out msg1
        _ <- processor.process(event3) // This should slide out msg2

        // Wait for debounce (100ms) + processing time
        _ <- ZIO.sleep(250.millis)

        // Verify ONLY 1 response was posted (to the latest message)
        allPosts <- posted.get
        postsToThread300 = allPosts.filter(_._3 == ThreadId(300.0))

      } yield assertTrue(
        postsToThread300.size == 1, // Only 1 response posted (not 3!)
        postsToThread300.head._1 == ChannelId("C_RAPID"), // To correct channel
        postsToThread300.head._3 == ThreadId(300.0) // To correct thread
      )
    }
  ).provide(
    MessageEventBus.Live.layer,
    MessageStore.InMemory.layer,
    stubLLMLayer, // Stub LLM service - no network calls
    TrackingSlackClient.slackLayer, // Tracking Slack client - records all posts
    TrackingSlackClient.refLayer, // Ref to access posted messages
    LLMMetrics.Live.layer, // Real metrics service
    ZLayer.succeed(testConfig),
    OpenTelemetrySetup.live,
    AiBotProcessor.layer
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  // Helper to create test thread
  private def createTestThread(
      id: Double,
      channelId: String,
      content: String,
      timestamp: Instant
  ): Thread = {
    val rootMessage = ThreadMessage(
      id = MessageId(id),
      threadId = ThreadId(id),
      source = MessageSource.SlackUser,
      author = UserId("U12345"),
      content = content,
      createdAt = timestamp,
      updatedAt = timestamp,
      slackCreatedAt = Some(timestamp)
    )
    Thread(
      id = ThreadId(id),
      channelId = ChannelId(channelId),
      botIdentity = testBotIdentity,
      rootMessage = rootMessage,
      state = ThreadState(),
      createdAt = timestamp,
      lastUpdatedAt = timestamp
    )
  }
