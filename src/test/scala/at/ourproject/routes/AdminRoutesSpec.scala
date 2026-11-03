package at.ourproject.routes

import at.ourproject.dao.{Dao, Db, EegMeteringPoint, EegParticipant, MetersRepository, ParticipantsRepository}
import at.ourproject.services.AdminService
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, headers}
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import slick.jdbc.PostgresProfile

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

// The rows read back after an accepted update: found 200 with the row, not found 404 (was 500).
class AdminRoutesSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit("AdminRoutesSpec", ConfigFactory.load())
  private implicit val system: ActorSystem[Nothing] = testKit.system
  private implicit val ec: ExecutionContext = system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val superuser: Credentials => Future[Option[AuthenticatedUser]] = {
    case _: Credentials.Provided =>
      Future.successful(Some(AuthenticatedUser("sub", None, Map("realm_access" ->
        java.util.Map.of("roles", java.util.List.of(RoleDirectives.SuperuserRole))))))
    case _ => Future.successful(None)
  }

  // the backend accepts every update
  private val admin = testKit.spawn(Behaviors.receiveMessage[AdminService.Command] {
    case AdminService.MessageUpdate(_, replyTo) => replyTo ! AdminService.MessageUpdateOk; Behaviors.same
    case _ => Behaviors.same
  })

  private val meter = EegMeteringPoint("TE100002", "AT0030000000000000000000000000001", "CONSUMPTION", "ACTIVE", None, "p-1",
    "ACTIVE", None, None)
  private val participant = EegParticipant("p-1", "TE100002", "Max", "Muster", "EEG_PRIVATE", "ACTIVE", Seq(meter))

  // no database: the two repositories answer from the given rows
  private def daos(p: Option[EegParticipant], m: Option[EegMeteringPoint]): Dao = new Dao with Db {
    override val db = null
    override val profile = PostgresProfile
    override def executionContext: ExecutionContext = ec
    override def participantRepository: ParticipantsRepository = new ParticipantsRepository(null, PostgresProfile) {
      override def getById(id: String): Future[Option[EegParticipant]] = Future.successful(p.filter(_.id == id))
    }
    override def meteringRepository: MetersRepository = new MetersRepository(null, PostgresProfile) {
      override def getMeteringPoint(tenant: String, participantId: String, meteringPoint: String): Future[Option[EegMeteringPoint]] =
        Future.successful(m)
    }
  }

  private def post(dao: Dao, uri: String, body: String): HttpResponse = {
    val port = Await.result(Http().newServerAt("127.0.0.1", 0).bind(new AdminRoutes(dao, superuser, admin).route), 10.seconds)
      .localAddress.getPort
    Await.result(Http().singleRequest(HttpRequest(HttpMethods.POST, s"http://127.0.0.1:$port$uri",
      List(headers.Authorization(OAuth2BearerToken("token"))), HttpEntity(ContentTypes.`application/json`, body))), 10.seconds)
  }

  private def bodyOf(response: HttpResponse): String =
    Await.result(response.entity.toStrict(5.seconds), 10.seconds).data.utf8String

  private val participantUpdate =
    """{"updateClass":"PARTICIPANT","tenant":"TE100002","participantId":"p-1","value":{"firstname":"Max"}}"""
  private val meterUpdate =
    """{"updateClass":"PROCESSSTATUS","tenant":"TE100002","participantId":"p-1","meteringPoint":"AT0030000000000000000000000000001","value":{"status":"ACTIVE"}}"""

  "POST /admin/master/update/participant" should {
    "answer 200 with the participant and its meters" in {
      val response = post(daos(Some(participant), None), "/admin/master/update/participant", participantUpdate)
      response.status shouldBe StatusCodes.OK
      val body = bodyOf(response)
      body should include("\"id\":\"p-1\"")
      body should include("AT0030000000000000000000000000001")
    }

    "answer 404 when the participant is not found" in {
      val response = post(daos(None, None), "/admin/master/update/participant", participantUpdate)
      response.status shouldBe StatusCodes.NotFound
      response.discardEntityBytes()
    }
  }

  "POST /admin/master/update" should {
    "answer 200 with the metering point" in {
      val response = post(daos(None, Some(meter)), "/admin/master/update", meterUpdate)
      response.status shouldBe StatusCodes.OK
      bodyOf(response) should include("\"meteringPoint\":\"AT0030000000000000000000000000001\"")
    }

    "answer 404 when the metering point is not found" in {
      val response = post(daos(None, None), "/admin/master/update", meterUpdate)
      response.status shouldBe StatusCodes.NotFound
      response.discardEntityBytes()
    }
  }
}
