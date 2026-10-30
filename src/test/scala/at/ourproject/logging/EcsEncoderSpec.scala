package at.ourproject.logging

import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.KeyValuePair

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

class EcsEncoderSpec extends AnyWordSpec with Matchers {

  private val logger = new LoggerContext().getLogger("at.ourproject.test")

  private def event(level: Level, message: String, error: Throwable = null, kv: Map[String, AnyRef] = Map.empty,
                    mdc: Map[String, String] = Map.empty): LoggingEvent = {
    val e = new LoggingEvent("fqcn", logger, level, message, error, null)
    kv.foreach { case (k, v) => e.addKeyValuePair(new KeyValuePair(k, v)) }
    e.setMDCPropertyMap(mdc.asJava)
    e.setTimeStamp(1767225600123L) // 2026-01-01T00:00:00.123Z
    e
  }

  private def encoder(format: String): EcsEncoder = {
    val enc = new EcsEncoder
    enc.setFormat(format)
    enc.setServiceVersion("v1.2.3")
    enc
  }

  private def line(format: String, e: LoggingEvent): String = new String(encoder(format).encode(e), StandardCharsets.UTF_8)

  private def json(e: LoggingEvent): JsonNode = {
    val l = line("json", e)
    l should endWith("\n")
    l.trim should not include "\n"
    new ObjectMapper().readTree(l)
  }

  "the json format" should {
    "write one object with the ECS fields" in {
      val j = json(event(Level.INFO, "started"))
      j.get("@timestamp").asText shouldBe "2026-01-01T00:00:00.123Z"
      j.get("log.level").asText shouldBe "info"
      j.get("log.logger").asText shouldBe "at.ourproject.test"
      j.get("service.name").asText shouldBe "eegfaktura-admin-backend"
      j.get("service.version").asText shouldBe "v1.2.3"
      j.get("message").asText shouldBe "started"
      j.fieldNames.asScala.toList shouldBe List("@timestamp", "log.level", "log.logger", "service.name", "service.version", "message")
    }

    "name the levels trace, debug, info, warn, error" in {
      Seq(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR).map(l => json(event(l, "x")).get("log.level").asText) shouldBe
        Seq("trace", "debug", "info", "warn", "error")
    }

    "keep the allowed fields, numbers as numbers, and drop all others" in {
      val j = json(event(Level.INFO, "GET /eeg/accounts 200",
        kv = Map("event.action" -> "http.access", "http.response.status_code" -> Integer.valueOf(200),
          "event.duration" -> java.lang.Long.valueOf(1234567L), "user.email" -> "max@example.org", "password" -> "geheim"),
        mdc = Map("tenant.id" -> "RC100200", "pekkoSource" -> "pekko://x", "sourceThread" -> "t1")))
      j.get("event.action").asText shouldBe "http.access"
      j.get("http.response.status_code").isInt shouldBe true
      j.get("event.duration").asLong shouldBe 1234567L
      j.get("tenant.id").asText shouldBe "RC100200"
      Seq("user.email", "password", "pekkoSource", "sourceThread").foreach(f => j.has(f) shouldBe false)
      j.toString should not include "example.org"
      j.toString should not include "geheim"
    }

    "put an exception into error.type, error.message and error.stack_trace of the same line" in {
      val j = json(event(Level.ERROR, "failed", new IllegalStateException("broken\nsecond line")))
      j.get("error.type").asText shouldBe "java.lang.IllegalStateException"
      j.get("error.message").asText shouldBe "broken\nsecond line"
      j.get("error.stack_trace").asText should include("EcsEncoderSpec")
    }

    "escape quotes and newlines in the message" in {
      json(event(Level.INFO, "a \"quoted\"\nline")).get("message").asText shouldBe "a \"quoted\"\nline"
    }
  }

  "the text format" should {
    "write a readable line with the extra fields as key=value" in {
      val l = line("text", event(Level.WARN, "refused", kv = Map("http.route" -> "/eeg/accounts", "client.id" -> "a b")))
      l shouldBe "2026-01-01T00:00:00.123Z warn  at.ourproject.test: refused http.route=/eeg/accounts client.id=\"a b\"\n"
    }
  }

  "an unknown format" should {
    "fall back to json (the start is refused before)" in {
      new ObjectMapper().readTree(line("xml", event(Level.INFO, "x"))).get("message").asText shouldBe "x"
    }
  }
}
