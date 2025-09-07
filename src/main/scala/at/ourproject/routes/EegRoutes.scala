package at.ourproject.routes

import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout
import at.ourproject.dao.Dao
import at.ourproject.json.JsonFormater._
import at.ourproject.services.RegisterService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import org.slf4j.Logger

import scala.concurrent.ExecutionContext

class EegRoutes (daos: Dao, node: ActorRef[RegisterService.Command])(implicit val system: ActorSystem[_], val ex: ExecutionContext) extends Router with TokenVerifier {
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  implicit val scheduler: Scheduler = system.scheduler
  implicit val materializer: Materializer = SystemMaterializer(system).materializer

  implicit def executionContext: ExecutionContext = system.executionContext

  override val log: Logger = system.log

  private val eegRoutes = {
      pathPrefix("vfeeg") {
        authorize { _ =>
          path("eeg") {
            get {
              complete(daos.eegRepository.getAll())
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
              complete(daos.operatorRepository.getAll())
            }
          }
        }
      }
  }

  override def route: Route = eegRoutes
}
