package at.ourproject.logging

import at.ourproject.routes.{AuthenticatedUser, EegRoutes, HealthRoutes, RegistrationRoutes}
import at.ourproject.services.RegisterService
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger => LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken, RawHeader}
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import org.apache.pekko.http.scaladsl.settings.ServerSettings
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

// The access line through the real routers (bound on 127.0.0.1:0, called over HTTP), as the service wires them.
class AccessLogSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit("AccessLogSpec", ConfigFactory.load())
  private implicit val system: ActorSystem[Nothing] = testKit.system
  private implicit val ec: ExecutionContext = system.executionContext

  private val appender = new ListAppender[ILoggingEvent]
  private val accessLogger = LoggerFactory.getLogger("at.ourproject.access").asInstanceOf[LogbackLogger]

  override def beforeAll(): Unit = {
    appender.start()
    accessLogger.addAppender(appender)
  }

  override def afterAll(): Unit = {
    accessLogger.detachAppender(appender)
    testKit.shutdownTestKit()
  }

  private val Client = "at.ourproject.vfeeg.admin"

  // personal data a token carries; none of it may reach the access line
  private def user(roles: String*) = AuthenticatedUser("4711-user-uuid", Some("max.mustermann"), Map(
    "azp" -> Client, "sub" -> "4711-user-uuid", "preferred_username" -> "max.mustermann", "email" -> "max@example.org",
    "realm_access" -> Map[String, Any]("roles" -> roles.asJava).asJava))

  private val authenticator: Credentials => Future[Option[AuthenticatedUser]] = {
    case Credentials.Provided("superuser") => Future.successful(Some(user("superuser")))
    case Credentials.Provided("member") => Future.successful(Some(user("EEG_ADMIN")))
    case _ => Future.successful(None)
  }

  private val node = testKit.spawn(Behaviors.receiveMessage[RegisterService.Command] {
    case RegisterService.ListAccounts(_, replyTo) => replyTo ! RegisterService.ListAccountsResponse(200, Nil); Behaviors.same
    case RegisterService.AddTenantToUser(_, _, replyTo) => replyTo ! RegisterService.AddTenantToUserResponse(200); Behaviors.same
    case _ => Behaviors.same
  })

  private lazy val port = {
    val routes = AccessLog.accessLog(
      new HealthRoutes(() => Future.successful(1)).route ~
        new RegistrationRoutes(authenticator, node).route ~
        new EegRoutes(null, authenticator, node).route)
    Await.result(Http().newServerAt("127.0.0.1", 0).bind(routes), 10.seconds).localAddress.getPort
  }

  private val encoder = { val e = new EcsEncoder; e.setFormat("json"); e }

  /** Calls the service; the access lines written for this call, encoded as the image writes them. */
  private def call(uri: String, headers: HttpHeader*): (HttpResponse, List[(String, JsonNode)]) =
    callAt(port, HttpRequest(uri = uri, headers = headers.toList))

  private def callAt(port: Int, request: HttpRequest): (HttpResponse, List[(String, JsonNode)]) = {
    appender.list.clear()
    val response = Await.result(
      Http().singleRequest(request.withUri(s"http://127.0.0.1:$port${request.uri}"))
        .flatMap(r => r.entity.discardBytes().future.map(_ => r)), 10.seconds)
    (response, lines())
  }

  /** The access lines written since the last call, encoded as the image writes them. */
  private def lines(): List[(String, JsonNode)] = appender.list.asScala.toList.map { e =>
    val raw = new String(encoder.encode(e), StandardCharsets.UTF_8)
    raw -> new ObjectMapper().readTree(raw)
  }

  private val Allowed = Set("@timestamp", "log.level", "log.logger", "service.name", "service.version", "message",
    "event.action", "event.outcome", "event.duration", "http.request.method", "http.route", "http.response.status_code",
    "request.id", "trace.id", "span.id", "tenant.id", "client.id")

  // no user, e-mail, name, user agent, address or query values (no user tracking)
  private def assertNoPersonalData(raw: String): Unit =
    Seq("4711-user-uuid", "mustermann", "example.org", "secret-agent", "203.0.113.7", "127.0.0.1", "tenant=", "?")
      .foreach(s => raw should not include s)

  private val Bearer = (t: String) => Authorization(OAuth2BearerToken(t))
  private val Trace = "4bf92f3577b34da6a3ce929d0e0e4736"
  private val Span = "00f067aa0ba902b7"

  "the access line" should {
    "carry route template, tenant, client and correlation ids, and nothing about the person" in {
      val (response, lines) = call("/eeg/accounts?tenant=rc100200", Bearer("superuser"),
        RawHeader("X-Request-Id", "req-42.a:b"), RawHeader("traceparent", s"00-$Trace-$Span-01"),
        RawHeader("User-Agent", "secret-agent/1.0"), RawHeader("X-Forwarded-For", "203.0.113.7"))
      response.status.intValue shouldBe 200
      response.headers.find(_.is("x-request-id")).map(_.value) shouldBe Some("req-42.a:b")
      lines should have size 1
      val (raw, j) = lines.head
      j.get("event.action").asText shouldBe "http.access"
      j.get("event.outcome").asText shouldBe "success"
      j.get("http.request.method").asText shouldBe "GET"
      j.get("http.route").asText shouldBe "/eeg/accounts"
      j.get("http.response.status_code").asInt shouldBe 200
      j.get("event.duration").isIntegralNumber shouldBe true
      j.get("event.duration").asLong should be > 0L
      j.get("request.id").asText shouldBe "req-42.a:b"
      j.get("trace.id").asText shouldBe Trace
      j.get("span.id").asText shouldBe Span
      j.get("tenant.id").asText shouldBe "RC100200"
      j.get("client.id").asText shouldBe Client
      j.get("message").asText shouldBe "GET /eeg/accounts 200"
      j.fieldNames.asScala.toSet -- Allowed shouldBe empty
      assertNoPersonalData(raw)
    }

    "not carry the e-mail address of a query" in {
      val (response, lines) = call("/eeg/addTenant?tenant=RC100200&email=max%40example.org", Bearer("superuser"))
      response.status.intValue shouldBe 200
      lines.head._2.get("http.route").asText shouldBe "/eeg/addTenant"
      assertNoPersonalData(lines.head._1)
    }

    "mark a request without token as refused, without route, tenant or client" in {
      val (response, lines) = call("/vfeeg/participants?tenant=RC100200")
      response.status.intValue shouldBe 401
      val j = lines.head._2
      j.get("event.outcome").asText shouldBe "refused"
      j.get("http.response.status_code").asInt shouldBe 401
      Seq("http.route", "tenant.id", "client.id").foreach(f => j.has(f) shouldBe false)
      j.get("message").asText shouldBe "GET - 401"
    }

    "mark a token without the superuser role as refused, with its client" in {
      val (response, lines) = call("/vfeeg/eeg", Bearer("member"))
      response.status.intValue shouldBe 403
      lines.head._2.get("event.outcome").asText shouldBe "refused"
      lines.head._2.get("client.id").asText shouldBe Client
      assertNoPersonalData(lines.head._1)
    }

    "mark an unknown path as failure without route, and replace an invalid request id and traceparent" in {
      val (response, lines) = call("/vfeeg/eeg/4711", Bearer("superuser"),
        RawHeader("X-Request-Id", "not a plain id"), RawHeader("traceparent", s"00-${"0" * 32}-$Span-01"))
      response.status.intValue shouldBe 404
      val j = lines.head._2
      j.get("event.outcome").asText shouldBe "failure"
      j.has("http.route") shouldBe false
      j.get("request.id").asText should fullyMatch regex "[0-9a-f]{32}"
      response.headers.find(_.is("x-request-id")).map(_.value) shouldBe Some(j.get("request.id").asText)
      j.has("trace.id") shouldBe false
      lines.head._1 should not include "4711"
    }

    "carry the 503 the server answers on a request timeout, once, also when the route completes later" in {
      val late = Promise[String]()
      val settings = ServerSettings(system)
      val slowPort = Await.result(Http().newServerAt("127.0.0.1", 0)
        .adaptSettings(_ => settings.withTimeouts(settings.timeouts.withRequestTimeout(300.millis)))
        .bind(AccessLog.accessLog(AccessLog.path("slow") { complete(late.future) })), 10.seconds).localAddress.getPort
      val (response, timeoutLines) = callAt(slowPort, HttpRequest(uri = "/slow"))
      response.status.intValue shouldBe 503
      timeoutLines should have size 1
      val j = timeoutLines.head._2
      j.get("http.response.status_code").asInt shouldBe 503
      j.get("event.outcome").asText shouldBe "failure"
      j.get("http.route").asText shouldBe "/slow"
      response.headers.find(_.is("x-request-id")).map(_.value) shouldBe Some(j.get("request.id").asText)
      late.success("late")
      Thread.sleep(300)
      lines() should have size 1
    }

    "not be written for the health routes" in {
      call("/healthz")._2 shouldBe empty
      call("/readyz")._2 shouldBe empty
    }
  }

  "traceIds" should {
    "accept a valid traceparent and refuse version ff, all-zero ids and a version 00 with extra fields" in {
      AccessLog.traceIds(s"00-$Trace-$Span-01") shouldBe Some((Trace, Span))
      AccessLog.traceIds(s"01-$Trace-$Span-01-more") shouldBe Some((Trace, Span))
      AccessLog.traceIds(s"ff-$Trace-$Span-01") shouldBe None
      AccessLog.traceIds(s"00-$Trace-${"0" * 16}-01") shouldBe None
      AccessLog.traceIds(s"00-$Trace-$Span-01-more") shouldBe None
      AccessLog.traceIds(s"00-${Trace.toUpperCase}-$Span-01") shouldBe None
    }
  }
}
