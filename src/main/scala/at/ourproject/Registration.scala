package at.ourproject

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import at.ourproject.keycloak.KeycloakClient
import at.ourproject.routes.RegistrationRoutes
import at.ourproject.services.RegisterService
import org.slf4j.{Logger, LoggerFactory}

object Registration extends App {

  implicit val log: Logger = LoggerFactory.getLogger(getClass)
  private val keycloakConfig = Config.config()
  val keycloakClient = KeycloakClient(keycloakConfig)

  val rootBehavior = Behaviors.setup[Nothing] { context =>

    val node = context.spawnAnonymous(RegisterService(keycloakClient))

    val router = new RegistrationRoutes(node)(context.system, context.executionContext)
    HttpServer.startHttpServer(router.route)(context.system)

    Behaviors.empty
  }

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](rootBehavior, "AdministrationServer")

//  import scala.jdk.CollectionConverters._

//  private val keycloakConfig = Config.config()
//  val keycloakClient = KeycloakClient(keycloakConfig)
//
//  println(keycloakClient.getUserRepresentation("d5dcd4e1-ba06-43a9-8dcd-9f3743264e55").getEmail)
//
////  keycloakClient.createUser(User("renatem", "renate", "Mustermann", "obermueller.p@aon.at"), "te100100")
//
//  val userGroups = List("EEG_ADMIN", "EEG_OWNER")
//
//  for (n <- keycloakClient.getGroups().filter(g => userGroups.find(ug => ug ==g.getName).isDefined)) println(n.getName)

}
