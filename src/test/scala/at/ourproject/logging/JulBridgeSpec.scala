package at.ourproject.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger => LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

import java.nio.charset.StandardCharsets
import java.util.logging.{Level, Logger => JulLogger}
import scala.jdk.CollectionConverters._

object JulBridgeSpec {
  /** A logback logger; while another suite is the first to use SLF4J, the factory hands out placeholders. */
  def logbackLogger(name: String): LogbackLogger =
    Iterator.continually(LoggerFactory.getLogger(name)).collectFirst { case logger: LogbackLogger => logger }.get
}

// The tests share a JVM with sbt: the root handlers of java.util.logging are put back afterwards.
class JulBridgeSpec extends AnyWordSpec with Matchers {

  private val Name = "at.ourproject.logging.JulBridgeSpec.library"

  "a java.util.logging line" should {
    "come out through the EcsEncoder as JSON, with its exception, and FINE stays off" in {
      val root = JulLogger.getLogger("")
      val handlers = root.getHandlers.toList
      val appender = new ListAppender[ILoggingEvent]
      appender.start()
      val logger = JulBridgeSpec.logbackLogger(Name)
      logger.addAppender(appender)
      val previous = logger.getLevel
      logger.setLevel(ch.qos.logback.classic.Level.ALL) // a higher LOG_LEVEL must not be what keeps FINE out

      val encoder = new EcsEncoder
      encoder.setFormat("json")
      try {
        LogSettings.bridgeJavaUtilLogging()
        root.getHandlers.toList.map(_.getClass) shouldBe List(classOf[SLF4JBridgeHandler])

        val library = JulLogger.getLogger(Name)
        library.log(Level.SEVERE, "library failed", new IllegalStateException("boom"))
        library.fine("frame dump")

        val lines = appender.list.asScala.toList
        lines should have size 1
        val json = new ObjectMapper().readTree(new String(encoder.encode(lines.head), StandardCharsets.UTF_8))
        json.get("log.level").asText shouldBe "error"
        json.get("log.logger").asText shouldBe Name
        json.get("message").asText shouldBe "library failed"
        json.get("error.type").asText shouldBe "java.lang.IllegalStateException"
        json.get("error.stack_trace").asText should include("JulBridgeSpec")
      } finally {
        SLF4JBridgeHandler.uninstall()
        handlers.foreach(root.addHandler)
        logger.setLevel(previous)
        logger.detachAppender(appender)
      }
    }
  }
}
