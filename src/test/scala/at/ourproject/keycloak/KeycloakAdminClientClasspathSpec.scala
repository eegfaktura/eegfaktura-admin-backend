package at.ourproject.keycloak

import jakarta.ws.rs.core.{MediaType, MultivaluedHashMap}
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.{JacksonProvider, KeycloakBuilder}
import org.keycloak.representations.idm.UserRepresentation
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

/**
 * Exercises the runtime classpath of the Keycloak admin client (RESTEasy + Jackson) without a Keycloak:
 * a missing class after a dependency change (platform#190) fails here instead of at the first admin call.
 * Offline: building the client and its resource proxies makes no network call.
 */
class KeycloakAdminClientClasspathSpec extends AnyWordSpec with Matchers {

  "the Keycloak admin client" should {
    "build like KeycloakClient.apply and create the resource proxies" in {
      val client = KeycloakBuilder.builder()
        .serverUrl("http://127.0.0.1:1")
        .realm("EEGFaktura")
        .clientId("test-client")
        .clientSecret("not-a-secret")
        .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
        .build()
      try {
        client.realm("EEGFaktura").users() should not be null
        client.realm("EEGFaktura").groups() should not be null
      } finally client.close()
    }

    "serialise a UserRepresentation with the admin client's Jackson provider" in {
      val user = new UserRepresentation()
      user.setUsername("ottan")
      user.setEnabled(true)
      user.setEmail("anna@test.local")
      user.setAttributes(Map("tenant" -> List("""["RC100100"]""").asJava).asJava)

      val out = new ByteArrayOutputStream()
      new JacksonProvider().writeTo(user, classOf[UserRepresentation], classOf[UserRepresentation], Array.empty,
        MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap[String, AnyRef](), out)

      val json = io.circe.parser.parse(new String(out.toByteArray, StandardCharsets.UTF_8)).toOption.get
      val c = json.hcursor
      c.get[String]("username") shouldBe Right("ottan")
      c.get[Boolean]("enabled") shouldBe Right(true)
      c.get[String]("email") shouldBe Right("anna@test.local")
      c.downField("attributes").get[List[String]]("tenant") shouldBe Right(List("""["RC100100"]"""))
    }
  }
}
