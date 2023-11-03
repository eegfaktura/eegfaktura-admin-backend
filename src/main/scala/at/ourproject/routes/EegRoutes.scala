package at.ourproject.routes

import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import at.ourproject.services.RegisterService

import scala.concurrent.ExecutionContext

class EegRoutes (node: ActorRef[RegisterService.Command])(implicit val system: ActorSystem[_], ex: ExecutionContext) extends Router {
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  implicit val scheduler: Scheduler = system.scheduler

//  val registrationRoutes = {
//    concat(
//      pathPrefix("eeg") {
//        path("register") {
//          pathEndOrSingleSlash {
//            post {
//              entity(as[Eeg]) { eeg =>
//                val processFuture: Future[RegisterService.EegRegisterd] = node.ask(
//                  ref => RegisterService.RegisterEeg(eeg, List("EEG_ADMIN", "EEG_OWNER"), ref)
//                )(timeout, scheduler).mapTo[RegisterService.EegRegisterd]
//                onSuccess(processFuture) { res =>
//                  complete(res)
//                }
//              }
//            }
//          }
//        }
//      }
//    )
//  }

  override def route: Route = ???
}
