package at.ourproject.routes

import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import at.ourproject.logging.AccessLog
import com.nimbusds.jose.{JOSEException, JWSAlgorithm}
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.{BadJOSEException, JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.JWTClaimsSet
import org.slf4j.LoggerFactory

import java.net.URL
import java.util.Date
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

case class AuthenticatedUser(subject: String, preferredUsername: Option[String], claims: Map[String, Any])

object KeycloakJwtAuthenticator {
  /** The token check of every protected route; puts the token's client (azp) into the access line. */
  def authenticated(authenticator: Credentials => Future[Option[AuthenticatedUser]]): Directive1[AuthenticatedUser] =
    authenticateOAuth2Async(realm = "keycloak", authenticator = authenticator).flatMap { user =>
      AccessLog.client(user.claims.get("azp").map(String.valueOf)).tflatMap(_ => provide(user))
    }
}

class KeycloakJwtAuthenticator(jwksUrl: String, expectedIssuer: String, expectedAudience: String)(implicit ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)

  private val jwkSetUrl = new URL(jwksUrl)
  // RemoteJWKSet caches keys and handles refreshes (key rotation)
  private val jwkSource = new RemoteJWKSet[SecurityContext](jwkSetUrl)
  private val jwtProcessor = {
    val p = new DefaultJWTProcessor[SecurityContext]()
    val keySelector = new JWSVerificationKeySelector[SecurityContext](JWSAlgorithm.RS256, jwkSource)
    p.setJWSKeySelector(keySelector)
    p
  }

  // Validate token and return JWTClaimsSet on success
  private def validateAndGetClaims(token: String): Future[JWTClaimsSet] = Future {
    val signedJWT = SignedJWT.parse(token)

    // Log header fields (kid, alg)
    val kid = Option(signedJWT.getHeader.getKeyID).getOrElse("<no-kid>")
    val alg = Option(signedJWT.getHeader.getAlgorithm).map(_.getName).getOrElse("<no-alg>")
    log.debug("Parsed token header: kid={}, alg={}", kid, alg)

    // Inspect unverified claims for quick debugging (this doesn't verify signature)
    val unverifiedClaims = Option(signedJWT.getJWTClaimsSet)
    unverifiedClaims.foreach { c =>
      val iss = Option(c.getIssuer).getOrElse("")
      val aud = Option(c.getAudience).map(_.asScala.mkString(",")).getOrElse("")
      val exp = Option(c.getExpirationTime).map(_.toString).getOrElse("<none>")
      log.debug("Unverified claims: iss={}, aud={}, exp={}", iss, aud, exp)
    }

    try {
      // process will verify signature using JWKS (via the key selector) and return claims
      val claims = jwtProcessor.process(signedJWT, null)
      // basic claims checks
      val now = new Date()
      val exp = Option(claims.getExpirationTime)
        .getOrElse(throw new RuntimeException("exp claim is missing"))
      if (exp.before(now)) throw new RuntimeException(s"token expired at $exp (now=$now)")
      val iss = Option(claims.getIssuer).getOrElse("")
      if (!iss.equalsIgnoreCase(expectedIssuer)) throw new RuntimeException(s"invalid issuer: $iss (expected $expectedIssuer)")
      val aud = Option(claims.getAudience).getOrElse(java.util.Collections.emptyList()).asScala
      if (!aud.contains(expectedAudience)) throw new RuntimeException(s"invalid audience: $aud (expected $expectedAudience)")

      log.debug("Token validated successfully (kid={})", kid)
      claims
    } catch {
      case e: BadJOSEException =>
        // JOSE processing errors (invalid signature, malformed, expired if thrown by library)
        log.warn("BadJOSEException while validating token (kid={}): {}", kid, e.toString)
        throw e
      case e: JOSEException =>
        log.warn("JOSEException while validating token (kid={}): {}", kid, e.toString)
        throw e
      case e: Exception =>
        log.warn("Unexpected exception validating token (kid={}): {}", kid, e.toString)
        throw e
    }
  }

  // Extract user principal (adapt to your needs)
  private def claimsToUser(claims: JWTClaimsSet): AuthenticatedUser = {
    val subject = Option(claims.getSubject).getOrElse("")
    val preferredUsername = Option(claims.getStringClaim("preferred_username"))
    // Convert all claims to a Scala Map (careful with nested objects; keep them as Java Maps/lists)
    val allClaims = Option(claims.getClaims).map(_.asScala.toMap).getOrElse(Map.empty).view.mapValues(_.asInstanceOf[Any]).toMap
    AuthenticatedUser(subject, preferredUsername, allClaims)
  }

  // Authenticator used by Akka HTTP authenticateOAuth2Async
  def authenticate(credentials: Credentials): Future[Option[AuthenticatedUser]] = credentials match {
    case Credentials.Provided(token) =>
      validateAndGetClaims(token)
        .map(claims => Some(claimsToUser(claims)))
        .recover { case _ => None }
    case _ =>
      Future.successful(None)
  }

  // Helper: extract roles from standard Keycloak structure
  def extractRoles(claims: JWTClaimsSet): Seq[String] = {
    def asStringSeq(obj: Any): Seq[String] = obj match {
      case l: java.util.List[_] => l.asScala.toSeq.map(_.toString)
      case s: Seq[_]            => s.map(_.toString)
      case _                    => Seq.empty
    }

    val realmRoles: Seq[String] = Option(claims.getClaim("realm_access")).flatMap {
      case m: java.util.Map[_, _] =>
        Option(m.asInstanceOf[java.util.Map[String, _]].get("roles")).map(asStringSeq)
      case _ => None
    }.getOrElse(Seq.empty)

    val clientRoles: Seq[String] = Option(claims.getClaim("resource_access")).map {
      case m: java.util.Map[_, _] =>
        val resourceAccess = m.asInstanceOf[java.util.Map[String, _]]
        resourceAccess.values().asScala.toSeq.flatMap {
          case v: java.util.Map[_, _] =>
            Option(v.asInstanceOf[java.util.Map[String, _]].get("roles")).map(asStringSeq).getOrElse(Seq.empty)
          case _ => Seq.empty
        }
      case _ => Seq.empty
    }.getOrElse(Seq.empty)

    (realmRoles ++ clientRoles).distinct
  }
}
