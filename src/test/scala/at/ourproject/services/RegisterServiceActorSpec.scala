package at.ourproject.services

import at.energydash.admin.{RegisterPontonRequest, RegisterPontonService, RegisterPontonServiceHandler, RegisteredPontonReply}
import at.ourproject.admin.{AdminEegService, AdminEegServiceHandler, UpdateEegReply, UpdateEegRequest}
import at.ourproject.services.RegisterService._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.ServerSocket
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class RegisterServiceActorSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort finally socket.close()
  }

  private val eegPort = freePort()
  private val pontonPort = freePort()

  private val testKit = ActorTestKit(ConfigFactory.parseString(
    s"""
       |pekko.http.server.preview.enable-http2 = on
       |pekko.grpc.client."register.RegisterEegService".port = $eegPort
       |pekko.grpc.client."register.RegisterPontonService".port = $pontonPort
       |""".stripMargin).withFallback(ConfigFactory.load()))

  private implicit val system: org.apache.pekko.actor.typed.ActorSystem[Nothing] = testKit.system

  private object PontonStub extends RegisterPontonService {
    override def register(in: RegisterPontonRequest): Future[RegisteredPontonReply] =
      Future.successful(RegisteredPontonReply(status = 200, message = "registered"))
    override def update(in: RegisterPontonRequest): Future[RegisteredPontonReply] =
      Future.successful(RegisteredPontonReply(status = 200))
  }

  private object AdminStub extends AdminEegService {
    override def updateValue(in: UpdateEegRequest): Future[UpdateEegReply] =
      Future.successful(UpdateEegReply(status = 200))
  }

  override def beforeAll(): Unit = {
    Await.result(Http().newServerAt("127.0.0.1", eegPort).bind(AdminEegServiceHandler(AdminStub)), 10.seconds)
    Await.result(Http().newServerAt("127.0.0.1", pontonPort).bind(RegisterPontonServiceHandler(PontonStub)), 10.seconds)
  }

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "PontonRegister" should {
    "reply with the success status when registration and update succeed" in {
      val service = testKit.spawn(RegisterService(null))
      val probe = testKit.createTestProbe[Command]()

      service ! PontonRegister("TE100002", "te100002",
        PontonInfo("te100002", "secret", "eda.test", None, None, PontonCommType.KEP), probe.ref)

      probe.expectMessage(10.seconds, PontonRegisterResponse(200, "registered"))
    }
  }
}
