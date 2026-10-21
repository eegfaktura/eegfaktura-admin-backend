package at.ourproject

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class KeycloakConfigSpec extends AnyWordSpecLike with Matchers {

  private def conf(secretLine: String) = ConfigFactory.parseString(
    s"""keycloak {
       |  url = "http://localhost:7080/"
       |  realm = "EEGFaktura"
       |  clientId = "admin-cli"
       |  $secretLine
       |}""".stripMargin)

  "adminConfigFrom" should {
    "fail when the secret is missing" in {
      an[IllegalStateException] should be thrownBy KeycloakConfig.adminConfigFrom(conf(""))
    }

    "fail when the secret is empty" in {
      an[IllegalStateException] should be thrownBy KeycloakConfig.adminConfigFrom(conf("secret = \"  \""))
    }

    "use the configured secret" in {
      KeycloakConfig.adminConfigFrom(conf("secret = \"test-only\"")).auth.clientSecret shouldBe "test-only"
    }

    "not ship a default secret in application.conf" in {
      ConfigFactory.parseResources("application.conf").resolve(ConfigResolveOptions.noSystem()).hasPath("keycloak.secret") shouldBe false
    }
  }
}
