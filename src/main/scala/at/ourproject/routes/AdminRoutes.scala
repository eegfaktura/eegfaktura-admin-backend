package at.ourproject.routes

import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.apache.pekko.util.Timeout
import at.ourproject.dao.Dao
import at.ourproject.routes.UpdateClassEnum.UpdateClassEnum
import at.ourproject.services.AdminService
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import org.slf4j.Logger
import sttp.client3._

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

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

// Ops request to delete raw energy data of one metering point in a time range.
case class DeleteRawDataRequest(tenant: String, ecId: String, meteringPoint: String, start: Long, end: Long, dryRun: Boolean)
// Body forwarded to energystore (tenant travels as a header, ecId as a path segment).
case class EnergystoreDeleteBody(meteringPoint: String, start: Long, end: Long, dryRun: Boolean)

class AdminRoutes(daos: Dao, akkaAuthenticator: Credentials => Future[Option[AuthenticatedUser]], admin: ActorRef[AdminService.Command]) (implicit val system: ActorSystem[_], val ex: ExecutionContext) extends Router {
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  implicit val scheduler: Scheduler = system.scheduler
  implicit val materializer: Materializer = SystemMaterializer(system).materializer

  import at.ourproject.json.JsonFormater._

  val log: Logger = system.log

  private val energystoreUrl: String = system.settings.config.getString("app.energystore.url")
  private val energystoreBackend = HttpURLConnectionBackend()

  private def hasRealmRole(user: AuthenticatedUser, role: String): Boolean =
    user.claims.get("realm_access") match {
      case Some(m: java.util.Map[_, _]) =>
        m.asInstanceOf[java.util.Map[String, Any]].get("roles") match {
          case roles: java.util.List[_] => roles.asScala.map(_.toString).contains(role)
          case _                        => false
        }
      case _ => false
    }

  // Forwards the delete to energystore, passing the operator's bearer through so
  // energystore's superuser-aware middleware authorizes the (cross-tenant) call.
  private def forwardRawDataDelete(authHeader: String, req: DeleteRawDataRequest): Future[(Int, String)] = Future {
    val body = EnergystoreDeleteBody(req.meteringPoint, req.start, req.end, req.dryRun).asJson.noSpaces
    val response = basicRequest
      .post(uri"$energystoreUrl/eeg/v2/${req.ecId}/rawdata/delete")
      .header("Authorization", authHeader)
      .header("tenant", req.tenant)
      .body(body)
      .contentType("application/json")
      .send(energystoreBackend)
    (response.code.code, response.body.fold(identity, identity))
  }

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
        } ~
        path("energystore" / "rawdata" / "delete") {
          pathEndOrSingleSlash {
            post {
              entity(as[DeleteRawDataRequest]) { req =>
                optionalHeaderValueByName("Authorization") { authOpt =>
                  if (!hasRealmRole(user, "superuser")) {
                    complete(StatusCodes.Forbidden)
                  } else authOpt match {
                    case None => complete(StatusCodes.Unauthorized)
                    case Some(auth) =>
                      onComplete(forwardRawDataDelete(auth, req)) {
                        case scala.util.Success((code, body)) =>
                          complete(HttpResponse(status = StatusCode.int2StatusCode(code), entity = HttpEntity(ContentTypes.`application/json`, body)))
                        case scala.util.Failure(e) =>
                          log.error(s"rawdata delete forward failed: ${e.getMessage}")
                          complete(StatusCodes.BadGateway)
                      }
                  }
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
