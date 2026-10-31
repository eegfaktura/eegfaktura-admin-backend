package at.ourproject.routes

import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.apache.pekko.util.Timeout
import at.ourproject.logging.AccessLog
import at.ourproject.services.RegisterService
import at.ourproject.services.RegisterService.{AccountGroups, AccountUser, Eeg, PontonRegInfo}
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class RegistrationRoutes(akkaAuthenticator: Credentials => Future[Option[AuthenticatedUser]], node: ActorRef[RegisterService.Command])(implicit val system: ActorSystem[_], val ex: ExecutionContext) extends Router {
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  // Registration with several accounts and the account list make many Keycloak calls (and set-password mails):
  // they get more time than the other routes (platform#166). Below the idle timeout and the proxy read timeout (60 s).
  private val keycloakTimeout: Timeout = Timeout(50.seconds)
  implicit val scheduler: Scheduler = system.scheduler
  implicit val materializer: Materializer = SystemMaterializer(system).materializer

  private val registrationRoutes = {
    concat(
      pathPrefix("eeg") {
        KeycloakJwtAuthenticator.authenticated(akkaAuthenticator) { user: AuthenticatedUser =>
          RoleDirectives.requireSuperuser(user) {
          AccessLog.path("register") {
            pathEndOrSingleSlash {
              post {
                withRequestTimeout(55.seconds) { AccessLog.withTenant(entity(as[Eeg]))(_.tenant) { eeg =>
                  val processFuture: Future[RegisterService.ResponseCommand] = node.ask(
                    ref => RegisterService.RegisterEeg(eeg, AccountGroups, ref)
                  )(keycloakTimeout, scheduler).mapTo[RegisterService.ResponseCommand]
                  onSuccess(processFuture) {
                    case res: RegisterService.EegRegistered => complete(res)
                    case err: RegisterService.EegRegisterError if err.code == RegisterService.EegExistsStatus => complete(StatusCodes.Conflict, err)
                    case err: RegisterService.EegRegisterError => complete(StatusCodes.BadRequest, err)
                    case _ => complete(StatusCodes.InternalServerError)
                  }
                } }
              }
            }
          } ~
            AccessLog.path("ponton") {
              pathEndOrSingleSlash {
                post {
                  AccessLog.withTenant(entity(as[PontonRegInfo]))(_.tenant) { pontonRegInfo =>
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
            AccessLog.path("account") {
              pathEndOrSingleSlash {
                post { withRequestTimeout(55.seconds) {
                  AccessLog.tenantParameter { tenant =>
                    entity(as[AccountUser]) { user =>
                      val processFuture: Future[RegisterService.AddAccountResponse] = node.ask(
                        ref => RegisterService.AddAccount(tenant, user, AccountGroups, ref)
                      )(keycloakTimeout, scheduler).mapTo[RegisterService.AddAccountResponse]
                      onSuccess(processFuture) { res =>
                        complete(StatusCodes.getForKey(res.status).getOrElse(StatusCodes.InternalServerError), res.result)
                      }
                    }
                  }
                } }
              }
            } ~
            AccessLog.path("accounts") {
              get { withRequestTimeout(55.seconds) {
                AccessLog.tenantParameter { tenant =>
                  val processFuture: Future[RegisterService.ListAccountsResponse] = node.ask(
                    ref => RegisterService.ListAccounts(tenant, ref)
                  )(keycloakTimeout, scheduler).mapTo[RegisterService.ListAccountsResponse]
                  onSuccess(processFuture) { res =>
                    if (res.status == 200) complete(res.accounts)
                    else complete(StatusCodes.getForKey(res.status).getOrElse(StatusCodes.InternalServerError), res.message)
                  }
                }
              } }
            } ~
            AccessLog.path("addTenant") {
              get {
                (AccessLog.tenantParameter & parameter("email")) { (tenant, email) =>
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
