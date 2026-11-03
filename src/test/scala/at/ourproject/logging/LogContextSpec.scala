package at.ourproject.logging

import at.ourproject.services.RegisterService
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.MDC

// The lines of a request through the real routers: AccessLogSpec, because the access logger is shared.
class LogContextSpec extends AnyWordSpec with Matchers {

  "AccessLog.tenantId" should {
    "upper-case a tenant and keep RC, TE and GEA numbers" in {
      AccessLog.tenantId(" rc100200 ") shouldBe Some("RC100200")
      AccessLog.tenantId("TE100002") shouldBe Some("TE100002")
      AccessLog.tenantId("GC123456-001") shouldBe Some("GC123456-001")
      AccessLog.tenantId("a_b") shouldBe Some("A_B")
      AccessLog.tenantId("A" * 64) shouldBe Some("A" * 64)
    }

    "refuse every other shape" in {
      Seq("", "-RC1", "_RC1", "RC.1", "RC:1", "RC 1", "A" * 65, "max@example.org")
        .foreach(v => withClue(v)(AccessLog.tenantId(v) shouldBe None))
      AccessLog.tenantId(null) shouldBe None
    }
  }

  "LogContext.scoped" should {
    "leave the thread as it found it" in {
      MDC.put("request.id", "outer")
      try {
        LogContext.scoped(Map("tenant.id" -> "RC1")) {
          LogContext.current shouldBe Map("tenant.id" -> "RC1")
          LogContext.put(Map("request.id" -> "inner"))
        }
        LogContext.current shouldBe Map("request.id" -> "outer")
      } finally MDC.clear()
    }
  }

  "a message created outside a request" should {
    "carry no fields" in {
      RegisterService.ListAccountsResponse(200, Nil).logFields shouldBe empty
    }
  }
}
