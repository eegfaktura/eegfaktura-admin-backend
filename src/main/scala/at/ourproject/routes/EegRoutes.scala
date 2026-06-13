package at.ourproject.routes

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.apache.pekko.util.Timeout
import at.ourproject.dao.Dao
import at.ourproject.json.JsonFormater._
import at.ourproject.services.RegisterService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import org.slf4j.Logger

import scala.concurrent.{ExecutionContext, Future}

class EegRoutes (daos: Dao, akkaAuthenticator: Credentials => Future[Option[AuthenticatedUser]], node: ActorRef[RegisterService.Command])(implicit val system: ActorSystem[_], val ex: ExecutionContext) extends Router {
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  implicit val scheduler: Scheduler = system.scheduler
  implicit val materializer: Materializer = SystemMaterializer(system).materializer

  implicit def executionContext: ExecutionContext = system.executionContext

  private val log: Logger = system.log

  private val eegRoutes = {
      pathPrefix("vfeeg") {
        authenticateOAuth2Async(realm = "keycloak", authenticator = akkaAuthenticator) { user: AuthenticatedUser =>
          path("eeg") {
            get {
              complete(daos.eegRepository.getAll)
            }
          } ~
          path("participants") {
            get {
              parameters("tenant") { tenant =>
                complete(daos.participantRepository.getFullParticipantByTenant(tenant))
              }
            }
          } ~
          path("operators") {
            get {
              complete(daos.operatorRepository.getAll)
            }
          }
        }
      }
  }

  override def route: Route = eegRoutes
}
