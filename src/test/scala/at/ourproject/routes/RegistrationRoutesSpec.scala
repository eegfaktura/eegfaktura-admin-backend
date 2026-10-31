package at.ourproject.routes

import at.ourproject.services.RegisterService
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, StatusCodes, headers}
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class RegistrationRoutesSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit("RegistrationRoutesSpec", ConfigFactory.load())
  private implicit val system: ActorSystem[Nothing] = testKit.system
  private implicit val ec: ExecutionContext = system.executionContext

  private val refusal = RegisterService.EegRegisterError(409, RegisterService.eegAlreadyRegistered("te100002"))

  private val refusingNode: Behavior[RegisterService.Command] = Behaviors.receiveMessage {
    case RegisterService.RegisterEeg(_, _, replyTo) =>
      replyTo ! refusal
      Behaviors.same
    case _ => Behaviors.same
  }

  private val superuser: Credentials => Future[Option[AuthenticatedUser]] = {
    case _: Credentials.Provided =>
      Future.successful(Some(AuthenticatedUser("sub", None, Map("realm_access" ->
        java.util.Map.of("roles", java.util.List.of(RoleDirectives.SuperuserRole))))))
    case _ => Future.successful(None)
  }

  private val port = Await.result(Http().newServerAt("127.0.0.1", 0)
    .bind(new RegistrationRoutes(superuser, testKit.spawn(refusingNode)).route), 10.seconds).localAddress.getPort

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val eeg =
    """{"tenant":"te100002","rcNumber":"te100002","communityId":"UNITTEST","name":"UNIT TEST","description":"","online":false,
      |"accountInfo":{"iban":"AT","owner":"Tester","sepa":false},
      |"businessInfo":{"legal":"verein","settlementInterval":"MONTHLY"},
      |"grid":{"id":"AT03001","name":"NETZ","area":"LOCAL","allocation":"DYNAMIC"},
      |"contact":{"street":"Allee","streetNumber":"1a","city":"Stadt","zip":"3456"},
      |"pontonInfo":{"username":"u","password":"p","domain":"d","pontonCommType":"NONE"},
      |"user":{"username":"t","password":"","firstname":"T","lastname":"K","email":"t@test.local"}}""".stripMargin

  "POST /eeg/register" should {
    "answer 409 with code and message for a tenant that exists (261015-ca1)" in {
      val response = Await.result(Http().singleRequest(HttpRequest(HttpMethods.POST, s"http://127.0.0.1:$port/eeg/register",
        List(headers.Authorization(OAuth2BearerToken("token"))), HttpEntity(ContentTypes.`application/json`, eeg))), 10.seconds)

      response.status shouldBe StatusCodes.Conflict
      val body = Await.result(response.entity.toStrict(5.seconds), 10.seconds).data.utf8String
      body should include(""""code":409""")
      body should include(refusal.message)
    }
  }
}
