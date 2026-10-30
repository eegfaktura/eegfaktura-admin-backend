package at.ourproject.routes

import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory

import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Failure, Success, Using}

// Kubernetes probes, without authentication (the documented exception to "every endpoint needs a bearer token").
// /healthz checks nothing, so a database outage never restarts the pod; /readyz runs the given database check.
// At most one check runs at a time; callers during a check, or within a second after it, share its result, so
// anonymous callers of the public path cannot multiply the load on the database.
// The bodies are fixed texts; a failed check is logged only when the state changes.
class HealthRoutes(dbCheck: () => Future[Any], timeout: FiniteDuration = 2.seconds)(implicit system: ActorSystem[_]) extends Router {
  private val log = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = system.executionContext

  private val reuseNanos = 1.second.toNanos
  private var current: Future[Boolean] = _
  @volatile private var completedAt = 0L
  @volatile private var lastReady = true

  private def ready: Future[Boolean] = synchronized {
    if (current == null || (current.isCompleted && System.nanoTime - completedAt >= reuseNanos)) {
      current = check().andThen { case _ => completedAt = System.nanoTime }
    }
    current
  }

  private def check(): Future[Boolean] = {
    val result = Promise[Unit]()
    val deadline = system.scheduler.scheduleOnce(timeout, () => {
      result.tryFailure(new TimeoutException(s"no answer within $timeout"))
      ()
    })
    Future.unit.flatMap(_ => dbCheck()).onComplete { r =>
      deadline.cancel()
      result.tryComplete(r.map(_ => ()))
    }
    result.future.transform {
      case Success(_) =>
        if (!lastReady) log.info("readyz: database reachable again")
        lastReady = true
        Success(true)
      case Failure(ex) =>
        if (lastReady) log.warn(s"readyz: database check failed: ${ex.getClass.getSimpleName}: ${ex.getMessage}")
        lastReady = false
        Success(false)
    }
  }

  override def route: Route =
    path("healthz") {
      get {
        complete("ok")
      }
    } ~
    path("readyz") {
      get {
        onSuccess(ready) { ok =>
          if (ok) complete("ok") else complete(StatusCodes.ServiceUnavailable, "unavailable")
        }
      }
    }
}

object HealthRoutes {
  // one daemon thread for the blocking ping; only one ping runs at a time anyway
  private lazy val pingContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val t = new Thread(r, "readyz-db-ping")
      t.setDaemon(true)
      t
    }
  }))

  // The ping opens its own short-lived connection, not one of the application's pool (a busy pool must not make the
  // service NotReady, and the public path must not be able to exhaust it). It uses the connection properties of
  // `slick.pgsql.vfeeg` and the driver's own connect, login and query timeouts, with a socket timeout as backstop.
  def postgresPing(properties: Config): () => Future[Unit] = {
    val ds = new PGSimpleDataSource()
    ds.setServerNames(Array(properties.getString("serverName")))
    ds.setPortNumbers(Array(properties.getString("portNumber").toInt))
    ds.setDatabaseName(properties.getString("databaseName"))
    ds.setUser(properties.getString("user"))
    if (properties.hasPath("password")) ds.setPassword(properties.getString("password"))
    ds.setConnectTimeout(2)
    ds.setLoginTimeout(2)
    ds.setSocketTimeout(3)
    ds.setApplicationName("readyz")
    () => Future {
      Using.resource(ds.getConnection()) { conn =>
        Using.resource(conn.createStatement()) { st =>
          st.setQueryTimeout(2)
          Using.resource(st.executeQuery("SELECT 1"))(_.next())
        }
      }
      ()
    }(pingContext)
  }
}
