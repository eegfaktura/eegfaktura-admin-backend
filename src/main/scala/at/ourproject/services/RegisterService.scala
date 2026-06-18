package at.ourproject.services

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.util.Timeout
import at.energydash.admin.{RegisterPontonRequest, RegisterPontonServiceClient}
import at.ourproject.admin.{AdminEegServiceClient, UpdateEegRequest}
import at.ourproject.keycloak.KeycloakClient
import at.ourproject.keycloak.KeycloakClient.User
import at.ourproject.register.{RegisterEegRequest, RegisterEegServiceClient}
import io.circe.{Decoder, Encoder}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object GridAreaType extends Enumeration {
  type GridArea = Value

  implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
  implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

  val LOCAL, REGIONAL, GEA, BEG = Value
}

object GridAllocationType extends Enumeration {
  type GridAllocation = Value

  implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
  implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

  val DYNAMIC, STATIC = Value
}

object EegLegalType extends Enumeration {
  type EegLegal = Value

  implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
  implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

  val verein, genossenschaft, gesellschaft = Value
}

object EegSettlementType extends Enumeration {
  type EegSettlement = Value

  implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
  implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

  val ANNUAL, BIANNUAL, QUARTER, MONTHLY = Value
}

object PontonCommType extends Enumeration {
  type PontonComm = Value

  implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
  implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

  val NONE, KEP, MAIL = Value
}

object RegisterService {

  import at.ourproject.services.EegLegalType.EegLegal
  import at.ourproject.services.EegSettlementType.EegSettlement
  import at.ourproject.services.GridAllocationType.GridAllocation
  import at.ourproject.services.GridAreaType.GridArea
  import at.ourproject.services.PontonCommType.PontonComm

  trait Command

  trait ResponseCommand extends Command

  case class PontonInfo(username: String, password: String, domain: String, host: Option[String], port: Option[Int], pontonCommType: PontonComm)

  case class PontonRegInfo(tenant: String, rcNumber: String, pontonInfo: PontonInfo)

  case class Contact(street: String, streetNumber: String, city: String, zip: String, web: Option[String], phone: Option[String])

  case class AccountInfo(iban: String, owner: String, sepa: Boolean)

  case class BusinessInfo(legal: EegLegal, settlementInterval: EegSettlement)

  case class Grid(id: String, name: String, area: GridArea, allocation: GridAllocation)

  case class UserInfo(username: String, password: String, firstname: String, lastname: String, email: String)

  case class Eeg(tenant: String, rcNumber: String, communityId: String, name: String, description: String, online: Boolean,
                 accountInfo: AccountInfo, businessInfo: BusinessInfo, grid: Grid, contact: Contact, pontonInfo: PontonInfo, user: UserInfo)

  case class AuthorizedUser(firstname: String, lastname: String, email: String, tenant: String, roles: Option[List[String]])

  case class RegisterEeg(eeg: Eeg, groups: List[String], replyTo: ActorRef[Command]) extends Command

  case class EegRegistered(code: Int, message: String) extends ResponseCommand
  case class EegRegisterError(code: Int, message: String) extends ResponseCommand

  private case class RegisterParticipant(tenant: String, firstname: String, lastname: String, email: String) extends Command

  case class LookupUsersRequest(tenant: String, replyTo: ActorRef[Command]) extends Command

  case class LookupUsersResponse(users: List[AuthorizedUser]) extends Command

  case class AddTenantToUser(tenant: String, user: String, replyTo: ActorRef[Command]) extends Command
  case class AddTenantToUserResponse(status: Int) extends Command

  case class PontonRegister(tenant: String, rcNumber: String, pontonInfo: PontonInfo, replyTo: ActorRef[Command]) extends Command
  case class PontonRegisterResponse(status: Int, message: String) extends Command

  val log: Logger = LoggerFactory.getLogger(getClass)

