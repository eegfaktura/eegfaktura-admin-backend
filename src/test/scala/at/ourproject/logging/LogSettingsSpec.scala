package at.ourproject.logging

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LogSettingsSpec extends AnyWordSpec with Matchers {

  "parseLevel" should {
    "accept the five levels in any case, and warning as warn" in {
      Seq("trace", "DEBUG", " Info ", "warn", "WARNING", "error").map(LogSettings.parseLevel) shouldBe
        Seq(Some("TRACE"), Some("DEBUG"), Some("INFO"), Some("WARN"), Some("WARN"), Some("ERROR"))
    }

    "refuse anything else" in {
      Seq("", "off", "all", "verbose", "inf").flatMap(LogSettings.parseLevel) shouldBe empty
    }
  }

  "validate" should {
    "accept no settings and valid settings" in {
      LogSettings.validate(Map.empty) shouldBe Right(())
      LogSettings.validate(Map("LOG_LEVEL" -> "debug", "LOG_LEVEL_APP" -> "TRACE", "LOG_FORMAT" -> "JSON")) shouldBe Right(())
    }

    "accept empty values as unset (the defaults apply)" in {
      LogSettings.validate(Map("LOG_LEVEL" -> "", "LOG_LEVEL_APP" -> " ", "LOG_FORMAT" -> "")) shouldBe Right(())
    }

    "refuse an unknown level or format, naming the variable" in {
      LogSettings.validate(Map("LOG_LEVEL" -> "verbose")).left.toOption.get should include("LOG_LEVEL='verbose'")
      LogSettings.validate(Map("LOG_LEVEL_APP" -> "OFF")).left.toOption.get should include("LOG_LEVEL_APP='OFF'")
      LogSettings.validate(Map("LOG_FORMAT" -> "xml")).left.toOption.get should include("LOG_FORMAT='xml'")
    }
  }

  "levelOf" should {
    "take the variable, then the fallback variable, then info" in {
      val env = Map("LOG_LEVEL" -> "warn", "LOG_LEVEL_APP" -> "debug")
      LogSettings.levelOf(env, "LOG_LEVEL_APP", Some("LOG_LEVEL")) shouldBe "DEBUG"
      LogSettings.levelOf(env - "LOG_LEVEL_APP", "LOG_LEVEL_APP", Some("LOG_LEVEL")) shouldBe "WARN"
      LogSettings.levelOf(Map.empty, "LOG_LEVEL", None) shouldBe "INFO"
    }

    "read an empty value as unset" in {
      LogSettings.levelOf(Map("LOG_LEVEL" -> "warn", "LOG_LEVEL_APP" -> ""), "LOG_LEVEL_APP", Some("LOG_LEVEL")) shouldBe "WARN"
      LogSettings.levelOf(Map("LOG_LEVEL" -> ""), "LOG_LEVEL", None) shouldBe "INFO"
    }
  }
}
