package at.ourproject.services

import at.energydash.admin.{RegisterPontonRequest, RegisterPontonService, RegisterPontonServiceHandler, RegisteredPontonReply}
import at.ourproject.admin.{AdminEegService, AdminEegServiceHandler, UpdateEegReply, UpdateEegRequest}
import at.ourproject.keycloak.KeycloakClient
import at.ourproject.services.RegisterService._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, LoggingTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

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
    override def register(in: RegisterPontonRequest): Future[RegisteredPontonReply] =
      Future.successful(RegisteredPontonReply(status = 200, message = "registered"))
    override def update(in: RegisterPontonRequest): Future[RegisteredPontonReply] =
      Future.successful(RegisteredPontonReply(status = 200))
  }

  private object AdminStub extends AdminEegService {
    @volatile var reply: () => Future[UpdateEegReply] = () => Future.successful(UpdateEegReply(status = 201))
    override def updateValue(in: UpdateEegRequest): Future[UpdateEegReply] = reply()
  }

  private def bindPort(handler: HttpRequest => Future[HttpResponse]): Int =
    Await.result(Http().newServerAt("127.0.0.1", 0).bind(handler), 10.seconds).localAddress.getPort

  private val eegPort = bindPort(AdminEegServiceHandler(AdminStub))
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
      val throwingKeycloak = new KeycloakClient(null, null) {
        override def getUserId(email: String): Try[String] = Success("user-id")
        override def addTenantToUser(userId: String, tenant: String): Unit = ()
        override def findUserByTenant(tenant: String): Try[List[org.keycloak.representations.idm.UserRepresentation]] =
          throw new RuntimeException("unexpected")
      }
      val service = testKit.spawn(RegisterService(throwingKeycloak))
      val probe = testKit.createTestProbe[Command]()

      LoggingTestKit.error[RuntimeException].expect {
        service ! LookupUsersRequest("TE100002", probe.ref)
      }(testKit.system)
      probe.expectNoMessage(500.millis)

      service ! AddTenantToUser("TE100002", "user@test.local", probe.ref)
      probe.expectMessage(10.seconds, AddTenantToUserResponse(200))
    }
  }
}
