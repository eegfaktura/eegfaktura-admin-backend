package at.ourproject.keycloak

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class KeycloakClientSpec extends AnyWordSpecLike with Matchers {

  // buildUsername uses neither the admin client nor the config
  private val client = new KeycloakClient(null, null)

  "buildUsername" should {
    "take 6 characters of the last name and 2 of the first name" in {
      client.buildUsername("Maria", "Mustermann") shouldBe "MusterMa"
    }

    "normalise umlauts before cutting" in {
      client.buildUsername("Jörg", "Müllner") shouldBe "MuellnJö"
    }

    "not fail for a last name shorter than 6 characters" in {
      client.buildUsername("Anna", "Ott") shouldBe "OttAn"
    }

    "not fail for a first name shorter than 2 characters" in {
      client.buildUsername("A", "Huber") shouldBe "HuberA"
    }
  }

  "fallbackUsername" should {
    "take the first name and 2 characters of the last name" in {
      client.fallbackUsername("Maria", "Mustermann") shouldBe "MariaMu"
    }

    "not fail for a last name shorter than 2 characters" in {
      client.fallbackUsername("Maria", "O") shouldBe "MariaO"
    }
  }
}
