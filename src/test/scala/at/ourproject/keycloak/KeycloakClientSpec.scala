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

  "usernameCandidates" should {
    "start with the username and then append 2 to 9" in {
      KeycloakClient.usernameCandidates("MusterMa") shouldBe
        List("MusterMa", "MusterMa2", "MusterMa3", "MusterMa4", "MusterMa5", "MusterMa6", "MusterMa7", "MusterMa8", "MusterMa9")
    }
  }

  "hasTenant" should {
    "find the tenant in the JSON list, ignoring case" in {
      KeycloakClient.hasTenant("""["RC100001","GC100002-001"]""", "gc100002-001") shouldBe true
    }

    "not match a tenant that only starts the same" in {
      KeycloakClient.hasTenant("""["RC1000011"]""", "RC100001") shouldBe false
      KeycloakClient.hasTenant("""["GC100002-001"]""", "GC100002") shouldBe false
    }

    "be false for an attribute that is no JSON list" in {
      KeycloakClient.hasTenant("RC100001", "RC100001") shouldBe false
      KeycloakClient.hasTenant("", "RC100001") shouldBe false
    }
  }
}
