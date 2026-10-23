package at.ourproject.services

import at.ourproject.register._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.http.scaladsl.Http
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

// The service talks to its gRPC peers with the generated pekko-grpc clients over Netty
// (grpc-netty-shaded). The io.grpc libraries are pinned newer than pekko-grpc-runtime 1.1.1 brings
// (platform#183), so this round trip - generated client over h2c to the generated server
// handler, on 127.0.0.1 only - checks that the combination works.
class GrpcClientRoundTripSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.empty[Nothing],
    "GrpcClientRoundTripSpec",
    ConfigFactory.parseString("pekko.http.server.preview.enable-http2 = on").withFallback(ConfigFactory.load())
  )

  override def afterAll(): Unit = system.terminate()

  private val service = new RegisterEegService {
    override def register(in: RegisterEegRequest): Future[RegisteredEegReply] =
      Future.successful(RegisteredEegReply())
    override def eegExists(in: EegRequest): Future[EegResponse] =
      Future.successful(EegResponse(exists = in.rcNumber == "RC100001"))
  }

  "A generated gRPC client" should {
    "reach the generated server handler over h2c" in {
      val binding = Await.result(
        Http().newServerAt("127.0.0.1", 0).bind(RegisterEegServiceHandler(service)(system)), 10.seconds)
      val client = RegisterEegServiceClient(
        GrpcClientSettings.connectToServiceAt("127.0.0.1", binding.localAddress.getPort).withTls(false))
      try {
        Await.result(client.eegExists(EegRequest("RC100001")), 10.seconds).exists shouldBe true
        Await.result(client.eegExists(EegRequest("RC100002")), 10.seconds).exists shouldBe false
      } finally {
        Await.result(client.close(), 10.seconds)
        Await.result(binding.unbind(), 10.seconds)
      }
    }
  }
}
