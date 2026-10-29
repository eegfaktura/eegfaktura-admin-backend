package at.ourproject.services

import at.energydash.admin.{RegisterPontonRequest, RegisterPontonService, RegisterPontonServiceHandler, RegisteredPontonReply}
import at.ourproject.admin.{AdminEegService, AdminEegServiceHandler, UpdateEegReply, UpdateEegRequest}
import at.ourproject.keycloak.KeycloakClient
import at.ourproject.register.{EegRequest, EegResponse, RegisterEegRequest, RegisterEegService, RegisterEegServiceHandler, RegisteredEegReply}
import at.ourproject.services.RegisterService._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, LoggingTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.scaladsl.ServiceHandler
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Success, Try}

class RegisterServiceActorSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  // The gRPC stubs run in their own actor system on port 0; the system under
  // test is created afterwards with the bound ports as client config.
  private val stubKit = ActorTestKit("grpc-stubs",
    ConfigFactory.parseString("pekko.http.server.preview.enable-http2 = on").withFallback(ConfigFactory.load()))
  private implicit val stubSystem: ActorSystem[Nothing] = stubKit.system

  private object PontonStub extends RegisterPontonService {
    val registered = new ConcurrentLinkedQueue[RegisterPontonRequest]()
    override def register(in: RegisterPontonRequest): Future[RegisteredPontonReply] = {
      registered.add(in)
      Future.successful(RegisteredPontonReply(status = 200, message = "registered"))
    }
    override def update(in: RegisterPontonRequest): Future[RegisteredPontonReply] =
      Future.successful(RegisteredPontonReply(status = 200))
  }

  private object AdminStub extends AdminEegService {
    @volatile var reply: () => Future[UpdateEegReply] = () => Future.successful(UpdateEegReply(status = 201))
    override def updateValue(in: UpdateEegRequest): Future[UpdateEegReply] = reply()
  }

  private object EegStub extends RegisterEegService {
    val registered = new ConcurrentLinkedQueue[RegisterEegRequest]()
    override def register(in: RegisterEegRequest): Future[RegisteredEegReply] = {
      registered.add(in)
      Future.successful(RegisteredEegReply(status = 201))
    }
    override def eegExists(in: EegRequest): Future[EegResponse] = Future.successful(EegResponse(exists = false))
  }

  private def bindPort(handler: HttpRequest => Future[HttpResponse]): Int =
    Await.result(Http().newServerAt("127.0.0.1", 0).bind(handler), 10.seconds).localAddress.getPort

  // admin and EEG client share the "register.RegisterEegService" client settings
  private val eegPort = bindPort(ServiceHandler.concatOrNotFound(
    AdminEegServiceHandler.partial(AdminStub), RegisterEegServiceHandler.partial(EegStub)))
  private val pontonPort = bindPort(RegisterPontonServiceHandler(PontonStub))

  private val testKit = ActorTestKit(ConfigFactory.parseString(
    s"""
       |pekko.grpc.client."register.RegisterEegService".host = "127.0.0.1"
       |pekko.grpc.client."register.RegisterEegService".port = $eegPort
       |pekko.grpc.client."register.RegisterPontonService".host = "127.0.0.1"
       |pekko.grpc.client."register.RegisterPontonService".port = $pontonPort
       |""".stripMargin).withFallback(ConfigFactory.load()))

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
    stubKit.shutdownTestKit()
  }

  private def keycloak(addTenant: () => Unit): KeycloakClient = new KeycloakClient(null, null) {
    override def getUserId(email: String): Try[String] = Success("user-id")
    override def addTenantToUser(userId: String, tenant: String): Unit = addTenant()
  }

  private def pontonRegister(probe: TestProbe[Command]): PontonRegister =
    PontonRegister("TE100002", "te100002",
      PontonInfo("te100002", "secret", "eda.test", None, None, PontonCommType.KEP), probe.ref)

  "PontonRegister" should {
    "reply with the success status when registration and update succeed (update 201)" in {
      AdminStub.reply = () => Future.successful(UpdateEegReply(status = 201))
      val service = testKit.spawn(RegisterService(null))
      val probe = testKit.createTestProbe[Command]()

      service ! pontonRegister(probe)

      probe.expectMessage(10.seconds, PontonRegisterResponse(200, "registered"))
    }

    "reply with the success status when the update answers 200" in {
      AdminStub.reply = () => Future.successful(UpdateEegReply(status = 200))
      val service = testKit.spawn(RegisterService(null))
      val probe = testKit.createTestProbe[Command]()

      service ! pontonRegister(probe)

      probe.expectMessage(10.seconds, PontonRegisterResponse(200, "registered"))
    }

    "reply with the update status when the EEG update reports a failure" in {
      AdminStub.reply = () => Future.successful(UpdateEegReply(status = 502, message = "update failed"))
      val service = testKit.spawn(RegisterService(null))
      val probe = testKit.createTestProbe[Command]()

      service ! pontonRegister(probe)

      probe.expectMessage(10.seconds, PontonRegisterResponse(502, "update failed"))
    }

    "reply 500 when the EEG update call fails" in {
      AdminStub.reply = () => Future.failed(new RuntimeException("backend down"))
      val service = testKit.spawn(RegisterService(null))
      val probe = testKit.createTestProbe[Command]()

      service ! pontonRegister(probe)

      probe.expectMessageType[PontonRegisterResponse](10.seconds).status shouldBe 500
    }
  }

  private def eeg(online: Boolean, commType: PontonCommType.Value): Eeg =
    Eeg("te100002", "te100002", "UNITTEST", "UNIT TEST REGISTRATION", "", online,
      AccountInfo(iban = "ATIBAN000111", owner = "Tester", sepa = false),
      BusinessInfo(legal = EegLegalType.verein, settlementInterval = EegSettlementType.MONTHLY),
      Grid(id = "AT03001", name = "NETZ GR", area = GridAreaType.LOCAL, allocation = GridAllocationType.DYNAMIC),
      Contact(street = "Allee", streetNumber = "1a", city = "Solarvilage", zip = "3456", web = None, phone = None),
      PontonInfo(username = "te100002", password = "secret", domain = "eda.test", host = None, port = None, pontonCommType = commType),
      UserInfo(username = "tester", password = "", firstname = "Tester", lastname = "Korrekt", email = "tester@test.local"))

  private val newUserKeycloak: KeycloakClient = new KeycloakClient(null, null) {
    override def checkUserAlreadyExist(email: String): Boolean = false
    override def getGroups: List[org.keycloak.representations.idm.GroupRepresentation] = Nil
    override def createUser(user: KeycloakClient.User, rcNumber: String,
                            userGroups: List[org.keycloak.representations.idm.GroupRepresentation]): Unit = ()
  }

  "RegisterEeg" should {
    "register an online EEG at Ponton with type KEP" in {
      PontonStub.registered.clear()
      val service = testKit.spawn(RegisterService(newUserKeycloak))
      val probe = testKit.createTestProbe[Command]()

      service ! RegisterEeg(eeg(online = true, PontonCommType.KEP), Nil, probe.ref)

      probe.expectMessage(10.seconds, EegRegistered(201, ""))
      PontonStub.registered.size shouldBe 1
      PontonStub.registered.peek().pontonCommType shouldBe "KEP"
    }

    "reject an online EEG with MAIL transport before registering anything" in {
      PontonStub.registered.clear()
      EegStub.registered.clear()
      val service = testKit.spawn(RegisterService(newUserKeycloak))
      val probe = testKit.createTestProbe[Command]()

      service ! RegisterEeg(eeg(online = true, PontonCommType.MAIL), Nil, probe.ref)

      probe.expectMessage(10.seconds, EegRegisterError(400, MailNotSupported))
      EegStub.registered.size shouldBe 0
      PontonStub.registered.size shouldBe 0
    }

    "answer 510 and create no user when Keycloak cannot tell whether the user exists" in {
      val created = new java.util.concurrent.atomic.AtomicBoolean(false)
      val kc = new KeycloakClient(null, null) {
        override def checkUserAlreadyExist(email: String): Boolean = throw new RuntimeException("keycloak down")
        override def getGroups: List[org.keycloak.representations.idm.GroupRepresentation] = Nil
        override def createUser(user: KeycloakClient.User, rcNumber: String,
                                userGroups: List[org.keycloak.representations.idm.GroupRepresentation]): Unit = created.set(true)
      }
      val service = testKit.spawn(RegisterService(kc))
      val probe = testKit.createTestProbe[Command]()

      service ! RegisterEeg(eeg(online = false, PontonCommType.NONE), Nil, probe.ref)

      probe.expectMessageType[EegRegisterError](10.seconds).code shouldBe 510
      created.get shouldBe false
    }

    "not register at Ponton for type NONE" in {
      PontonStub.registered.clear()
      val service = testKit.spawn(RegisterService(newUserKeycloak))
      val probe = testKit.createTestProbe[Command]()

      service ! RegisterEeg(eeg(online = true, PontonCommType.NONE), Nil, probe.ref)

      probe.expectMessage(10.seconds, EegRegistered(201, ""))
      PontonStub.registered.size shouldBe 0
    }
  }

  private class AccountKeycloak(existing: Set[String], failing: Set[String] = Set.empty) extends KeycloakClient(null, null) {
    val created = new ConcurrentLinkedQueue[String]()
    override def checkUserAlreadyExist(email: String): Boolean = false
    override def getGroups: List[org.keycloak.representations.idm.GroupRepresentation] = Nil
    override def createUser(user: KeycloakClient.User, rcNumber: String,
                            userGroups: List[org.keycloak.representations.idm.GroupRepresentation]): Unit = ()
    override def ensureAccount(tenant: String, user: AccountUser,
                               userGroups: List[org.keycloak.representations.idm.GroupRepresentation]): at.ourproject.keycloak.AccountStatus.Value =
      if (failing.contains(user.email)) throw new RuntimeException("keycloak down")
      else if (user.email.startsWith("member")) throw new IllegalStateException(KeycloakClient.LessRights)
      else if (existing.contains(user.email)) at.ourproject.keycloak.AccountStatus.linked
      else { created.add(user.email); at.ourproject.keycloak.AccountStatus.created }
    override def listAccounts(tenant: String): List[KeycloakClient.AccountView] =
      if (tenant == "BROKEN") throw new RuntimeException("keycloak down")
      else List(KeycloakClient.AccountView("ottan", "Anna", "Ott", "anna@test.local", enabled = true, passwordPending = true))
  }

  "RegisterEeg with a list of accounts" should {
    "set up exactly the listed accounts and report the result per account" in {
      val kc = new AccountKeycloak(existing = Set("linked@test.local"), failing = Set("fails@test.local")) {
        override def createUser(user: KeycloakClient.User, rcNumber: String,
                                userGroups: List[org.keycloak.representations.idm.GroupRepresentation]): Unit =
          throw new RuntimeException("the contact must not get a login")
      }
      val service = testKit.spawn(RegisterService(kc))
      val probe = testKit.createTestProbe[Command]()
      val withAccounts = eeg(online = false, PontonCommType.NONE).copy(accounts = Some(List(
        AccountUser("Neu", "Account", "new@test.local"),
        AccountUser("Alt", "Account", "linked@test.local"),
        AccountUser("Kaputt", "Account", "fails@test.local"),
        AccountUser("Ohne", "Mail", "keine-mail"),
        AccountUser("Neu", "Account", "NEW@test.local"))))

      service ! RegisterEeg(withAccounts, Nil, probe.ref)

      val reply = probe.expectMessageType[EegRegistered](10.seconds)
      reply.code shouldBe 201
      reply.accounts.map(r => (r.email, r.status)) shouldBe List(
        ("new@test.local", "created"), ("linked@test.local", "linked"), ("fails@test.local", "failed"), ("keine-mail", "failed"))
      kc.created.size shouldBe 1
    }
  }

  "RegisterEeg with a list and an invalid tenant" should {
    "be refused before the community is registered" in {
      EegStub.registered.clear()
      val service = testKit.spawn(RegisterService(new AccountKeycloak(existing = Set.empty)))
      val probe = testKit.createTestProbe[Command]()

      service ! RegisterEeg(eeg(online = false, PontonCommType.NONE).copy(tenant = "TE 100002",
        accounts = Some(List(AccountUser("Neu", "Account", "new@test.local")))), Nil, probe.ref)

      probe.expectMessage(10.seconds, EegRegisterError(400, "Tenant ungültig"))
      EegStub.registered.size shouldBe 0
    }
  }

  "AddAccount" should {
    "reply 200 with the result" in {
      val service = testKit.spawn(RegisterService(new AccountKeycloak(existing = Set("linked@test.local"))))
      val probe = testKit.createTestProbe[Command]()

      service ! AddAccount("TE100002", AccountUser("Alt", "Account", "linked@test.local"), AccountGroups, probe.ref)
      probe.expectMessage(10.seconds, AddAccountResponse(200, AccountResult("linked@test.local", "linked", "")))
    }

    "reply 400 for invalid input or a missing tenant, 500 when Keycloak fails" in {
      val service = testKit.spawn(RegisterService(new AccountKeycloak(existing = Set.empty, failing = Set("fails@test.local"))))
      val probe = testKit.createTestProbe[Command]()

      service ! AddAccount("TE100002", AccountUser("", "Account", "x@test.local"), AccountGroups, probe.ref)
      probe.expectMessageType[AddAccountResponse](10.seconds).status shouldBe 400
      service ! AddAccount(" ", AccountUser("Neu", "Account", "x@test.local"), AccountGroups, probe.ref)
      probe.expectMessageType[AddAccountResponse](10.seconds).status shouldBe 400
      service ! AddAccount("""TE100002","RC999999""", AccountUser("Neu", "Account", "x@test.local"), AccountGroups, probe.ref)
      probe.expectMessageType[AddAccountResponse](10.seconds).status shouldBe 400
      service ! AddAccount("TE100002", AccountUser("Kaputt", "Account", "fails@test.local"), AccountGroups, probe.ref)
      probe.expectMessageType[AddAccountResponse](10.seconds).status shouldBe 500
      service ! AddAccount("TE100002", AccountUser("Mit", "Glied", "member@test.local"), AccountGroups, probe.ref)
      val refused = probe.expectMessageType[AddAccountResponse](10.seconds)
      refused.status shouldBe 409
      refused.result.message shouldBe KeycloakClient.LessRights
    }
  }

  "ListAccounts" should {
    "reply with the accounts, or 500 when Keycloak fails" in {
      val service = testKit.spawn(RegisterService(new AccountKeycloak(existing = Set.empty)))
      val probe = testKit.createTestProbe[Command]()

      service ! ListAccounts("TE100002", probe.ref)
      probe.expectMessageType[ListAccountsResponse](10.seconds).accounts.map(_.email) shouldBe List("anna@test.local")
      service ! ListAccounts("BROKEN", probe.ref)
      probe.expectMessageType[ListAccountsResponse](10.seconds).status shouldBe 500
      service ! ListAccounts("", probe.ref)
      probe.expectMessageType[ListAccountsResponse](10.seconds).status shouldBe 400
    }
  }

  "PontonRegister with MAIL transport" should {
    "be rejected without calling Ponton" in {
      PontonStub.registered.clear()
      val service = testKit.spawn(RegisterService(null))
      val probe = testKit.createTestProbe[Command]()

      service ! PontonRegister("TE100002", "te100002",
        PontonInfo("te100002", "secret", "eda.test", None, None, PontonCommType.MAIL), probe.ref)

      probe.expectMessage(10.seconds, PontonRegisterResponse(400, MailNotSupported))
      PontonStub.registered.size shouldBe 0
    }
  }

  "PontonRegister with type NONE" should {
    "be rejected without calling Ponton or the backend" in {
      PontonStub.registered.clear()
      val updated = new java.util.concurrent.atomic.AtomicBoolean(false)
      AdminStub.reply = () => { updated.set(true); Future.successful(UpdateEegReply(status = 201)) }
      val service = testKit.spawn(RegisterService(null))
      val probe = testKit.createTestProbe[Command]()

      service ! PontonRegister("TE100002", "te100002",
        PontonInfo("te100002", "secret", "eda.test", None, None, PontonCommType.NONE), probe.ref)

      probe.expectMessage(10.seconds, PontonRegisterResponse(400, NoPontonSelected))
      PontonStub.registered.size shouldBe 0
      updated.get shouldBe false
    }
  }

  "AddTenantToUser" should {
    "reply 200 when Keycloak succeeds" in {
      val service = testKit.spawn(RegisterService(keycloak(() => ())))
      val probe = testKit.createTestProbe[Command]()

      service ! AddTenantToUser("TE100002", "user@test.local", probe.ref)
      probe.expectMessage(10.seconds, AddTenantToUserResponse(200))
    }

    "reply with an error and keep serving when Keycloak fails" in {
      val service = testKit.spawn(RegisterService(keycloak(() => throw new RuntimeException("keycloak down"))))
      val probe = testKit.createTestProbe[Command]()

      service ! AddTenantToUser("TE100002", "user@test.local", probe.ref)
      probe.expectMessage(10.seconds, AddTenantToUserResponse(500))

      // the actor is still alive and answers the next request
      service ! AddTenantToUser("TE100002", "user@test.local", probe.ref)
      probe.expectMessage(10.seconds, AddTenantToUserResponse(500))
    }
  }

  "RegisterService" should {
    "keep running after an exception escapes a message" in {
      // getUserId is called in the message handler itself; the first call throws out of it
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)
      val throwingKeycloak = new KeycloakClient(null, null) {
        override def getUserId(email: String): Try[String] =
          if (calls.getAndIncrement() == 0) throw new RuntimeException("unexpected") else Success("user-id")
        override def addTenantToUser(userId: String, tenant: String): Unit = ()
      }
      val service = testKit.spawn(RegisterService(throwingKeycloak))
      val probe = testKit.createTestProbe[Command]()

      LoggingTestKit.error[RuntimeException].expect {
        service ! AddTenantToUser("TE100002", "user@test.local", probe.ref)
      }(testKit.system)
      probe.expectNoMessage(500.millis)

      service ! AddTenantToUser("TE100002", "user@test.local", probe.ref)
      probe.expectMessage(10.seconds, AddTenantToUserResponse(200))
    }
  }
}
