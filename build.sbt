val scala3Version = "3.7.2"

// Fork JVM for run and reStart (prevents sbt locking, cleaner shutdown)
ThisBuild / run / fork := true
Compile / run / fork := true
reStart / fork := true

// JVM options for forked processes (optional but recommended)
ThisBuild / run / javaOptions ++= Seq(
  "-Xmx512m",                    // Max heap 512MB
  "-XX:+UseG1GC",                // Use G1 garbage collector
  "-XX:MaxGCPauseMillis=200"     // Target max GC pause
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "fishy-zio-http-socket",
    version := "0.1.0-SNAPSHOT",
    organization := "com.nestor10",

  scalaVersion := scala3Version,
  // Format code on compile (scalafmt)
  ThisBuild / scalafmtOnCompile := true,
  // Enable semanticdb (Scala 3 provides it; flag kept if supported, harmless if ignored)
  ThisBuild / scalacOptions += "-Ysemanticdb",
  // Explicit main class (uses new package structure)
  Compile / mainClass := Some("com.nestor10.slackbot.Main"),
  // Use ZIO Test framework for running tests
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    libraryDependencies ++= Seq(
      // ZIO Test - the official testing library for ZIO (Zionomicon Chapter 2)
      "dev.zio" %% "zio-test" % "2.1.21" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.21" % Test,
      "dev.zio" %% "zio-test-magnolia" % "2.1.21" % Test, // Auto-derive test instances
      
      "dev.zio" %% "zio-http" % "3.5.1",
      // Logging dependencies for Netty internal logs via SLF4J
      "ch.qos.logback" % "logback-classic" % "1.5.6",
      // zio-config core + magnolia derivation + typesafe (HOCON) + refinement (optional)
      "dev.zio" %% "zio-config" % "4.0.5",
      "dev.zio" %% "zio-config-magnolia" % "4.0.5",
      "dev.zio" %% "zio-config-typesafe" % "4.0.5",
      "dev.zio" %% "zio-json" % "0.7.44",
      
      // OpenTelemetry + ZIO integration (Observability: Traces, Metrics, Logs)
      "dev.zio" %% "zio-opentelemetry" % "3.1.10",
      "dev.zio" %% "zio-opentelemetry-zio-logging" % "3.1.10", // Trace correlation in logs
      "io.opentelemetry" % "opentelemetry-sdk" % "1.42.1",
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.42.1",
      
      // ZIO Logging for structured logs
      "dev.zio" %% "zio-logging" % "2.4.0",
      "dev.zio" %% "zio-logging-slf4j2-bridge" % "2.4.0" // Bridge to SLF4J for existing logs
    )
  )

// Reload the build automatically when build.sbt / project/ changes
Global / onChangedBuildSource := ReloadOnSourceChanges

// Handy alias: continuous compile + restart (requires sbt-revolver)
addCommandAlias("dev", "~compile; reStart")

 inThisBuild(
   List(
     scalaVersion := "3.7.2",
     semanticdbEnabled := true,
   semanticdbVersion := scalafixSemanticdb.revision
)
)