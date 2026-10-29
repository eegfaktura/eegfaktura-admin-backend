package at.ourproject.services

import at.ourproject.admin.{AdminEegService, AdminEegServiceHandler, UpdateEegReply, UpdateEegRequest}
import at.ourproject.routes.{UpdateClassEnum, UpdateMessage}
import at.ourproject.services.AdminService._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class AdminServiceSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  // The backend's gRPC admin service as a stub on port 0; the system under test gets the bound port.
  private val stubKit = ActorTestKit("admin-grpc-stub",
    ConfigFactory.parseString("pekko.http.server.preview.enable-http2 = on").withFallback(ConfigFactory.load()))
  private implicit val stubSystem: ActorSystem[Nothing] = stubKit.system

  private object AdminStub extends AdminEegService {
    @volatile var reply: () => Future[UpdateEegReply] = () => Future.successful(UpdateEegReply(status = 200))
    override def updateValue(in: UpdateEegRequest): Future[UpdateEegReply] = reply()
  }

  private val port = Await.result(Http().newServerAt("127.0.0.1", 0).bind(AdminEegServiceHandler(AdminStub)), 10.seconds)
    .localAddress.getPort

  private val testKit = ActorTestKit(ConfigFactory.parseString(
    s"""
       |pekko.grpc.client."register.RegisterEegService".host = "127.0.0.1"
       |pekko.grpc.client."register.RegisterEegService".port = $port
       |""".stripMargin).withFallback(ConfigFactory.load()))

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
    stubKit.shutdownTestKit()
  }

  private def update(updateClass: UpdateClassEnum.Value): UpdateMessage =
    UpdateMessage(updateClass, "TE100002", Some("participant-id"), Some("AT0030000000000000000000000000001"), Map("processState" -> "ACTIVE"))

  "MessageUpdate" should {
    "reply MessageUpdateOk when the backend answers 200 or 201" in {
      val service = testKit.spawn(AdminService())
      val probe = testKit.createTestProbe[Command]()

      AdminStub.reply = () => Future.successful(UpdateEegReply(status = 200))
      service ! MessageUpdate(update(UpdateClassEnum.EEG), probe.ref)
      probe.expectMessage(10.seconds, MessageUpdateOk)

      AdminStub.reply = () => Future.successful(UpdateEegReply(status = 201))
      service ! MessageUpdate(update(UpdateClassEnum.PARTICIPANT), probe.ref)
      probe.expectMessage(10.seconds, MessageUpdateOk)
    }

    "reply MessageUpdateFail with the backend's status when it refuses the update, for every update class" in {
      val service = testKit.spawn(AdminService())
      val probe = testKit.createTestProbe[Command]()
      AdminStub.reply = () => Future.successful(UpdateEegReply(status = 502, message = "update refused"))

      UpdateClassEnum.values.foreach { c =>
        service ! MessageUpdate(update(c), probe.ref)
        probe.expectMessage(10.seconds, MessageUpdateFail(502, "update refused"))
      }
    }

    "reply MessageUpdateFail 500 when the gRPC call fails" in {
      val service = testKit.spawn(AdminService())
      val probe = testKit.createTestProbe[Command]()
      AdminStub.reply = () => Future.failed(new RuntimeException("backend down"))

      service ! MessageUpdate(update(UpdateClassEnum.EEG), probe.ref)
      probe.expectMessageType[MessageUpdateFail](10.seconds).status shouldBe 500
    }
  }
}
