package at.ourproject.routes

import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives.{extractCredentials, onComplete, provide, reject}
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Directive1}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import at.ourproject.KeycloakConfig
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import io.circe.parser._
import org.keycloak.adapters.{KeycloakDeployment, KeycloakDeploymentBuilder}
import org.keycloak.jose.jws.AlgorithmType
import org.keycloak.representations.AccessToken
import org.keycloak.{TokenVerifier => KCTokenVerifier}
import org.slf4j.Logger

import java.io.InputStream
import java.math.BigInteger
import java.security.spec.RSAPublicKeySpec
import java.security.{KeyFactory, PublicKey}
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success


trait TokenVerifier {

  implicit def ex: ExecutionContext
  implicit def materializer: Materializer
  implicit def system: ClassicActorSystemProvider

  def log: Logger
//  val keycloakDeployment: KeycloakDeployment =
//    KeycloakDeploymentBuilder.build(getClass.getResourceAsStream("/keycloak.json"))
//
//  def authorizedEeg: Directive1[AccessToken] = {
//    bearerToken.flatMap {
//      case Some(token) =>
//        Directives
//          .onComplete(Future {
//            AdapterTokenVerifier.verifyToken(token, keycloakDeployment)
//          })
//          .flatMap {
//            provide(AdapterTokenVerifier.verifyToken(token, keycloakDeployment))
//            _.map(accessToken => provide(accessToken)).recover {
//              case e =>
//                log.error(
//                  "Couldn't log in using provided authorization token", e
//                )
//                reject(AuthorizationFailedRejection)
//                  .toDirective[Tuple1[AccessToken]]
//            }.get
//          }
//      case None =>
//        reject(AuthorizationFailedRejection)
//    }
//  }
//
//  /**
//   * Obtain Bearer Token from Authentication Header.
//   * Fallback to X-Authorization-Token Cookie on failure.
//   *
//   * @return
//   * The Bearer Token.
//   */
//  private def bearerToken: Directive1[Option[String]] = {
//    for {
//      authBearerHeader <- optionalHeaderValueByType(classOf[Authorization])
//        .map(
//          authHeader =>
//            authHeader.collect {
//              case Authorization(OAuth2BearerToken(token)) => token
//            }
//        )
//      xAuthCookie <- optionalCookie("X-Authorization-Token")
//        .map(_.map(cookie => cookie.value))
//    } yield authBearerHeader.orElse(xAuthCookie)
//  }

  def authorize: Directive1[AccessToken] =
    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(token)) =>
        onComplete(verifyToken(token)).flatMap {
          case Success(Some(t)) =>
            provide(t)
          case _ =>
            log.warn(s"token $token is not valid")
            reject(AuthorizationFailedRejection)
        }
      case _ =>
        log.warn("no token present in request")
        reject(AuthorizationFailedRejection)
    }

//  def keycloakConfig: InputStream = {
//    val jsonString: String = scala.io.Source.fromFile(AppConfig.keycloakFile).mkString
//    val parseResult: Either[ParsingFailure, Json] = parse {
//      jsonString
//    }
//    parseResult match {
//      case Left(parsingError) =>
//        throw new IllegalArgumentException(s"Invalid JSON object: ${parsingError.message}")
//      case Right(json) =>
//        new ByteArrayInputStream((json \\ "admin").head.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8.name))
//    }
//  }
  def keycloakConfig: InputStream = KeycloakConfig.keycloakConfigStream

//  val keycloakDeployment: KeycloakDeployment =
//    KeycloakDeploymentBuilder.build(getClass.getResourceAsStream("/keycloak.json"))
  val keycloakDeployment: KeycloakDeployment =
    KeycloakDeploymentBuilder.build(keycloakConfig)


  def verifyToken(token: String): Future[Option[AccessToken]] = {
    val tokenVerifier = KCTokenVerifier.create(token, classOf[AccessToken]) //.realmUrl(keycloakDeployment.getRealmInfoUrl)
    for {
      publicKey <- publicKeys.map(_.get(tokenVerifier.getHeader.getKeyId))
    } yield publicKey match {
      case Some(publicKey) =>
        val token = tokenVerifier.publicKey(publicKey).verify().getToken
        Some(token)
      case None =>
        log.warn(s"no public key found for id ${tokenVerifier.getHeader.getKeyId}")
        None
    }
  }

  case class Keys(keys: Seq[KeyData])
  case class KeyData(kid: String, n: String, e: String)

  lazy val publicKeys: Future[Map[String, PublicKey]] =
    Http().singleRequest(HttpRequest(uri = keycloakDeployment.getJwksUrl)).flatMap(response => {
      Unmarshal(response).to[Keys].map(_.keys.map(k => (k.kid, generateKey(k))).toMap)
    })

  private def generateKey(keyData: KeyData): PublicKey = {
    val keyFactory = KeyFactory.getInstance(AlgorithmType.RSA.toString)
    val urlDecoder = Base64.getUrlDecoder
    val modulus = new BigInteger(1, urlDecoder.decode(keyData.n))
    val publicExponent = new BigInteger(1, urlDecoder.decode(keyData.e))
    keyFactory.generatePublic(new RSAPublicKeySpec(modulus, publicExponent))
  }
}
