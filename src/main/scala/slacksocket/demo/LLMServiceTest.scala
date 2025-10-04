package slacksocket.demo

import zio.*
import zio.http.*
import slacksocket.demo.service.LLMService
import slacksocket.demo.domain.llm.ChatMessage

/** Quick test of the LLMService with local Ollama.
  *
  * Run Ollama first: podman run -d --name ollama -p 11434:11434 ollama/ollama Pull model: podman
  * exec -it ollama ollama pull qwen2.5:0.5b
  */
object LLMServiceTest extends ZIOAppDefault:

  val run = ZIO
    .scoped {
      for {
        _ <- ZIO.logInfo("🧪 Testing LLMService with Ollama...")

        // Simple test
        response1 <- LLMService.chat(
          messages = List(ChatMessage.user("Say hello in one sentence.")),
          model = "qwen2.5:0.5b"
        )
        _ <- ZIO.logInfo(s"✅ Response 1: $response1")

        // Conversation test
        conversation = List(
          ChatMessage.system("You are a helpful assistant. Be concise."),
          ChatMessage.user("What is the capital of France?")
        )
        response2 <- LLMService.chat(
          messages = conversation,
          model = "qwen2.5:0.5b",
          temperature = Some(0.7)
        )
        _ <- ZIO.logInfo(s"✅ Response 2: $response2")

        // Full response test
        fullResponse <- LLMService.chatWithResponse(
          messages = List(ChatMessage.user("Count from 1 to 5.")),
          model = "qwen2.5:0.5b"
        )
        _ <- ZIO.logInfo(s"✅ Full response:")
        _ <- ZIO.logInfo(s"   ID: ${fullResponse.id}")
        _ <- ZIO.logInfo(s"   Model: ${fullResponse.model}")
        _ <- ZIO.logInfo(s"   Content: ${fullResponse.firstContent}")
        _ <- ZIO.logInfo(s"   Tokens: ${fullResponse.usage.map(_.totalTokens)}")

        _ <- ZIO.logInfo("✅ All tests passed!")

      } yield ()
    }
    .provide(
      Client.default,
      LLMService.ollama("http://localhost:11434")
    )
    .catchAll { error =>
      ZIO.logError(s"❌ Test failed: ${error}")
    }
