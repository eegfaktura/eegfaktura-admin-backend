package at.ourproject.logging

import ch.qos.logback.core.PropertyDefinerBase
import com.typesafe.config.{Config, ConfigFactory}

// The one logging setup of the service (eegfaktura-dev logging concept W-261008-ca2): every line goes through
// logback.xml and the EcsEncoder there - the application's SLF4J loggers and Pekko's (Slf4jLogger, see `pekkoLogging`).
//
//   LOG_LEVEL      trace | debug | info | warn | error (default info; `warning` is read as warn) - root level
//   LOG_LEVEL_APP  the same values, level of the application loggers at.ourproject.* (default: LOG_LEVEL)
//   LOG_FORMAT     json | text (the image sets json; from source the default is text)
//   SERVICE_VERSION  set by the image build, otherwise `dev`
//
// An unknown level or format stops the start (`validate`), logback alone would fall back to DEBUG.
object LogSettings {

  val ServiceName = "eegfaktura-admin-backend"

  private val Levels = Map(
    "trace" -> "TRACE", "debug" -> "DEBUG", "info" -> "INFO", "warn" -> "WARN", "warning" -> "WARN", "error" -> "ERROR")
  val Formats: Set[String] = Set("json", "text")

  /** The logback level name for a LOG_LEVEL value, None when it is not one of the allowed values. */
  def parseLevel(value: String): Option[String] = Levels.get(value.trim.toLowerCase)

  def parseFormat(value: String): Option[String] = Some(value.trim.toLowerCase).filter(Formats)

  /** The reason the start is refused, if it is. */
  def validate(env: Map[String, String]): Either[String, Unit] = {
    val levels = Seq("LOG_LEVEL", "LOG_LEVEL_APP").flatMap(name => env.get(name).map(name -> _))
      .collectFirst { case (name, v) if parseLevel(v).isEmpty =>
        s"$name='$v' is not one of trace, debug, info, warn, error" }
    val format = env.get("LOG_FORMAT").filter(parseFormat(_).isEmpty).map(v => s"LOG_FORMAT='$v' is not one of json, text")
    levels.orElse(format).toLeft(())
  }

  /** The level logback.xml uses for an environment variable, with another one (or info) as fallback. */
  def levelOf(env: Map[String, String], variable: String, fallback: Option[String]): String =
    env.get(variable).orElse(fallback.flatMap(env.get)).flatMap(parseLevel).getOrElse("INFO")

  /**
   * Pekko logs through SLF4J and leaves the level decision to logback; this goes over the application.conf (also
   * the one the zones mount), so a mounted config cannot switch the routing or the levels back.
   */
  val pekkoLogging: Config = ConfigFactory.parseString(
    """pekko {
      |  loggers = ["org.apache.pekko.event.slf4j.Slf4jLogger"]
      |  logging-filter = "org.apache.pekko.event.slf4j.Slf4jLoggingFilter"
      |  loglevel = "DEBUG"
      |  stdout-loglevel = "OFF"
      |}""".stripMargin)
}

/** `<define>` in logback.xml: the level of the variable `env` (falling back to `fallbackEnv`, then info), as logback level. */
class LogLevelDefiner extends PropertyDefinerBase {
  private var variable = "LOG_LEVEL"
  private var fallback: Option[String] = None

  def setEnv(v: String): Unit = variable = v
  def setFallbackEnv(v: String): Unit = fallback = Option(v).filter(_.nonEmpty)

  override def getPropertyValue: String = LogSettings.levelOf(sys.env, variable, fallback)
}