  def registerPonton(eeg: Eeg, pontonClient: RegisterPontonServiceClient)(implicit ec: ExecutionContext): Future[Boolean]  = {
    eeg.pontonInfo.pontonCommType match {
      case PontonCommType.NONE => Future(true)
      case _ if eeg.online =>
        pontonClient.register(
          RegisterPontonRequest(eeg.rcNumber.toUpperCase(), eeg.pontonInfo.username, eeg.pontonInfo.password, eeg.pontonInfo.domain)).map(r => r.status == 200)
      case _ => Future(true)
    }
  }

  def apply(keycloakClient: KeycloakClient): Behavior[Command] = {
    import scala.jdk.CollectionConverters._
//    val dbConfig = Db.getConfig

    Behaviors.setup[Command] { context =>
      implicit def system: ActorSystem[Nothing] = context.system

      implicit def ec: ExecutionContext = system.executionContext

      implicit def scheduler: Scheduler = context.system.scheduler

      implicit lazy val timeout: Timeout = Timeout(15.seconds)
      //      context.system.receptionist ! Receptionist.Register(NodeServiceKey, context.self)

      val adminClient = AdminEegServiceClient(GrpcClientSettings.fromConfig("register.RegisterEegService"))
      val eegClient = RegisterEegServiceClient(GrpcClientSettings.fromConfig("register.RegisterEegService"))
      val pontonClient = RegisterPontonServiceClient(GrpcClientSettings.fromConfig("register.RegisterPontonService"))

//      val userRepo = new SlickTenantUserRepository(dbConfig)

      Behaviors.receiveMessage { message => {
        message match {
          case RegisterEeg(eeg, groups, replyTo) => {
            val userExists = keycloakClient.checkUserAlreadyExist(eeg.user.email)
            val request = RegisterEegRequest(eeg.tenant, eeg.rcNumber.toUpperCase(), eeg.communityId, eeg.name, eeg.description,
              eeg.accountInfo.iban, eeg.accountInfo.owner, eeg.accountInfo.sepa,
              (eeg.businessInfo.legal match {
                case EegLegalType.verein => RegisterEegRequest.EEG_LEGAL.verein
                case EegLegalType.gesellschaft => RegisterEegRequest.EEG_LEGAL.gesellschaft
                case EegLegalType.genossenschaft => RegisterEegRequest.EEG_LEGAL.genossenschaft
                case _ => RegisterEegRequest.EEG_LEGAL.verein
              }),
              (eeg.businessInfo.settlementInterval match {
                case EegSettlementType.ANNUAL => RegisterEegRequest.EEG_SETTELMENT.ANNUAL
                case EegSettlementType.BIANNUAL => RegisterEegRequest.EEG_SETTELMENT.BIANNUAL
                case EegSettlementType.QUARTER => RegisterEegRequest.EEG_SETTELMENT.QUARTER
                case EegSettlementType.MONTHLY => RegisterEegRequest.EEG_SETTELMENT.MONTHLY
                case _ => RegisterEegRequest.EEG_SETTELMENT.ANNUAL
              }), eeg.grid.id, eeg.grid.name, (eeg.grid.area match {
                case GridAreaType.LOCAL => RegisterEegRequest.GRID_AREA.LOCAL
                case GridAreaType.BEG => RegisterEegRequest.GRID_AREA.BEG
                case GridAreaType.GEA => RegisterEegRequest.GRID_AREA.GEA
                case _ => RegisterEegRequest.GRID_AREA.REGIONAL
              }), (eeg.grid.allocation match {
                case GridAllocationType.DYNAMIC => RegisterEegRequest.GRID_ALLOCATION.DYNAMIC
                case _ => RegisterEegRequest.GRID_ALLOCATION.STATIC
              }), s"${eeg.user.firstname} ${eeg.user.lastname}", eeg.contact.street, eeg.contact.streetNumber, eeg.contact.city, eeg.contact.zip,
              eeg.user.email, eeg.contact.web, eeg.contact.phone, eeg.online,
            )

            context.log.info(s"Register new EEG ${request}")

            (for {
              eegReply <- eegClient.register(request).map(r => r.status == 201)
              pontonReply <- registerPonton(eeg, pontonClient)
            } yield (eegReply && pontonReply)).onComplete {
              case Success(state) if state =>
                try {
                  log.info(s"Create Keycloak User [exists=${userExists}} - ${eeg.rcNumber}")
                  if (userExists) {
                    keycloakClient.getUserId(eeg.user.email) match {
                      case Success(id) =>
                        keycloakClient.configureUser(
                          userId = id, tenant = eeg.rcNumber, userInfo = eeg.user,
                          userGroups = keycloakClient.getGroups.filter(g => groups.contains(g.getName)))
                        replyTo ! EegRegistered(201, "")
                      case _ => replyTo ! EegRegisterError(510, "Error adding tenant to user")
                    }
                  } else {
                    val keyCloakUser = User(Option(eeg.user.username).filter(_.nonEmpty), eeg.user.firstname, eeg.user.lastname, eeg.user.email, Option(eeg.user.password).filter(_.nonEmpty))
                    val userGroups = keycloakClient.getGroups.filter(g => groups.contains(g.getName))
                    keycloakClient.createUser(keyCloakUser, eeg.rcNumber, userGroups)

                    log.info(s"Keycloak User created ${!userExists} - ${eeg.rcNumber}")
                    replyTo ! EegRegistered(201, "")
                  }
                } catch {
                  case e: Exception =>
                    log.error(s"Creating/Updating ($userExists) Keycloak User failed ${eeg.rcNumber} - ${e.getMessage}")
                    replyTo ! EegRegisterError(510, s"Creating Keycloak User failed! ${e.getMessage}")
                }
              case Failure(e) =>
                log.error(s"Failure Create User: ${e.toString}")
                replyTo ! EegRegisterError(502, s"Creating Tenant failed! ${e.getMessage}")
              case e =>
                log.error(s"Error creating EEG ${e.toString}")
                replyTo ! EegRegisterError(501, "Creating Tenant failed!")
            }
          }

          case RegisterParticipant(tenant, firstname, lastname, email) =>

//            userRepo.create(KeycloakUser(id = UUID.randomUUID(),
//              tenant = tenant,
//              username = None,
//              firtsname = firstname,
//              lastname = lastname,
//              email = email,
//              invitedAt = LocalDateTime.now(), createdAt = LocalDateTime.now, status = 0))

          case LookupUsersRequest(tenant, replyTo) =>
            replyTo ! LookupUsersResponse(
              keycloakClient.findUserByTenant(tenant) match {
                case Success(l) =>
                  l.filter(ku => ku.getFirstName != null).map(ku =>
                    AuthorizedUser(ku.getFirstName, ku.getLastName, ku.getEmail, tenant, if (ku.getRealmRoles == null) None else Some(ku.getRealmRoles.asScala.toList)))
                case Failure(e) =>
                  context.log.error(e.getMessage)
                  List.empty
              })

          case AddTenantToUser(tenant, email, replyTo) =>
            keycloakClient.getUserId(email) match {
              case Success(id) =>
                keycloakClient.addTenantToUser(id, tenant)
                replyTo ! AddTenantToUserResponse(200)
              case _ => replyTo ! AddTenantToUserResponse(500)
            }

          case PontonRegister(tenant, rcNumber, pontonInfo, replyTo) =>
            val pontonRequest = RegisterPontonRequest(rcNumber.toUpperCase(),
              pontonInfo.username,
              pontonInfo.password,
              pontonInfo.domain,
              pontonInfo.pontonCommType.toString)

            pontonClient.register(pontonRequest).onComplete {
              case Success(value) if value.status == 200 => {
                adminClient.updateValue(UpdateEegRequest(
                  updateClass = UpdateEegRequest.UPDATE_CLASS.EEG,
                  tenant = tenant,
                  value = Map("online" -> "true"),
                )) onComplete {
                  case Success(_) => replyTo ! PontonRegisterResponse(500, value.message)
                  case Failure(ex) => replyTo ! PontonRegisterResponse(500, ex.getMessage)
                }
              }
              case Success(value) => replyTo ! PontonRegisterResponse(500, value.message)
              case Failure(exception) => replyTo ! PontonRegisterResponse(500, exception.getMessage)
            }
        }
        Behaviors.same
      }
      }
    }
  }
}
