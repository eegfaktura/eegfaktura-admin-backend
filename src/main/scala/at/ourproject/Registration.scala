package at.ourproject

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import at.ourproject.dao.{Dao, DbInstance}
import at.ourproject.keycloak.KeycloakClient
import at.ourproject.routes.{AdminRoutes, AuthenticatedUser, EegRoutes, KeycloakJwtAuthenticator, RegistrationRoutes}
import at.ourproject.services.{AdminService, RegisterService}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext

object Registration extends App {

  implicit val log: Logger = LoggerFactory.getLogger(getClass)
  private val keycloakConfig = KeycloakConfig.keycloakAdminConfig
  private val keycloakJWTConfig = KeycloakConfig.keycloakJWTConfig
  val keycloakClient = KeycloakClient(keycloakConfig)

  val rootBehavior = Behaviors.setup[Nothing] { context =>

    import context._
    implicit val system: ActorSystem[Nothing] = context.system

    val daos = new Dao with DbInstance {
      override implicit def executionContext: ExecutionContext = context.executionContext
    }

    val node = context.spawnAnonymous(RegisterService(keycloakClient))
    val masterNode = context.spawnAnonymous(AdminService())

    val issuer = s"${keycloakJWTConfig.auth.url.stripSuffix("/")}/realms/${keycloakJWTConfig.auth.realm}"
    // JWKS-Fetch-URL optional getrennt vom Issuer: in Prod muss der Issuer die
    // oeffentliche Keycloak-URL sein (iss-Check), der JWKS-Fetch aber cluster-
    // intern laufen (LB-Hairpin + ingress-TLS). Ohne Override = aus dem Issuer
    // abgeleitet (bisheriges Verhalten, Dev unveraendert).
    val jwksUrl =
      if (KeycloakConfig.config.hasPath("keycloakAuthenticator.jwksUrl"))
        KeycloakConfig.config.getString("keycloakAuthenticator.jwksUrl")
      else s"$issuer/protocol/openid-connect/certs"

    val authenticator = new KeycloakJwtAuthenticator(
      jwksUrl,
      issuer,
      keycloakJWTConfig.auth.clientId
    )

    val akkaAuthenticator: Credentials => scala.concurrent.Future[Option[AuthenticatedUser]] =
      cred => authenticator.authenticate(cred)

    val regRouter = new RegistrationRoutes(akkaAuthenticator, node)
    val eegRouter = new EegRoutes(daos, akkaAuthenticator, node)
    val adminRouter = new AdminRoutes(daos, akkaAuthenticator, masterNode)
    HttpServer.startHttpServer(regRouter.route ~ eegRouter.route ~ adminRouter.route)

    Registration.system.log.info("Registration Server started ...")

    Behaviors.empty
  }

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](rootBehavior, "AdministrationServer")
}
