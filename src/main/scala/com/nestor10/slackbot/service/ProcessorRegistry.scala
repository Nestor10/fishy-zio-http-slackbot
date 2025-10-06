package com.nestor10.slackbot.service

import zio.*
import com.nestor10.slackbot.processor.MessageProcessor
import com.nestor10.slackbot.domain.service.MessageEventBus

/** Registry for managing message processors and distributing events. \```
  *
  * The ProcessorRegistry maintains a set of registered processors and runs a worker fiber that
  * subscribes to the MessageEventBus. When events arrive, they are distributed to all processors
  * that can handle them, with parallel execution and error isolation.
  *
  * Zionomicon References:
  *   - Chapter 5-6: Fiber Model and Concurrency (worker fiber, parallel processing)
  *   - Chapter 9: Ref (shared state for processor set)
  *   - Chapter 12: Hub (subscription to MessageEventBus)
  *   - Chapter 15: Scope (resource management)
  */
trait ProcessorRegistry:
  /** Register a new processor */
  def register(processor: MessageProcessor): UIO[Unit]

  /** Unregister a processor by name */
  def unregister(name: String): UIO[Unit]

  /** Get all currently registered processors */
  def getProcessors: UIO[Set[MessageProcessor]]

  /** Start the worker fiber that processes events */
  def startProcessing: IO[Nothing, Fiber.Runtime[Nothing, Unit]]

object ProcessorRegistry:

  object Live:

    case class Live(
        processors: Ref[Set[MessageProcessor]],
        eventBus: MessageEventBus
    ) extends ProcessorRegistry:

      override def register(processor: MessageProcessor): UIO[Unit] =
        processors.update(_ + processor) *>
          ZIO.logInfo(s"✅ PROCESSOR_REGISTERED: ${processor.name}")

      override def unregister(name: String): UIO[Unit] =
        processors.update(_.filterNot(_.name == name)) *>
          ZIO.logInfo(s"❌ PROCESSOR_UNREGISTERED: $name")

      override def getProcessors: UIO[Set[MessageProcessor]] =
        processors.get

      override def startProcessing: IO[Nothing, Fiber.Runtime[Nothing, Unit]] =
        ZIO.logInfo("🚀 PROCESSOR_REGISTRY: Starting worker fiber") *>
          processEvents.forkDaemon

      /** Core event processing loop.
        *
        * Subscribes to the MessageEventBus and distributes events to all matching processors in
        * parallel. Each processor runs in isolation so failures don't affect other processors or
        * the event bus.
        */
      private def processEvents: ZIO[Any, Nothing, Unit] =
        ZIO
          .scoped {
            eventBus.subscribeStream.mapZIO { event =>
              for {
                procs <- processors.get
                matchingProcs = procs.filter(_.canProcess(event))
                _ <- ZIO.logDebug(
                  s"📨 EVENT_RECEIVED: ${event.getClass.getSimpleName} -> ${matchingProcs.size} processors"
                )
                // Process in parallel with error isolation
                _ <- ZIO.foreachParDiscard(matchingProcs) { processor =>
                  processor
                    .process(event)
                    .catchAll { error =>
                      // Log error but don't fail - isolate processor failures
                      ZIO.logError(
                        s"⚠️ PROCESSOR_ERROR: ${processor.name} - ${error.message}"
                      )
                    }
                    .forkDaemon // Fork each processor to prevent blocking
                    .unit
                }
              } yield ()
            }.runDrain
          }
          .catchAllCause { cause =>
            // Worker fiber should never die - log and restart
            ZIO.logErrorCause(s"💥 PROCESSOR_WORKER_DIED: Restarting...", cause) *>
              processEvents
          }

    /** ZLayer construction.
      *
      * Depends on MessageEventBus for event subscription. Creates a Ref to hold the processor set.
      */
    val layer: ZLayer[MessageEventBus, Nothing, ProcessorRegistry] =
      ZLayer.fromZIO {
        for {
          bus <- ZIO.service[MessageEventBus]
          ref <- Ref.make(Set.empty[MessageProcessor])
        } yield Live(ref, bus)
      }

  // Accessor methods (Chapter 19: Contextual Data Types)

  def register(processor: MessageProcessor): URIO[ProcessorRegistry, Unit] =
    ZIO.serviceWithZIO[ProcessorRegistry](_.register(processor))

  def unregister(name: String): URIO[ProcessorRegistry, Unit] =
    ZIO.serviceWithZIO[ProcessorRegistry](_.unregister(name))

  def getProcessors: URIO[ProcessorRegistry, Set[MessageProcessor]] =
    ZIO.serviceWithZIO[ProcessorRegistry](_.getProcessors)

  def startProcessing: URIO[ProcessorRegistry, Fiber.Runtime[Nothing, Unit]] =
    ZIO.serviceWithZIO[ProcessorRegistry](_.startProcessing)
