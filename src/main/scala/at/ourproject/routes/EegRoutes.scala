package at.ourproject.routes

import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import at.ourproject.dao.Dao
import at.ourproject.services.RegisterService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._

import scala.concurrent.ExecutionContext

class EegRoutes (daos: Dao, node: ActorRef[RegisterService.Command])(implicit val system: ActorSystem[_], ex: ExecutionContext) extends Router  {
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  implicit val scheduler: Scheduler = system.scheduler

  implicit def executionContext: ExecutionContext = system.executionContext

  val eegRoutes = {
      pathPrefix("vfeeg") {
        path("eeg") {
          get {
            //              onSuccess(eegRepository.getAll()) { res =>
            complete(daos.eegRepository.getAll())
            //              }
          }
        }
      }
  }

  override def route: Route = eegRoutes
}
