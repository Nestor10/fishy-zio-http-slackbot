val scala3Version = "3.7.2"

ThisBuild / run / fork := true

lazy val root = project
  .in(file("."))
  .settings(
    name := "fishy-zio-http-socket",
    version := "0.1.0-SNAPSHOT",

  scalaVersion := scala3Version,
  // Format code on compile (scalafmt)
  ThisBuild / scalafmtOnCompile := true,
  // Enable semanticdb (Scala 3 provides it; flag kept if supported, harmless if ignored)
  ThisBuild / scalacOptions += "-Ysemanticdb",
  // Explicit main class (used by run & reStart)
  Compile / mainClass := Some("slacksocket.demo.SlackSocketDemoApp"),

    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.0" % Test,
      "dev.zio" %% "zio-http" % "3.5.0",
      // Logging dependencies for Netty internal logs via SLF4J
      "ch.qos.logback" % "logback-classic" % "1.5.6",
      // zio-config core + magnolia derivation + typesafe (HOCON) + refinement (optional)
      "dev.zio" %% "zio-config" % "4.0.4",
      "dev.zio" %% "zio-config-magnolia" % "4.0.4",
      "dev.zio" %% "zio-config-typesafe" % "4.0.4"
  , "dev.zio" %% "zio-json" % "0.7.3"
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