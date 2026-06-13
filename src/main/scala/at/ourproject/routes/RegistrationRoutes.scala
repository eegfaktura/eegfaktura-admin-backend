package at.ourproject.routes

import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.apache.pekko.util.Timeout
import at.ourproject.services.RegisterService
import at.ourproject.services.RegisterService.{Eeg, PontonRegInfo}
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import org.slf4j.Logger

import scala.concurrent.{ExecutionContext, Future}

class RegistrationRoutes(akkaAuthenticator: Credentials => Future[Option[AuthenticatedUser]], node: ActorRef[RegisterService.Command])(implicit val system: ActorSystem[_], val ex: ExecutionContext) extends Router {
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  implicit val scheduler: Scheduler = system.scheduler
  implicit val materializer: Materializer = SystemMaterializer(system).materializer

  private val log: Logger = system.log

  private val registrationRoutes = {
    concat(
      pathPrefix("eeg") {
        authenticateOAuth2Async(realm = "keycloak", authenticator = akkaAuthenticator) { user: AuthenticatedUser =>
          path("register") {
            pathEndOrSingleSlash {
              post {
                entity(as[Eeg]) { eeg =>
                  val processFuture: Future[RegisterService.ResponseCommand] = node.ask(
                    ref => RegisterService.RegisterEeg(eeg, List("EEG_ADMIN", "EEG_OWNER"), ref)
                  )(timeout, scheduler).mapTo[RegisterService.ResponseCommand]
                  onSuccess(processFuture) {
                    case res: RegisterService.EegRegistered => complete(res)
                    case err: RegisterService.EegRegisterError => complete(StatusCodes.BadRequest, err)
                    case _ => complete(StatusCodes.InternalServerError)
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
                      ref => RegisterService.PontonRegister(pontonRegInfo.tenant, pontonRegInfo.rcNumber, pontonRegInfo.pontonInfo, ref)
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
