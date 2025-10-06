package slacksocket.demo.otel

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

/** Example demonstrating how to use OpenTelemetry tracing aspects.
  *
  * Based on Zio-telemetry.md Section 5 patterns for zio-opentelemetry 3.1.10.
  *
  * Key Patterns:
  *   - Access aspects via Tracing service instance: `tracing.aspects.root/span`
  *   - Apply with @@ operator: `effect @@ tracing.aspects.span("name")`
  *   - Set attributes: `tracing.setAttribute(key, value)`
  *   - Add events: `tracing.addEvent(name)`
  */
object TracingExamples:

  /** Example: Root span for entry point */
  def tracedEntryPoint: ZIO[Tracing, Throwable, String] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      (for {
        _ <- ZIO.logInfo("Starting main operation")
        _ <- tracing.setAttribute("user.id", "12345")
        result <- subOperation1
        _ <- subOperation2
        _ <- ZIO.logInfo("Finished main operation")
      } yield result) @@ tracing.aspects.root("main_operation")
    }

  /** Example: Child span for internal operation */
  private def subOperation1: ZIO[Tracing, Throwable, String] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      (for {
        _ <- tracing.setAttribute("operation.type", "database_query")
        _ <- ZIO.sleep(100.millis)
        _ <- tracing.addEvent("query_executed")
      } yield "result") @@ tracing.aspects.span("sub_operation_1")
    }

  /** Example: Another child span */
  private def subOperation2: ZIO[Tracing, Throwable, Unit] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      (for {
        _ <- tracing.setAttribute("cache.hit", "false")
        _ <- ZIO.sleep(50.millis)
        _ <- tracing.addEvent("cache_miss")
      } yield ()) @@ tracing.aspects.span("sub_operation_2")
    }

  /** Example: Scoped span (guarantees finalization) */
  def tracedWithScope: ZIO[Tracing, Throwable, String] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      (ZIO.scoped {
        for {
          _ <- tracing.setAttribute("resource.id", "abc123")
          result <- ZIO.succeed("scoped result")
          _ <- tracing.addEvent("operation_complete")
        } yield result
      }) @@ tracing.aspects.span("scoped_operation")
    }

  /** Example: Error handling (automatic error recording) */
  def tracedWithError: ZIO[Tracing, Throwable, Unit] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      (for {
        _ <- ZIO.attempt(throw new RuntimeException("Simulated error"))
      } yield ()) @@ tracing.aspects.span("failing_operation")
      // Span will automatically record the exception and set status to ERROR
    }
