package com.nestor10.slackbot.service

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.nestor10.slackbot.domain.conversation.*
import java.time.Instant

/** ZIO Test spec for MessageStore - Zionomicon Chapter 2 patterns */
object MessageStoreSpec extends ZIOSpecDefault:

  def spec = suite("MessageStore")(
    test("stores and retrieves a message") {
      for
        store <- ZIO.service[MessageStore]
        message = ThreadMessage(
          id = MessageId(1234567890.123456),
          threadId = ThreadId(1234567890.123456),
          source = MessageSource.SlackUser,
          author = UserId("U12345"),
          content = "Hello!",
          createdAt = Instant.now(),
          updatedAt = Instant.now()
        )
        _ <- store.store(message)
        retrieved <- store.retrieve(message.id)
      yield assertTrue(
        retrieved.isDefined,
        retrieved.get.content == "Hello!"
      )
    }
  ).provide(
    MessageEventBus.Live.layer,
    MessageStore.InMemory.layer
  )
