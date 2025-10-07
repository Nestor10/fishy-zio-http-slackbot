package com.nestor10.slackbot.infrastructure.storage

import com.nestor10.slackbot.domain.model.conversation.*
import com.nestor10.slackbot.domain.service.MessageEventBus
import zio.*
import zio.test.Assertion.*
import zio.test.*

import java.time.Instant

/** ZIO Test spec for MessageStore - Critical path testing
  *
  * Tests cover:
  *   - Concurrent thread creation (race conditions)
  *   - Message storage and retrieval
  *   - Message ordering within threads
  *   - Time-range queries
  */
object MessageStoreSpec extends ZIOSpecDefault:

  // Test bot identity
  private val testBotIdentity = BotIdentity(
    userId = UserId("B_TEST_BOT"),
    username = "testbot",
    displayName = Some("Test Bot")
  )

  // Helper to create test thread
  private def createThread(id: Double, rootContent: String): Thread = {
    val rootMessage = ThreadMessage(
      id = MessageId(id),
      threadId = ThreadId(id),
      source = MessageSource.SlackUser,
      author = UserId("U12345"),
      content = rootContent,
      createdAt = Instant.now(),
      updatedAt = Instant.now()
    )
    Thread(
      id = ThreadId(id),
      channelId = ChannelId("C12345"),
      botIdentity = testBotIdentity,
      rootMessage = rootMessage,
      state = ThreadState(),
      createdAt = Instant.now(),
      lastUpdatedAt = Instant.now()
    )
  }

  def spec = suite("MessageStore")(
    test("stores and retrieves a message") {
      for {
        store <- ZIO.service[MessageStore]
        message = ThreadMessage(
          id = MessageId(1.0),
          threadId = ThreadId(1.0),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "Hello!",
          createdAt = Instant.now(),
          updatedAt = Instant.now()
        )
        _ <- store.store(message)
        retrieved <- store.retrieve(message.id)
      } yield assertTrue(
        retrieved.isDefined,
        retrieved.get.content == "Hello!"
      )
    },
    test("stores and retrieves a complete thread") {
      for {
        store <- ZIO.service[MessageStore]
        thread = createThread(100.0, "Thread root message")
        _ <- store.storeThread(thread)
        retrieved <- store.retrieveThread(thread.id)
      } yield assertTrue(
        retrieved.id == thread.id,
        retrieved.rootMessage.content == "Thread root message"
      )
    },
    test("handles concurrent thread creation without race conditions") {
      for {
        store <- ZIO.service[MessageStore]
        // Create 10 threads concurrently
        threads = (1 to 10).map(i => createThread(i.toDouble * 1000, s"Thread $i"))
        _ <- ZIO.foreachPar(threads)(store.storeThread(_))
        // Verify all threads were stored
        retrieved <- ZIO.foreach(threads)(t => store.retrieveThread(t.id))
      } yield assertTrue(
        retrieved.size == 10,
        retrieved.map(_.rootMessage.content).toSet == threads.map(_.rootMessage.content).toSet
      )
    },
    test("maintains message ordering within a thread") {
      for {
        store <- ZIO.service[MessageStore]
        now = Instant.now()

        // Create root message with slackCreatedAt for ordering
        rootMessage = ThreadMessage(
          id = MessageId(200.0),
          threadId = ThreadId(200.0),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "Root",
          createdAt = now,
          updatedAt = now,
          slackCreatedAt = Some(now)
        )
        thread = Thread(
          id = ThreadId(200.0),
          channelId = ChannelId("C12345"),
          botIdentity = testBotIdentity,
          rootMessage = rootMessage,
          state = ThreadState(),
          createdAt = now,
          lastUpdatedAt = now
        )

        // Store thread AND root message
        _ <- store.storeThread(thread)
        _ <- store.store(rootMessage)

        // Add messages in specific order with slackCreatedAt timestamps
        msg1 = ThreadMessage(
          id = MessageId(201.0),
          threadId = ThreadId(200.0),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "First reply",
          createdAt = now,
          updatedAt = now,
          slackCreatedAt = Some(now.plusMillis(100))
        )
        msg2 = ThreadMessage(
          id = MessageId(202.0),
          threadId = ThreadId(200.0),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "Second reply",
          createdAt = now,
          updatedAt = now,
          slackCreatedAt = Some(now.plusMillis(200))
        )

        _ <- store.store(msg1)
        _ <- store.store(msg2)

        // Stream messages and check order (sorted by slackCreatedAt)
        messages <- store.streamThread(ThreadId(200.0)).runCollect
      } yield assertTrue(
        messages.size == 3, // root + 2 replies
        messages.map(_.content).toList == List("Root", "First reply", "Second reply")
      )
    },
    test("returns empty option for non-existent message") {
      for {
        store <- ZIO.service[MessageStore]
        retrieved <- store.retrieve(MessageId(999999.0))
      } yield assertTrue(retrieved.isEmpty)
    }
  ).provide(
    MessageEventBus.Live.layer,
    MessageStore.InMemory.layer
  ) @@ TestAspect.sequential // Run tests sequentially to avoid state conflicts
