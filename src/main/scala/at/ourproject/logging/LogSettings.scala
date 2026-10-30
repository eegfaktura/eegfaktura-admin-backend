package at.ourproject.logging

import ch.qos.logback.core.PropertyDefinerBase
import com.typesafe.config.{Config, ConfigFactory}

// The one logging setup of the service (eegfaktura-dev logging concept W-261008-ca2): every line goes through
// logback.xml and the EcsEncoder there - the application's SLF4J loggers and Pekko's (Slf4jLogger, see `pekkoLogging`).
//
//   LOG_LEVEL      trace | debug | info | warn | error (default info; `warning` is read as warn) - root level
//   LOG_LEVEL_APP  the same values, level of the application loggers at.ourproject.* (default: LOG_LEVEL)
//   LOG_LEVEL_HTTP_CLIENT  the same values, level of the Keycloak admin client's HTTP stack (org.apache.http,
//                  org.jboss.resteasy; default warn, not raised by LOG_LEVEL: its debug lines carry client secrets
//                  and tokens). Its raw traffic (org.apache.http.wire, .headers) stays off in any case.
//   LOG_FORMAT     json | text (default json, also from source; text for a readable line locally)
//   SERVICE_VERSION  set by the image build, otherwise `dev`
//
// An empty value is the same as an unset one (the default applies). An unknown level or format stops the start
// (`validate`), logback alone would fall back to DEBUG.
object LogSettings {

  val ServiceName = "eegfaktura-admin-backend"

  private val Levels = Map(
    "trace" -> "TRACE", "debug" -> "DEBUG", "info" -> "INFO", "warn" -> "WARN", "warning" -> "WARN", "error" -> "ERROR")
  val Formats: Set[String] = Set("json", "text")

  /** The logback level name for a LOG_LEVEL value, None when it is not one of the allowed values. */
  def parseLevel(value: String): Option[String] = Levels.get(value.trim.toLowerCase)

  def parseFormat(value: String): Option[String] = Some(value.trim.toLowerCase).filter(Formats)

  /** The value of a variable; unset and empty are the same (None). */
  private def valueOf(env: Map[String, String], name: String): Option[String] = env.get(name).filter(_.trim.nonEmpty)

  /** The reason the start is refused, if it is. */
  def validate(env: Map[String, String]): Either[String, Unit] = {
    val levels = Seq("LOG_LEVEL", "LOG_LEVEL_APP", "LOG_LEVEL_HTTP_CLIENT").flatMap(name => valueOf(env, name).map(name -> _))
      .collectFirst { case (name, v) if parseLevel(v).isEmpty =>
        s"$name='$v' is not one of trace, debug, info, warn, error" }
    val format = valueOf(env, "LOG_FORMAT").filter(parseFormat(_).isEmpty).map(v => s"LOG_FORMAT='$v' is not one of json, text")
    levels.orElse(format).toLeft(())
  }

  /** The level logback.xml uses for an environment variable, with another one (or `default`) as fallback. */
  def levelOf(env: Map[String, String], variable: String, fallback: Option[String], default: String = "INFO"): String =
    valueOf(env, variable).orElse(fallback.flatMap(valueOf(env, _))).flatMap(parseLevel).getOrElse(default)

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

/**
 * `<define>` in logback.xml: the level of the variable `env` (falling back to `fallbackEnv`, then `defaultLevel`,
 * info), as logback level. A logback context property of the same name comes before the environment, as in logback's
 * own `${...}` (the tests configure a context this way).
 */
class LogLevelDefiner extends PropertyDefinerBase {
  private var variable = "LOG_LEVEL"
  private var fallback: Option[String] = None
  private var default = "INFO"

  def setEnv(v: String): Unit = variable = v
  def setFallbackEnv(v: String): Unit = fallback = Option(v).filter(_.nonEmpty)
  def setDefaultLevel(v: String): Unit = default = LogSettings.parseLevel(v).getOrElse("INFO")

  override def getPropertyValue: String = {
    val env = (variable +: fallback.toSeq)
      .flatMap(name => Option(getContext.getProperty(name)).orElse(sys.env.get(name)).map(name -> _)).toMap
    LogSettings.levelOf(env, variable, fallback, default)
  }
}
