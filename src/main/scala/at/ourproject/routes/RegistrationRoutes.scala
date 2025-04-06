package at.ourproject.routes

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout
import at.ourproject.services.RegisterService
import at.ourproject.services.RegisterService.{Eeg, PontonRegInfo}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import org.slf4j.Logger

import scala.concurrent.{ExecutionContext, Future}

class RegistrationRoutes(node: ActorRef[RegisterService.Command])(implicit val system: ActorSystem[_], val ex: ExecutionContext) extends Router with TokenVerifier {
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  implicit val scheduler: Scheduler = system.scheduler
  implicit val materializer: Materializer = SystemMaterializer(system).materializer

  override val log: Logger = system.log

  val registrationRoutes = {
    concat(
      pathPrefix("eeg") {
        authorize { token =>
          path("register") {
            pathEndOrSingleSlash {
              post {
                entity(as[Eeg]) { eeg =>
                  val processFuture: Future[RegisterService.EegRegisterd] = node.ask(
                    ref => RegisterService.RegisterEeg(eeg, List("EEG_ADMIN", "EEG_OWNER"), ref)
                  )(timeout, scheduler).mapTo[RegisterService.EegRegisterd]
                  onSuccess(processFuture) { res =>
                    complete(res)
                  }
                }
              }
            }
          } ~
            path("ponton") {
              pathEndOrSingleSlash {
                post {
                  entity(as[PontonRegInfo]) { pontonRegInfo =>
                    val processFuture: Future[RegisterService.PontonRegisterResponse] = node.ask(
                      ref => RegisterService.PontonRegister(pontonRegInfo.tenant, pontonRegInfo.pontonInfo, ref)
                    )(timeout, scheduler).mapTo[RegisterService.PontonRegisterResponse]
                    onSuccess(processFuture) { res =>
                      complete(res)
                    }
                  }
                }
              }
            } ~
            path("users") {
            get {
              val processFuture: Future[RegisterService.LookupUsersResponse] = node.ask(
                ref => RegisterService.LookupUsersRequest("RC100130", ref)
              )(timeout, scheduler).mapTo[RegisterService.LookupUsersResponse]
              onSuccess(processFuture) { res =>
                complete(res)
              }
            }
          } ~
            path("addTenant") {
              get {
                parameters("tenant", "email") { (tenant, email) =>
                  val processFuture: Future[RegisterService.AddTenantToUserResponse] = node.ask(
                    ref => RegisterService.AddTenantToUser(tenant, email, ref)
                  )(timeout, scheduler).mapTo[RegisterService.AddTenantToUserResponse]
                  onSuccess(processFuture) { res =>
                    complete(res)
                  }
                }
              }
            }
        }
      }
    )
  }

  override def route: Route = registrationRoutes
}
