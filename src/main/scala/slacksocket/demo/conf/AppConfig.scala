package slacksocket.demo.conf

import zio._
import zio.config.magnolia.deriveConfig

final case class AppConfig(
    pingIntervalSeconds: Int,
    slackAppToken: String,
    debugReconnects: Boolean,
    socketCount: Int
)

object AppConfig {
  val config: Config[AppConfig] = deriveConfig[AppConfig].nested("app")
  val layer: ZLayer[Any, Throwable, AppConfig] = ZLayer.fromZIO(ZIO.config(config))
}
