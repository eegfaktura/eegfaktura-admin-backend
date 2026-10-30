package at.ourproject.routes

import at.ourproject.services.RegisterService
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpMethod, HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.{InetAddress, ServerSocket, Socket}
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

// The routes are bound on 127.0.0.1:0 and called over HTTP, the same way a probe calls them.
class HealthRoutesSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit("HealthRoutesSpec", ConfigFactory.load())
  private implicit val system: ActorSystem[Nothing] = testKit.system
  private implicit val ec: ExecutionContext = system.executionContext
  private val sockets = ListBuffer[java.io.Closeable]()

  override def afterAll(): Unit = {
    sockets.foreach(s => Try(s.close()))
    testKit.shutdownTestKit()
  }

  private def bind(route: Route): Int =
    Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 10.seconds).localAddress.getPort

  private def request(port: Int, uri: String, method: HttpMethod = HttpMethods.GET): Future[(HttpResponse, String)] =
    Http().singleRequest(HttpRequest(method = method, uri = s"http://127.0.0.1:$port$uri")).flatMap { response =>
      response.entity.toStrict(5.seconds).map(e => (response, e.data.utf8String))
    }

  private def call(route: Route, uri: String, method: HttpMethod = HttpMethods.GET): (HttpResponse, String) =
    Await.result(request(bind(route), uri, method), 10.seconds)

  // A local TCP server: `answer` decides what happens with an accepted connection.
  private def tcpServer(answer: Socket => Unit): Int = {
    val server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    sockets += server
    val t = new Thread(() => while (!server.isClosed) Try(server.accept()).foreach { s => sockets += s; answer(s) })
    t.setDaemon(true)
    t.start()
    server.getLocalPort
  }

  private def pingProperties(port: Int) = ConfigFactory.parseString(
    s"""serverName = "127.0.0.1", portNumber = "$port", databaseName = "none", user = "none", password = "none"""")

  private def elapsedMillis[T](f: => T): (Try[T], Long) = {
    val start = System.nanoTime
    val result = Try(f)
    (result, (System.nanoTime - start) / 1000000)
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

    "run one check for concurrent callers and share its result" in {
      val checks = new AtomicInteger()
      val routes = new HealthRoutes(() => {
        checks.incrementAndGet()
        org.apache.pekko.pattern.after(300.millis)(Future.successful(1))
      })
      val port = bind(routes.route)
      val answers = Await.result(Future.sequence((1 to 5).map(_ => request(port, "/readyz"))), 10.seconds)
      answers.map(_._1.status).distinct shouldBe Seq(StatusCodes.OK)
      checks.get shouldBe 1
    }

    "reuse a result younger than a second and check again after that" in {
      val checks = new AtomicInteger()
      val port = bind(new HealthRoutes(() => { checks.incrementAndGet(); Future.successful(1) }).route)
      Await.result(request(port, "/readyz"), 10.seconds)
      Await.result(request(port, "/readyz"), 10.seconds)
      checks.get shouldBe 1
      Thread.sleep(1100)
      Await.result(request(port, "/readyz"), 10.seconds)
      checks.get shouldBe 2
    }
  }

  "the Postgres ping" should {
    "fail fast by itself when the server closes the connection" in {
      val port = tcpServer(_.close())
      val (result, millis) = elapsedMillis(Await.result(HealthRoutes.postgresPing(pingProperties(port))(), 10.seconds))
      result.isFailure shouldBe true
      result.failed.get should not be a[TimeoutException]
      millis should be < 1000L

      val (response, body) = call(new HealthRoutes(HealthRoutes.postgresPing(pingProperties(port))).route, "/readyz")
      response.status shouldBe StatusCodes.ServiceUnavailable
      body shouldBe "unavailable"
    }

    "give up by its own driver timeouts when the server never answers" in {
      val port = tcpServer(_ => ()) // accepts and stays silent
      val (result, millis) = elapsedMillis(Await.result(HealthRoutes.postgresPing(pingProperties(port))(), 15.seconds))
      result.isFailure shouldBe true
      result.failed.get should not be a[TimeoutException] // not the Await above
      millis should be < 5000L
    }
  }

  "the protected routes next to the health routes" should {
    // an authenticator that accepts nobody; the dao and the actor are never reached
    val authenticator = (_: Any) => Future.successful(Option.empty[AuthenticatedUser])
    lazy val combined = {
      val node = testKit.createTestProbe[RegisterService.Command]().ref
      new HealthRoutes(() => Future.successful(1)).route ~
        new RegistrationRoutes(authenticator, node).route ~
        new EegRoutes(null, authenticator, node).route
    }

    "still answer 401 to a GET without a token" in {
      call(combined, "/vfeeg/eeg")._1.status shouldBe StatusCodes.Unauthorized
    }

    "still answer 401 to a POST without a token" in {
      call(combined, "/eeg/register", HttpMethods.POST)._1.status shouldBe StatusCodes.Unauthorized
    }

    "still answer 404 to a POST on an unknown path" in {
      call(combined, "/unknown", HttpMethods.POST)._1.status shouldBe StatusCodes.NotFound
    }
  }
}
