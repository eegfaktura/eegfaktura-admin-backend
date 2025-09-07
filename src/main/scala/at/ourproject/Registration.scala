package at.ourproject

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.server.Directives._
import at.ourproject.dao.{Dao, DbInstance}
import at.ourproject.keycloak.KeycloakClient
import at.ourproject.routes.{AdminRoutes, EegRoutes, RegistrationRoutes}
import at.ourproject.services.{AdminService, RegisterService}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext

object Registration extends App {

  implicit val log: Logger = LoggerFactory.getLogger(getClass)
  private val keycloakConfig = KeycloakConfig.keycloakAdminConfig
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

    val regRouter = new RegistrationRoutes(node)
    val eegRouter = new EegRoutes(daos, node)
    val adminRouter = new AdminRoutes(daos, masterNode)
    HttpServer.startHttpServer(regRouter.route ~ eegRouter.route ~ adminRouter.route)

    Registration.system.log.debug("Registration Server started ...")

    Behaviors.empty
  }

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](rootBehavior, "AdministrationServer")
}
