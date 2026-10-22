package at.ourproject.routes

import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.{MACSigner, RSASSASigner}
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import com.sun.net.httpserver.HttpServer
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Date
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

// Regression test for the bearer token checks (platform#183): the JWKS is served from a local
// HTTP server on 127.0.0.1, no other network access.
class KeycloakJwtAuthenticatorSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val issuer = "https://keycloak.test/realms/test"
  private val audience = "account"
  private val signingKeyPair: RSAKey = new RSAKeyGenerator(2048).keyID("k1").generate()
  // another key pair with the same kid: its signature must not verify against the published key
  private val foreignKey: RSAKey = new RSAKeyGenerator(2048).keyID("k1").generate()

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext("/jwks", exchange => {
    val body = new JWKSet(signingKeyPair.toPublicJWK).toString.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, body.length)
    exchange.getResponseBody.write(body)
    exchange.close()
  })
  server.start()

  private val authenticator =
    new KeycloakJwtAuthenticator(s"http://127.0.0.1:${server.getAddress.getPort}/jwks", issuer, audience)

  override def afterAll(): Unit = server.stop(0)

  private def inOneMinute = new Date(System.currentTimeMillis() + 60000)

  private def claims(exp: Option[Date] = Some(inOneMinute), iss: String = issuer, aud: String = audience) = {
    val builder = new JWTClaimsSet.Builder()
      .subject("user-1").issuer(iss).audience(aud).claim("preferred_username", "user")
    exp.foreach(builder.expirationTime)
    builder.build()
  }

  private def signRsa(c: JWTClaimsSet, signingKey: RSAKey = signingKeyPair, alg: JWSAlgorithm = JWSAlgorithm.RS256) = {
    val jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID("k1").build(), c)
    jwt.sign(new RSASSASigner(signingKey))
    jwt.serialize()
  }

  private def authenticate(token: String) =
    Await.result(authenticator.authenticate(Credentials(Some(OAuth2BearerToken(token)))), 10.seconds)

  "KeycloakJwtAuthenticator" should {
    "accept a valid RS256 token" in {
      val user = authenticate(signRsa(claims()))
      user.map(_.subject) shouldBe Some("user-1")
      user.flatMap(_.preferredUsername) shouldBe Some("user")
    }
    "reject an expired token" in {
      authenticate(signRsa(claims(exp = Some(new Date(System.currentTimeMillis() - 120000))))) shouldBe None
    }
    "reject a token without exp" in {
      authenticate(signRsa(claims(exp = None))) shouldBe None
    }
    "reject a wrong issuer" in {
      authenticate(signRsa(claims(iss = "https://other.test/realms/test"))) shouldBe None
    }
    "reject a wrong audience" in {
      authenticate(signRsa(claims(aud = "other"))) shouldBe None
    }
    "reject a token signed with a foreign key" in {
      authenticate(signRsa(claims(), signingKey = foreignKey)) shouldBe None
    }
    "reject RS512" in {
      authenticate(signRsa(claims(), alg = JWSAlgorithm.RS512)) shouldBe None
    }
    "reject HS256" in {
      val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), claims())
      jwt.sign(new MACSigner(Array.fill[Byte](32)(7)))
      authenticate(jwt.serialize()) shouldBe None
    }
    "reject alg=none" in {
      authenticate(new PlainJWT(claims()).serialize()) shouldBe None
    }
    "reject a malformed token" in {
      authenticate("abc.def.ghi") shouldBe None
    }
    "reject missing credentials" in {
      Await.result(authenticator.authenticate(Credentials(None)), 10.seconds) shouldBe None
    }
  }
}
