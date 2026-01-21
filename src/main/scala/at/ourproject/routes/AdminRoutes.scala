package at.ourproject.routes

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.Credentials
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout
import at.ourproject.dao.Dao
import at.ourproject.routes.UpdateClassEnum.UpdateClassEnum
import at.ourproject.services.AdminService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import io.circe.{Decoder, Encoder}
import org.slf4j.Logger

import scala.concurrent.{ExecutionContext, Future}

object UpdateClassEnum extends Enumeration {
  type UpdateClassEnum = Value

  implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
  implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

  val PROCESSSTATUS: UpdateClassEnum.Value = Value("PROCESSSTATUS")
  val INACTIVESINCE: UpdateClassEnum.Value = Value("INACTIVESINCE")
  val ACTIVESINCE: UpdateClassEnum.Value = Value("ACTIVESINCE")
  val PARTICIPANT: UpdateClassEnum.Value = Value("PARTICIPANT")
  val EEG: UpdateClassEnum.Value = Value("EEG")
}

case class UpdateMessage(updateClass: UpdateClassEnum, tenant: String, participantId: Option[String], meteringPoint: Option[String], value: Map[String, String])

class AdminRoutes(daos: Dao, authenticator: KeycloakJwtAuthenticator, admin: ActorRef[AdminService.Command]) (implicit val system: ActorSystem[_], val ex: ExecutionContext) extends Router with TokenVerifier {
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  implicit val scheduler: Scheduler = system.scheduler
  implicit val materializer: Materializer = SystemMaterializer(system).materializer

  import at.ourproject.json.JsonFormater._

  override val log: Logger = system.log
  private val akkaAuthenticator: Credentials => scala.concurrent.Future[Option[AuthenticatedUser]] =
    cred => authenticator.authenticate(cred)

  private val adminRoutes = concat(
    pathPrefix("admin") {
      authenticateOAuth2Async(realm = "keycloak", authenticator = akkaAuthenticator) { user: AuthenticatedUser =>
        path("master" / "update") {
          pathEndOrSingleSlash {
            post {
              entity(as[UpdateMessage]) { updateMsg =>
                val processFuture: Future[AdminService.Command] = admin.ask(
                  ref => AdminService.MessageUpdate(updateMsg, ref)
                )(timeout, scheduler).mapTo[AdminService.Command]
                onSuccess(processFuture) {
                  case AdminService.MessageUpdateOk =>
                    complete(daos.meteringRepository.getMeteringPoint(updateMsg.tenant, updateMsg.participantId.getOrElse(""), updateMsg.meteringPoint.getOrElse("")))
                  case AdminService.MessageUpdateFail(_, e) =>
                    log.error(e)
                    complete(StatusCodes.BadRequest)
                }
              }
            }
          }
        } ~
        path("master" / "update" / "participant") {
          pathEndOrSingleSlash {
            post {
              entity(as[UpdateMessage]) { updateMsg =>
                val processFuture: Future[AdminService.Command] = admin.ask(
                  ref => AdminService.MessageUpdate(updateMsg, ref)
                )(timeout, scheduler).mapTo[AdminService.Command]
                onSuccess(processFuture) {
                  case AdminService.MessageUpdateOk =>
                    complete(daos.participantRepository.getById(updateMsg.participantId.getOrElse("")))
                  case AdminService.MessageUpdateFail(_, e) =>
                    log.error(s"Message Update Failed - $e")
                    complete(StatusCodes.BadRequest)
                }
              }
            }
          }
        } ~
        path("master" / "update" / "eeg") {
          pathEndOrSingleSlash {
            post {
              entity(as[UpdateMessage]) { updateMsg =>
                val processFuture: Future[AdminService.Command] = admin.ask(
                  ref => AdminService.MessageUpdate(updateMsg, ref)
                )(timeout, scheduler).mapTo[AdminService.Command]
                onSuccess(processFuture) {
                  case AdminService.MessageUpdateOk =>
                    complete(daos.eegRepository.getByTenant(updateMsg.tenant))
                  case AdminService.MessageUpdateFail(_, e) =>
                    log.error(s"Message Update Failed - ${e}")
                    complete(StatusCodes.BadRequest)
                }
              }
            }
          }
        }
      }
    }
  )
  override def route: Route = adminRoutes
}
