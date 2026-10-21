package at.ourproject.services

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler, SupervisorStrategy}
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.util.Timeout
import at.energydash.admin.{RegisterPontonRequest, RegisterPontonServiceClient}
import at.ourproject.admin.{AdminEegServiceClient, UpdateEegRequest}
import at.ourproject.keycloak.{AccountStatus, KeycloakClient}
import at.ourproject.keycloak.KeycloakClient.{AccountView, User}
import at.ourproject.register.{RegisterEegRequest, RegisterEegServiceClient}
import io.circe.{Decoder, Encoder}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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

  /** A person with access to a community; all accounts have the same rights. */
  case class AccountUser(firstname: String, lastname: String, email: String)

  /** status: created, linked (existing account got the community) or failed (message says why). */
  case class AccountResult(email: String, status: String, message: String)

  case class Eeg(tenant: String, rcNumber: String, communityId: String, name: String, description: String, online: Boolean,
                 accountInfo: AccountInfo, businessInfo: BusinessInfo, grid: Grid, contact: Contact, pontonInfo: PontonInfo, user: UserInfo,
                 accounts: Option[List[AccountUser]] = None)

  case class RegisterEeg(eeg: Eeg, groups: List[String], replyTo: ActorRef[Command]) extends Command

  /** accounts: the result per account of the registration's list (empty without a list). */
  case class EegRegistered(code: Int, message: String, accounts: List[AccountResult] = Nil) extends ResponseCommand
  case class EegRegisterError(code: Int, message: String) extends ResponseCommand

  private case class RegisterParticipant(tenant: String, firstname: String, lastname: String, email: String) extends Command

  case class AddTenantToUser(tenant: String, user: String, replyTo: ActorRef[Command]) extends Command
  case class AddTenantToUserResponse(status: Int) extends Command

  case class AddAccount(tenant: String, user: AccountUser, groups: List[String], replyTo: ActorRef[Command]) extends Command
  case class AddAccountResponse(status: Int, result: AccountResult) extends Command

  case class ListAccounts(tenant: String, replyTo: ActorRef[Command]) extends Command
  case class ListAccountsResponse(status: Int, accounts: List[AccountView], message: String = "") extends Command

  case class PontonRegister(tenant: String, rcNumber: String, pontonInfo: PontonInfo, replyTo: ActorRef[Command]) extends Command
  case class PontonRegisterResponse(status: Int, message: String) extends Command

  val log: Logger = LoggerFactory.getLogger(getClass)

  /** Ponton is registered with KEP only; the mail transport is no longer used. */
  val KepCommType: String = PontonCommType.KEP.toString
  val MailNotSupported: String = "MAIL-Transport wird nicht mehr unterstützt"
  val NoPontonSelected: String = "Keine Ponton-Anbindung gewählt"

  /** Groups of every account of a community: all accounts have the same rights (platform#166). */
  val AccountGroups: List[String] = List("EEG_ADMIN", "EEG_OWNER")

  private val EmailPattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  private val TenantPattern = "^[A-Za-z0-9_-]+$".r

  /** A tenant as it goes into the Keycloak attribute: trimmed, only letters, digits, _ and -. */
  def validTenant(tenant: String): Option[String] = Option(tenant).map(_.trim).filter(t => TenantPattern.findFirstIn(t).isDefined)

  /** Why the account cannot be created, if it cannot. */
  def invalidAccount(user: AccountUser): Option[String] =
    if (user.firstname.trim.isEmpty || user.lastname.trim.isEmpty) Some("Vorname und Nachname erforderlich")
    else if (EmailPattern.findFirstIn(user.email.trim).isEmpty) Some("E-Mail-Adresse ungültig")
    else None

  def trimmed(user: AccountUser): AccountUser = AccountUser(user.firstname.trim, user.lastname.trim, user.email.trim)

  /** Set up the accounts; a failure becomes a result, not an exception. The groups are read once. */
  def setupAccounts(keycloakClient: KeycloakClient, tenant: String, users: List[AccountUser], groups: List[String]): List[AccountResult] = {
    lazy val userGroups = keycloakClient.getGroups.filter(g => groups.contains(g.getName))
    users.map(user => setupAccount(keycloakClient, tenant, user, userGroups))
  }

  private def setupAccount(keycloakClient: KeycloakClient, tenant: String, user: AccountUser,
                           userGroups: => List[org.keycloak.representations.idm.GroupRepresentation]): AccountResult =
    invalidAccount(user) match {
      case Some(reason) => AccountResult(user.email, AccountStatus.failed.toString, reason)
      case None =>
        val u = trimmed(user)
        Try(keycloakClient.ensureAccount(tenant, u, userGroups)) match {
          case Success(status) => AccountResult(u.email, status.toString, "")
          case Failure(e) =>
            log.error(s"Setting up an account for tenant ${tenant.toUpperCase()} failed: ${e.getMessage}")
            AccountResult(u.email, AccountStatus.failed.toString, e.getMessage)
        }
    }

  /** The accounts of a registration without duplicates (e-mail, case-insensitive); None: the old way, a login for the contact. */
  def registrationAccounts(eeg: Eeg): Option[List[AccountUser]] =
    eeg.accounts.filter(_.nonEmpty).map(_.foldLeft(List.empty[AccountUser])((acc, a) =>
      if (acc.exists(_.email.trim.equalsIgnoreCase(a.email.trim))) acc else acc :+ a))

  def registerPonton(eeg: Eeg, pontonClient: RegisterPontonServiceClient)(implicit ec: ExecutionContext): Future[Boolean]  = {
    eeg.pontonInfo.pontonCommType match {
      case PontonCommType.NONE => Future(true)
      case _ if eeg.online =>
        pontonClient.register(
          RegisterPontonRequest(eeg.rcNumber.toUpperCase(), eeg.pontonInfo.username, eeg.pontonInfo.password, eeg.pontonInfo.domain,
            pontonCommType = KepCommType)).map(r => r.status == 200)
      case _ => Future(true)
    }
  }

  def apply(keycloakClient: KeycloakClient): Behavior[Command] = {
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

      // This is the only RegisterService actor: an exception escaping a message
      // must not stop it. Resume keeps the behaviour and the gRPC clients; the
      // actor holds no state that a failed message could leave inconsistent.
      Behaviors.supervise(Behaviors.receiveMessage[Command] { message => {
        message match {
          case RegisterEeg(eeg, _, replyTo) if eeg.online && eeg.pontonInfo.pontonCommType == PontonCommType.MAIL =>
            replyTo ! EegRegisterError(400, MailNotSupported)

          // checked before the community is registered: afterwards it could get no login
          case RegisterEeg(eeg, _, replyTo) if registrationAccounts(eeg).isDefined && validTenant(eeg.tenant).isEmpty =>
            replyTo ! EegRegisterError(400, "Tenant ungültig")

          case RegisterEeg(eeg, groups, replyTo) => {
            lazy val userExists = keycloakClient.checkUserAlreadyExist(eeg.user.email)
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

            context.log.info(s"Register new EEG ${request.rcNumber} (tenant ${request.tenant})")

            (for {
              eegReply <- eegClient.register(request).map(r => r.status == 201)
              pontonReply <- registerPonton(eeg, pontonClient)
            } yield (eegReply && pontonReply)).onComplete {
              // With a list of accounts (platform#166) exactly these get a login, the contact only if listed.
              case Success(state) if state && registrationAccounts(eeg).isDefined =>
                val accounts = registrationAccounts(eeg).get
                replyTo ! EegRegistered(201, "", validTenant(eeg.tenant) match {
                  case Some(tenant) => setupAccounts(keycloakClient, tenant, accounts, groups)
                  case None => accounts.map(a => AccountResult(a.email, AccountStatus.failed.toString, "Tenant ungültig"))
                })
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

          case AddTenantToUser(tenant, email, replyTo) =>
            keycloakClient.getUserId(email) match {
              case Success(id) =>
                Try(keycloakClient.addTenantToUser(id, tenant)) match {
                  case Success(_) => replyTo ! AddTenantToUserResponse(200)
                  case Failure(e) =>
                    context.log.error(s"Adding tenant ${tenant.toUpperCase()} to user $id failed: ${e.getMessage}")
                    replyTo ! AddTenantToUserResponse(500)
                }
              case _ => replyTo ! AddTenantToUserResponse(500)
            }

          case AddAccount(tenant, user, _, replyTo) if validTenant(tenant).isEmpty =>
            replyTo ! AddAccountResponse(400, AccountResult(user.email, AccountStatus.failed.toString, "EEG fehlt oder ungültig"))

          case AddAccount(tenant, user, groups, replyTo) =>
            val result = setupAccounts(keycloakClient, validTenant(tenant).get, List(user), groups).head
            val status = if (result.status != AccountStatus.failed.toString) 200
              else if (invalidAccount(user).isDefined) 400
              else if (KeycloakClient.Refusals.contains(result.message)) 409 else 500
            replyTo ! AddAccountResponse(status, result)

          case ListAccounts(tenant, replyTo) if validTenant(tenant).isEmpty =>
            replyTo ! ListAccountsResponse(400, Nil, "EEG fehlt oder ungültig")

          case ListAccounts(tenant, replyTo) =>
            Try(keycloakClient.listAccounts(validTenant(tenant).get)) match {
              case Success(accounts) => replyTo ! ListAccountsResponse(200, accounts)
              case Failure(e) =>
                log.error(s"Listing the accounts of tenant ${tenant.toUpperCase()} failed: ${e.getMessage}")
                replyTo ! ListAccountsResponse(500, Nil, "Accounts konnten nicht geladen werden")
            }

          case PontonRegister(_, _, pontonInfo, replyTo) if pontonInfo.pontonCommType == PontonCommType.MAIL =>
            replyTo ! PontonRegisterResponse(400, MailNotSupported)

          // NONE is no communication type for eda-xp: registering it would store "NONE" as type.
          case PontonRegister(_, _, pontonInfo, replyTo) if pontonInfo.pontonCommType == PontonCommType.NONE =>
            replyTo ! PontonRegisterResponse(400, NoPontonSelected)

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
                  case Success(update) if update.status == 200 || update.status == 201 =>
                    replyTo ! PontonRegisterResponse(value.status, value.message)
                  case Success(update) => replyTo ! PontonRegisterResponse(update.status, update.message)
                  case Failure(ex) => replyTo ! PontonRegisterResponse(500, ex.getMessage)
                }
              }
              case Success(value) => replyTo ! PontonRegisterResponse(500, value.message)
              case Failure(exception) => replyTo ! PontonRegisterResponse(500, exception.getMessage)
            }
        }
        Behaviors.same
      }
      }).onFailure[Exception](SupervisorStrategy.resume)
    }
  }
}
