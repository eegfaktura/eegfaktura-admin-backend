package at.ourproject.logging

import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{AttributeKey, HttpRequest}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directive, Directive0, Directive1, PathMatcher0, Route, RouteResult}
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.util.{Failure, Success}

/**
 * One access line per HTTP request (event.action=http.access; logging concept point 6), written when the response is
 * done. The route template, tenant and client are put into the request's `Context` by the routes: the token check sets
 * `client.id` (the token's azp, the Keycloak client - not a person), the route sets `http.route` (`AccessLog.path`)
 * and `tenant.id` (`AccessLog.tenant`, the tenant the route acts on). No raw path, no query, no address, no user agent,
 * no user (no user tracking, DSGVO). The health routes (/healthz, /readyz) are left out. The logger
 * `at.ourproject.access` is fixed at info in logback.xml, so LOG_LEVEL / LOG_LEVEL_APP do not switch the line off.
 */
object AccessLog {

  private val log = LoggerFactory.getLogger("at.ourproject.access")

  final class Context(val requestId: String, val traceId: Option[String], val spanId: Option[String]) {
    @volatile var route: Option[String] = None
    @volatile var tenant: Option[String] = None
    @volatile var client: Option[String] = None
  }

  val ContextKey: AttributeKey[Context] = AttributeKey[Context]("access-log-context")
  val RequestIdHeader = "X-Request-Id"

  private val SkippedPaths = Set("/healthz", "/readyz")
  private val Token = "[A-Za-z0-9._:-]{1,128}".r
  private val Traceparent = "([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}(-.*)?".r

  /** A plain token (request id, client id, tenant) or None; anything else is not written. */
  def plainToken(value: String): Option[String] = Option(value).map(_.trim).filter(Token.matches)

  /** trace.id and span.id of a W3C traceparent; version ff and all-zero ids are invalid. */
  def traceIds(value: String): Option[(String, String)] = Option(value).map(_.trim).collect {
    case Traceparent(version, trace, span, suffix)
      if version != "ff" && !(version == "00" && suffix != null) && trace.exists(_ != '0') && span.exists(_ != '0') =>
      (trace, span)
  }

  def outcome(status: Int): String =
    if (status == 401 || status == 403) "refused" else if (status >= 400) "failure" else "success"

  private def contextOf(request: HttpRequest): Context = {
    val id = request.headers.find(_.is("x-request-id")).flatMap(h => plainToken(h.value))
      .getOrElse(UUID.randomUUID().toString.replace("-", ""))
    val trace = request.headers.find(_.is("traceparent")).flatMap(h => traceIds(h.value))
    new Context(id, trace.map(_._1), trace.map(_._2))
  }

  /**
   * Wraps all routes. Rejections and exceptions of the inner route are turned into responses here (as the server
   * would do), so the line carries the status the client gets.
   */
  val accessLog: Directive0 = Directive { inner =>
    val route = inner(())
    val sealedRoute = Route.seal(route) // once; the settings are taken from each request's context
    rc => {
      val request = rc.request
      if (SkippedPaths(request.uri.path.toString)) route(rc)
      else {
        val ctx = contextOf(request)
        val start = System.nanoTime()
        val idHeader = RawHeader(RequestIdHeader, ctx.requestId)
        sealedRoute(rc.withRequest(request.addAttribute(ContextKey, ctx))).transform { result =>
          val status = result match {
            case Success(RouteResult.Complete(response)) => response.status.intValue
            case Success(RouteResult.Rejected(_)) => 404 // not reached: the route is sealed
            case Failure(_) => 500
          }
          write(request.method.value, status, System.nanoTime() - start, ctx)
          result.map {
            case RouteResult.Complete(response) => RouteResult.Complete(response.addHeader(idHeader))
            case other => other
          }
        }(rc.executionContext)
      }
    }
  }

  private def write(method: String, status: Int, durationNanos: Long, ctx: Context): Unit = {
    val line = log.atInfo()
      .addKeyValue("event.action", "http.access")
      .addKeyValue("event.outcome", outcome(status))
      .addKeyValue("event.duration", java.lang.Long.valueOf(durationNanos))
      .addKeyValue("http.request.method", method)
      .addKeyValue("http.response.status_code", Integer.valueOf(status))
      .addKeyValue("request.id", ctx.requestId)
    ctx.route.foreach(line.addKeyValue("http.route", _))
    ctx.traceId.foreach(line.addKeyValue("trace.id", _))
    ctx.spanId.foreach(line.addKeyValue("span.id", _))
    ctx.tenant.foreach(line.addKeyValue("tenant.id", _))
    ctx.client.foreach(line.addKeyValue("client.id", _))
    line.log(s"$method ${ctx.route.getOrElse("-")} $status")
  }

  private def update(f: Context => Unit): Directive0 =
    optionalAttribute(ContextKey).flatMap { ctx => ctx.foreach(f); pass }

  /** Sets the client of the access line (the token's azp). */
  def client(azp: Option[String]): Directive0 = update(_.client = azp.flatMap(plainToken))

  /** Sets the tenant the route acts on (upper case, as the services use it). */
  def tenant(tenant: String): Directive0 = update(_.tenant = plainToken(tenant).map(_.toUpperCase))

  /** `directive`, setting the tenant its value names. */
  def withTenant[T](directive: Directive1[T])(tenantOf: T => String): Directive1[T] =
    directive.flatMap(value => tenant(tenantOf(value)).tmap(_ => value))

  /** The query parameter `tenant`, set as tenant of the access line. */
  def tenantParameter: Directive1[String] = withTenant(parameter("tenant"))(identity)

  /**
   * `path` for fixed segments only; records the matched path as route template. Only strings are accepted, so the
   * template can never contain an id.
   */
  def path(first: String, more: String*): Directive0 = {
    val matcher = more.foldLeft(first: PathMatcher0)((m, s) => m / s)
    pathPrefix(matcher ~ PathEnd) & extractMatchedPath.flatMap(p => update(_.route = Some(p.toString)))
  }
}
