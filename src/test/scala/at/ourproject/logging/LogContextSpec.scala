package at.ourproject.logging

import at.ourproject.services.RegisterService
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.MDC

// The lines of a request through the real routers: AccessLogSpec, because the access logger is shared.
class LogContextSpec extends AnyWordSpec with Matchers {

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
