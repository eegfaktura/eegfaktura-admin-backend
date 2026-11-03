package at.ourproject.logging

import at.ourproject.testsupport.LogCapture
import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.bridge.SLF4JBridgeHandler

import java.nio.charset.StandardCharsets
import java.util.logging.{Level, Logger => JulLogger}

class JulBridgeSpec extends AnyWordSpec with Matchers {

  private val Name = "at.ourproject.logging.JulBridgeSpec.library"

  "a java.util.logging line" should {
    "come out through the EcsEncoder as JSON, with its exception, and FINE stays off" in {
      val root = JulLogger.getLogger("")
      val handlers = root.getHandlers.toList
      val encoder = new EcsEncoder
      encoder.setFormat("json")
      // ALL: a higher LOG_LEVEL must not be what keeps FINE out
      LogCapture(Name, Some(ch.qos.logback.classic.Level.ALL)) { capture =>
        try {
          LogSettings.bridgeJavaUtilLogging()
          root.getHandlers.toList.map(_.getClass) shouldBe List(classOf[SLF4JBridgeHandler])

          val library = JulLogger.getLogger(Name)
          library.log(Level.SEVERE, "library failed", new IllegalStateException("boom"))
          library.fine("frame dump")

          capture.events should have size 1
          val json = new ObjectMapper().readTree(new String(encoder.encode(capture.events.head), StandardCharsets.UTF_8))
          json.get("log.level").asText shouldBe "error"
          json.get("log.logger").asText shouldBe Name
          json.get("message").asText shouldBe "library failed"
          json.get("error.type").asText shouldBe "java.lang.IllegalStateException"
          json.get("error.stack_trace").asText should include("JulBridgeSpec")
        } finally {
          // the JVM's own java.util.logging handlers back, for the suites after this one
          SLF4JBridgeHandler.uninstall()
          handlers.foreach(root.addHandler)
        }
      }
    }
  }
}
