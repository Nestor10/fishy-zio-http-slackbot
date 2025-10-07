// sbt-revolver for fast restarts
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")
// sbt-dotenv to load environment variables from .env
addSbtPlugin("nl.gn0s1s" % "sbt-dotenv" % "3.2.0")

// Code formatting (Scalafmt)
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

// Semantic rewrites / lints (Scalafix)
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.1")

// Release management
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0")

// Docker packaging
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")
