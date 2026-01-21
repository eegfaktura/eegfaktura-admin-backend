package at.ourproject

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.Credentials
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
//    import log._

    implicit val system: ActorSystem[Nothing] = context.system

    val daos = new Dao with DbInstance {
      override implicit def executionContext: ExecutionContext = context.executionContext
    }

    val node = context.spawnAnonymous(RegisterService(keycloakClient))
    val masterNode = context.spawnAnonymous(AdminService())

    val authenticator = new KeycloakJwtAuthenticator(
      s"${keycloakJWTConfig.auth.url.stripSuffix("/")}/realms/${keycloakJWTConfig.auth.realm}/protocol/openid-connect/certs",
      s"${keycloakJWTConfig.auth.url.stripSuffix("/")}/realms/${keycloakJWTConfig.auth.realm}",
      keycloakJWTConfig.auth.clientId
    )

    val akkaAuthenticator: Credentials => scala.concurrent.Future[Option[AuthenticatedUser]] =
      cred => authenticator.authenticate(cred)

    val regRouter = new RegistrationRoutes(akkaAuthenticator, node)
    val eegRouter = new EegRoutes(daos, authenticator, node)
    val adminRouter = new AdminRoutes(daos, authenticator, masterNode)
    HttpServer.startHttpServer(regRouter.route ~ eegRouter.route ~ adminRouter.route)

    Registration.system.log.debug("Registration Server started ...")

    Behaviors.empty
  }

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](rootBehavior, "AdministrationServer")
}
