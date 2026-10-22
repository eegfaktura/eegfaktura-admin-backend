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
import java.text.ParseException
import java.util.Date
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

// Regression test for the bearer token checks (platform#183): the JWKS is served from a local
// HTTP server on 127.0.0.1, no other network access. Every negative case is the known-good token
// with exactly one thing changed, and each case first checks that the unchanged token is accepted,
// so a broken JWKS fetch or signature path cannot make the negative cases pass on their own.
class KeycloakJwtAuthenticatorSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val issuer = "https://keycloak.test/realms/test"
  private val audience = "account"
  private val keyId = "k1"
  private val signingKeyPair: RSAKey = new RSAKeyGenerator(2048).keyID(keyId).generate()
  // another key pair with the same kid: its signature must not verify against the published key
  private val foreignKey: RSAKey = new RSAKeyGenerator(2048).keyID(keyId).generate()

  private var server: HttpServer = _
  private var authenticator: KeycloakJwtAuthenticator = _

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/jwks", exchange => {
      val body = new JWKSet(signingKeyPair.toPublicJWK).toString.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, body.length)
      exchange.getResponseBody.write(body)
      exchange.close()
    })
    server.start()
    authenticator =
      new KeycloakJwtAuthenticator(s"http://127.0.0.1:${server.getAddress.getPort}/jwks", issuer, audience)
    // RemoteJWKSet fetches with 500 ms timeouts (not configurable from outside): warm the server and
    // the key cache, so a slow first request on a cold runner does not fail a test.
    val warm = (1 to 5).iterator.map(_ => authenticate(goodToken())).find(_.isDefined).flatten
    assert(warm.isDefined, "the known-good token is not accepted - JWKS not reachable?")
  }

  override def afterAll(): Unit = if (server != null) server.stop(0)

  private def inOneMinute = new Date(System.currentTimeMillis() + 60000)
  private def secondsAgo(s: Int) = new Date(System.currentTimeMillis() - s * 1000L)

  private def goodClaims: JWTClaimsSet.Builder = new JWTClaimsSet.Builder()
    .subject("user-1").issuer(issuer).audience(audience).expirationTime(inOneMinute)
    .claim("preferred_username", "user")

  private def sign(claims: JWTClaimsSet, key: RSAKey = signingKeyPair, alg: JWSAlgorithm = JWSAlgorithm.RS256,
                   kid: String = keyId): String = {
    val jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(kid).build(), claims)
    jwt.sign(new RSASSASigner(key))
    jwt.serialize()
  }

  private def goodToken(): String = sign(goodClaims.build())

  private def authenticate(token: String) =
    Await.result(authenticator.authenticate(Credentials(Some(OAuth2BearerToken(token)))), 10.seconds)

  // The unchanged token is accepted, the changed one is rejected.
  private def rejects(changed: String) = {
    authenticate(goodToken()) should not be empty
    authenticate(changed) shouldBe None
  }

  "KeycloakJwtAuthenticator" should {
    "accept a valid RS256 token" in {
      val user = authenticate(goodToken())
      user.map(_.subject) shouldBe Some("user-1")
      user.flatMap(_.preferredUsername) shouldBe Some("user")
    }
    "accept the issuer case-insensitively (as the service compares it)" in {
      authenticate(sign(goodClaims.issuer(issuer.toUpperCase).build())).map(_.subject) shouldBe Some("user-1")
    }
    // nimbus' own check allows 60 s clock skew; a token expired 30 s ago is only rejected by the
    // service's zero-tolerance exp check
    "reject a token expired 30 seconds ago" in {
      rejects(sign(goodClaims.expirationTime(secondsAgo(30)).build()))
    }
    "reject a token expired long ago" in {
      rejects(sign(goodClaims.expirationTime(secondsAgo(3600)).build()))
    }
    "reject a token without exp" in {
      rejects(sign(goodClaims.expirationTime(null).build()))
    }
    "reject a wrong issuer" in {
      rejects(sign(goodClaims.issuer("https://other.test/realms/test").build()))
    }
    "reject a wrong audience" in {
      rejects(sign(goodClaims.audience("other").build()))
    }
    "reject a token signed with a foreign key" in {
      rejects(sign(goodClaims.build(), key = foreignKey))
    }
    "reject an unknown kid" in {
      rejects(sign(goodClaims.build(), kid = "unknown"))
    }
    "reject RS512" in {
      rejects(sign(goodClaims.build(), alg = JWSAlgorithm.RS512))
    }
    "reject HS256 signed with the published RSA public key (key confusion)" in {
      val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keyId).build(), goodClaims.build())
      jwt.sign(new MACSigner(signingKeyPair.toRSAPublicKey.getEncoded))
      rejects(jwt.serialize())
    }
    "reject alg=none" in {
      val token = new PlainJWT(goodClaims.build()).serialize()
      // rejected already at SignedJWT.parse: "none" is no JWS algorithm
      a[ParseException] should be thrownBy SignedJWT.parse(token)
      rejects(token)
    }
    "reject a malformed token" in {
      rejects("abc.def.ghi")
    }
    "reject missing credentials" in {
      authenticate(goodToken()) should not be empty
      Await.result(authenticator.authenticate(Credentials(None)), 10.seconds) shouldBe None
    }
  }
}
