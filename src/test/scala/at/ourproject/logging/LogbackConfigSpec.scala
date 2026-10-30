package at.ourproject.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

// The shipped logback.xml, configured into a fresh context; the variables are given as context properties, which
// come before the environment (logback's `${...}` and LogLevelDefiner).
class LogbackConfigSpec extends AnyWordSpec with Matchers {

  private def withConfig(variables: (String, String)*)(check: LoggerContext => Unit): Unit = {
    val ctx = new LoggerContext
    variables.foreach { case (k, v) => ctx.putProperty(k, v) }
    val configurator = new JoranConfigurator
    configurator.setContext(ctx)
    configurator.doConfigure(getClass.getResource("/logback.xml"))
    try check(ctx) finally ctx.stop()
  }

  private def debug(ctx: LoggerContext, name: String) = ctx.getLogger(name).isDebugEnabled
  private def error(ctx: LoggerContext, name: String) = ctx.getLogger(name).isErrorEnabled

  "the Keycloak admin client's HTTP loggers" should {
    "stay at warn and the raw traffic off at LOG_LEVEL=debug" in withConfig("LOG_LEVEL" -> "debug", "LOG_LEVEL_APP" -> "debug") { ctx =>
      debug(ctx, "org.apache.pekko") shouldBe true // LOG_LEVEL reaches the root
      error(ctx, "org.apache.http.wire") shouldBe false
      error(ctx, "org.apache.http.headers") shouldBe false
      debug(ctx, "org.apache.http") shouldBe false
      debug(ctx, "org.apache.http.impl.conn.PoolingHttpClientConnectionManager") shouldBe false
      ctx.getLogger("org.apache.http").isWarnEnabled shouldBe true
      debug(ctx, "org.jboss.resteasy") shouldBe false
    }

    "not be raised by log_slick_action" in withConfig("LOG_LEVEL" -> "debug", "log_slick_action" -> "DEBUG") { ctx =>
      error(ctx, "org.apache.http.wire") shouldBe false
      debug(ctx, "org.apache.http") shouldBe false
      debug(ctx, "org.jboss.resteasy") shouldBe false
    }

    "follow LOG_LEVEL_HTTP_CLIENT, with the raw traffic still off" in withConfig("LOG_LEVEL_HTTP_CLIENT" -> "debug") { ctx =>
      debug(ctx, "org.apache.http") shouldBe true
      debug(ctx, "org.jboss.resteasy") shouldBe true
      error(ctx, "org.apache.http.wire") shouldBe false
      error(ctx, "org.apache.http.headers") shouldBe false
    }
  }
}
