package at.ourproject.routes

import at.ourproject.services.RegisterService
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import slick.jdbc.JdbcBackend.Database

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

// The routes are bound on 127.0.0.1:0 and called over HTTP, the same way a probe calls them.
class HealthRoutesSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit()
  private implicit val system: ActorSystem[Nothing] = testKit.system
  private implicit val ec: ExecutionContext = system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private def call(route: Route, uri: String): (HttpResponse, String) = {
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 10.seconds)
    try {
      val port = binding.localAddress.getPort
      val response = Await.result(Http().singleRequest(HttpRequest(uri = s"http://127.0.0.1:$port$uri")), 10.seconds)
      val body = Await.result(response.entity.toStrict(5.seconds), 10.seconds).data.utf8String
      (response, body)
    } finally Await.result(binding.unbind(), 10.seconds)
  }

  "GET /healthz" should {
    "answer 200 ok without a token and without asking the database" in {
      val checks = new AtomicInteger()
      val routes = new HealthRoutes(() => { checks.incrementAndGet(); Future.failed(new RuntimeException("db down")) })
      val (response, body) = call(routes.route, "/healthz")
      response.status shouldBe StatusCodes.OK
      response.entity.contentType shouldBe ContentTypes.`text/plain(UTF-8)`
      body shouldBe "ok"
      checks.get shouldBe 0
    }
  }

  "GET /readyz" should {
    "answer 200 ok when the database check succeeds" in {
      val (response, body) = call(new HealthRoutes(() => Future.successful(1)).route, "/readyz")
      response.status shouldBe StatusCodes.OK
      body shouldBe "ok"
    }

    "answer 503 unavailable without the error text when the database check fails" in {
      val routes = new HealthRoutes(() => Future.failed(new RuntimeException("connection to db-host-17 refused")))
      val (response, body) = call(routes.route, "/readyz")
      response.status shouldBe StatusCodes.ServiceUnavailable
      body shouldBe "unavailable"
    }

    "answer 503 when the database check throws instead of returning a failed future" in {
      val (response, body) = call(new HealthRoutes(() => throw new IllegalStateException("pool closed")).route, "/readyz")
      response.status shouldBe StatusCodes.ServiceUnavailable
      body shouldBe "unavailable"
    }

    "answer 503 when the database check does not answer within the timeout" in {
      val routes = new HealthRoutes(() => Promise[Int]().future, timeout = 200.millis)
      val (response, body) = call(routes.route, "/readyz")
      response.status shouldBe StatusCodes.ServiceUnavailable
      body shouldBe "unavailable"
    }

    "answer 503 when the Slick database cannot reach Postgres" in {
      // nothing listens on port 1; the query of the real check fails to connect
      val db = Database.forURL("jdbc:postgresql://127.0.0.1:1/none?connectTimeout=1", driver = "org.postgresql.Driver")
      try {
        val (response, body) = call(new HealthRoutes(HealthRoutes.selectOne(db)).route, "/readyz")
        response.status shouldBe StatusCodes.ServiceUnavailable
        body shouldBe "unavailable"
      } finally db.close()
    }
  }

  "the protected routes next to the health routes" should {
    "still answer 401 without a token" in {
      // an authenticator that accepts nobody; the dao is never reached
      val authenticator = (_: Any) => Future.successful(Option.empty[AuthenticatedUser])
      val eegRoutes = new EegRoutes(null, authenticator, testKit.createTestProbe[RegisterService.Command]().ref)
      val health = new HealthRoutes(() => Future.successful(1))
      val (response, _) = call(health.route ~ eegRoutes.route, "/vfeeg/eeg")
      response.status shouldBe StatusCodes.Unauthorized
    }
  }
}
