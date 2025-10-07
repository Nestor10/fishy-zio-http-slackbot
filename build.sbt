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
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "fishy-zio-http-socket",
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
// ============================================================================
// Docker Configuration (sbt-native-packager)
// ============================================================================
//
// Multi-arch builds (AMD64 + ARM64) are handled in GitHub Actions using
// Docker Buildx. Locally, use:
//   - `sbt Docker/publishLocal` for single-arch testing
//   - GitHub Actions for multi-arch production images

import com.typesafe.sbt.packager.docker._

// Use podman instead of docker (macOS with podman-machine)
dockerExecCommand := Seq("podman")

// Run as root for simplicity (can secure later)
Docker / daemonUser := "root"

Docker / packageName := "fishy-zio-http-slackbot"
Docker / version := version.value
dockerRepository := Some("quay.io/nestor10")
dockerBaseImage := "eclipse-temurin:23-jre-noble"  // Ubuntu Noble with Java 23
dockerUpdateLatest := true
dockerExposedPorts := Seq(8080, 8888, 8889)

// Use exec form for better signal handling
dockerEntrypoint := Seq("/opt/docker/bin/fishy-zio-http-socket")

// ============================================================================
// Release Configuration (sbt-release)
// ============================================================================

import ReleaseTransformations._
import ReleasePlugin.autoImport._

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommand("docker:publish"),  // Publish Docker image
  setNextVersion,
  commitNextVersion,
  pushChanges
)

// Version bumping strategy (default: Next = bump last part)
releaseVersionBump := sbtrelease.Version.Bump.Next

// Custom commit messages
releaseCommitMessage := s"Release ${version.value}"
releaseTagComment := s"Release ${version.value}"
releaseNextCommitMessage := s"Bump version to ${version.value}"
