package at.ourproject.services

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.grpc.GrpcClientSettings
import akka.util.Timeout
import at.energydash.admin.{RegisterPontonRequest, RegisterPontonServiceClient}
import at.ourproject.dao.Db
import at.ourproject.keycloak.KeycloakClient
import at.ourproject.keycloak.KeycloakClient.User
import at.ourproject.register.{RegisterEegRequest, RegisterEegServiceClient}
import io.circe.{Decoder, Encoder}

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

  val KEP, MAIL = Value
}

object RegisterService {

  import at.ourproject.services.EegLegalType.EegLegal
  import at.ourproject.services.EegSettlementType.EegSettlement
  import at.ourproject.services.GridAllocationType.GridAllocation
  import at.ourproject.services.GridAreaType.GridArea
  import at.ourproject.services.PontonCommType.PontonComm

  trait Command
  case class PontonInfo(username: String, password: String, domain: String, host: Option[String], port: Option[Int], pontonCommType: PontonComm)

  case class PontonRegInfo(tenant: String, pontonInfo: PontonInfo)

  case class Contact(owner: String, street: String, streetNumber: String, city: String, zip: String, email: String, web: Option[String], phone: Option[String])

  case class AccountInfo(iban: String, owner: String, sepa: Boolean, bankName: String)

  case class BusinessInfo(legal: EegLegal, businessNr: String, taxNumber: String, vatNumber: String, settlementInterval: EegSettlement)

  case class Grid(id: String, name: String, area: GridArea, allocation: GridAllocation)

  case class UserInfo(username: String, password: String, firstname: String, lastname: String, email: String)

  case class Eeg(tenant: String, rcNumber: String, communityId: String, name: String, description: String, online: Boolean,
                 accountInfo: AccountInfo, businessInfo: BusinessInfo, grid: Grid, contact: Contact, pontonInfo: PontonInfo, user: UserInfo)

  case class AutorizedUser(firstname: String, lastname: String, email: String, tenant: String, roles: Option[List[String]])

  case class RegisterEeg(eeg: Eeg, groups: List[String], replyTo: ActorRef[Command]) extends Command

  case class EegRegisterd(code: Int, message: String) extends Command

  case class RegisterParticipant(tenant: String, firstname: String, lastname: String, email: String) extends Command

  case class AutorizeParticipant(email: String, tenant: String, roles: List[String])

  case class LookupUsersRequest(tenant: String, replyTo: ActorRef[Command]) extends Command

  case class LookupUsersResponse(users: List[AutorizedUser]) extends Command

  case class AddTenantToUser(tenant: String, user: String, replyTo: ActorRef[Command]) extends Command
  case class AddTenantToUserResponse(status: Int) extends Command

  case class PontonRegister(tenant: String, pontonInfo: PontonInfo, replyTo: ActorRef[Command]) extends Command
  case class PontonRegisterResponse(status: Int, message: String) extends Command

  def apply(keycloakClient: KeycloakClient): Behavior[Command] = {
    import scala.jdk.CollectionConverters._
    val dbConfig = Db.getConfig

    Behaviors.setup[Command] { context =>
      implicit def system: ActorSystem[Nothing] = context.system

      implicit def ec: ExecutionContext = system.executionContext

      implicit def scheduler: Scheduler = context.system.scheduler

      implicit lazy val timeout: Timeout = Timeout(5.seconds)
      //      context.system.receptionist ! Receptionist.Register(NodeServiceKey, context.self)

      val eegClient = RegisterEegServiceClient(GrpcClientSettings.fromConfig("register.RegisterEegService"))
      val pontonClient = RegisterPontonServiceClient(GrpcClientSettings.fromConfig("register.RegisterPontonService"))

//      val userRepo = new SlickTenantUserRepository(dbConfig)

      Behaviors.receiveMessage { message => {
        message match {
          case RegisterEeg(eeg, groups, replyTo) => {
            val userExists = keycloakClient.checkUserAlreadyExist(eeg.user.email)
            val request = RegisterEegRequest(eeg.tenant, eeg.rcNumber.toUpperCase(), eeg.communityId, eeg.name, eeg.description,
              eeg.accountInfo.iban, eeg.accountInfo.owner, eeg.accountInfo.sepa, eeg.accountInfo.bankName,
              (eeg.businessInfo.legal match {
                case EegLegalType.verein => RegisterEegRequest.EEG_LEGAL.verein
                case EegLegalType.gesellschaft => RegisterEegRequest.EEG_LEGAL.gesellschaft
                case EegLegalType.genossenschaft => RegisterEegRequest.EEG_LEGAL.genossenschaft
                case _ => RegisterEegRequest.EEG_LEGAL.verein
              }), eeg.businessInfo.businessNr, eeg.businessInfo.taxNumber, eeg.businessInfo.vatNumber,
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
              }), eeg.contact.owner, eeg.contact.street, eeg.contact.streetNumber, eeg.contact.city, eeg.contact.zip,
              eeg.contact.email, eeg.contact.web, eeg.contact.phone, eeg.online,
            )

            val pontonRequest = RegisterPontonRequest(eeg.rcNumber.toUpperCase(), eeg.pontonInfo.username, eeg.pontonInfo.password, eeg.pontonInfo.domain)

            context.log.info(s"Register new EEG ${request}")

            (for {
              eegReply <- eegClient.register(request).map(r => {
                if (r.status == 201) true else false
              })
              pontonReply <- if (eeg.online) pontonClient.register(pontonRequest).map(r => if (r.status == 200) true else false) else Future(true)
            } yield (eegReply && pontonReply)).onComplete {
              case Success(state) =>
                try {
                  if (userExists) {
                    keycloakClient.getUserId(eeg.user.email) match {
                      case Success(id) =>
                        keycloakClient.addTenantToUser(id, eeg.rcNumber)
                        replyTo ! EegRegisterd(201, "")
                      case _ => replyTo ! EegRegisterd(510, "Error adding tenant to user")
                    }
                  } else {
                    //                    println(s"REGISTRATION STATE: ${state}")
                    val keyCloakUser = User(eeg.user.username, eeg.user.firstname, eeg.user.lastname, eeg.user.email, eeg.user.password)
                    val userGroups = keycloakClient.getGroups().filter(g => groups.contains(g.getName))
                    //                    println(s"Create Keycloak User: ${keyCloakUser}, ${userGroups}")
                    keycloakClient.createUser(keyCloakUser, eeg.rcNumber, userGroups)
                  }
                  replyTo ! EegRegisterd(201, "")
                } catch {
                  case e: Exception => replyTo ! EegRegisterd(501, e.getMessage)
                }
              case Failure(e) => replyTo ! EegRegisterd(502, e.getMessage)
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
                    AutorizedUser(ku.getFirstName, ku.getLastName, ku.getEmail, tenant, if (ku.getRealmRoles == null) None else Some(ku.getRealmRoles.asScala.toList)))
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

          case PontonRegister(tenant, pontonInfo, replyTo) =>
            val pontonRequest = RegisterPontonRequest(tenant.toUpperCase(),
              pontonInfo.username,
              pontonInfo.password,
              pontonInfo.domain,
              pontonInfo.pontonCommType.toString)

            pontonClient.register(pontonRequest).onComplete {
              case Success(value) if value.status == 200 => replyTo ! PontonRegisterResponse(200, value.message)
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
