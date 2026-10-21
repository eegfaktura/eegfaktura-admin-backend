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
import at.ourproject.services.RegisterService.{AccountGroups, AccountUser, Eeg, PontonRegInfo}
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
          RoleDirectives.requireSuperuser(user) {
          path("register") {
            pathEndOrSingleSlash {
              post {
                entity(as[Eeg]) { eeg =>
                  val processFuture: Future[RegisterService.ResponseCommand] = node.ask(
                    ref => RegisterService.RegisterEeg(eeg, AccountGroups, ref)
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
            path("account") {
              pathEndOrSingleSlash {
                post {
                  parameters("tenant") { tenant =>
                    entity(as[AccountUser]) { user =>
                      val processFuture: Future[RegisterService.AddAccountResponse] = node.ask(
                        ref => RegisterService.AddAccount(tenant, user, AccountGroups, ref)
                      )(timeout, scheduler).mapTo[RegisterService.AddAccountResponse]
                      onSuccess(processFuture) { res =>
                        complete(StatusCodes.getForKey(res.status).getOrElse(StatusCodes.InternalServerError), res.result)
                      }
                    }
                  }
                }
              }
            } ~
            path("accounts") {
              get {
                parameters("tenant") { tenant =>
                  val processFuture: Future[RegisterService.ListAccountsResponse] = node.ask(
                    ref => RegisterService.ListAccounts(tenant, ref)
                  )(timeout, scheduler).mapTo[RegisterService.ListAccountsResponse]
                  onSuccess(processFuture) { res =>
                    if (res.status == 200) complete(res.accounts)
                    else complete(StatusCodes.getForKey(res.status).getOrElse(StatusCodes.InternalServerError), res.message)
                  }
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
      }
    )
  }

  override def route: Route = registrationRoutes
}
