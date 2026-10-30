package at.ourproject.routes

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.after
import org.slf4j.LoggerFactory
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, TimeoutException}

// Kubernetes probes, without authentication (the documented exception to "every endpoint needs a bearer token").
// /healthz checks nothing, so a database outage never restarts the pod; /readyz runs the given database check.
// The bodies are fixed texts; the reason of a failed check goes to the log only.
class HealthRoutes(dbCheck: () => Future[Any], timeout: FiniteDuration = 2.seconds)(implicit system: ActorSystem[_]) extends Router {
  private val log = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = system.executionContext

  private def ready: Future[Boolean] = {
    val check = Future.unit.flatMap(_ => dbCheck())
    val deadline = after(timeout)(Future.failed(new TimeoutException(s"no answer within $timeout")))
    Future.firstCompletedOf(Seq(check, deadline)).map(_ => true).recover { case ex =>
      log.warn(s"readyz: database check failed: ${ex.getClass.getSimpleName}: ${ex.getMessage}")
      false
    }
  }

  override def route: Route = get {
    path("healthz") {
      complete("ok")
    } ~
    path("readyz") {
      onSuccess(ready) { ok =>
        if (ok) complete("ok") else complete(StatusCodes.ServiceUnavailable, "unavailable")
      }
    }
  }
}

object HealthRoutes {
  // the trivial query /readyz runs through the service's Slick database
  def selectOne(db: Database): () => Future[Int] = {
    import slick.jdbc.PostgresProfile.api._
    () => db.run(sql"SELECT 1".as[Int].head)
  }
}
