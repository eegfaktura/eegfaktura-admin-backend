package at.ourproject.routes

import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory

import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Using}

// Kubernetes probes, without authentication (the documented exception to "every endpoint needs a bearer token").
// /healthz checks nothing, so a database outage never restarts the pod; /readyz runs the given database check.
// At most one check runs at a time; callers during a check, or within a second after it, share its result, so
// anonymous callers of the public path cannot multiply the load on the database.
// Postgres refusing the ping with SQLSTATE 53300 (too_many_connections) counts as ready: the application's pool
// still holds its connections, and a saturated database must not take the service out of the load balancer.
// The bodies are fixed texts; a failed or refused check is logged only when the state changes.
class HealthRoutes(dbCheck: () => Future[Any], timeout: FiniteDuration = 2.seconds)(implicit system: ActorSystem[_]) extends Router {
  private val log = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = system.executionContext

  private val reuseNanos = 1.second.toNanos
  private var current: Future[Boolean] = _
  @volatile private var completedAt = 0L
  @volatile private var lastState = "none" // none (no result yet), ok, saturated or down

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
        if (lastState == "saturated" || lastState == "down") log.info("readyz: database reachable again")
        lastState = "ok"
        Success(true)
      case Failure(_: HealthRoutes.PingStillRunning) =>
        // a slow ping must not make a healthy pod flap: answer from the last completed result
        Success(lastState == "ok" || lastState == "saturated")
      case Failure(ex: SQLException) if ex.getSQLState == "53300" =>
        if (lastState != "saturated")
          log.warn("readyz: database has no free connection for the ping (53300 too_many_connections), still reporting ready")
        lastState = "saturated"
        Success(true)
      case Failure(ex) =>
        if (lastState != "down") log.warn(s"readyz: database check failed: ${ex.getClass.getSimpleName}: ${ex.getMessage}")
        lastState = "down"
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
  private val log = LoggerFactory.getLogger(getClass)

  // the previous ping has not finished yet; the routes answer from the last completed result
  class PingStillRunning extends RuntimeException("previous ping still running")

  // one daemon thread for the blocking ping; a ping never waits in its queue (see `running` below)
  private lazy val pingContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val t = new Thread(r, "readyz-db-ping")
      t.setDaemon(true)
      t
    }
  }))

  // The ping opens its own short-lived connection, not one of the application's pool (a busy pool must not make the
  // service NotReady, and the public path must not be able to exhaust it). It uses the connection properties of
  // `slick.pgsql.vfeeg` (all of them, so driver keys such as ssl or options apply too) and the driver's own connect,
  // login and query timeouts, with a socket timeout as backstop. While a ping still runs (it can outlast the 2 s
  // answer), the next one fails at once with PingStillRunning instead of waiting behind it.
  def postgresPing(properties: Config): () => Future[Unit] = {
    val ds = new PGSimpleDataSource()
    properties.entrySet.asScala.foreach { e =>
      val value = e.getValue.unwrapped.toString
      e.getKey match {
        case "serverName" => ds.setServerNames(Array(value))
        case "portNumber" => ds.setPortNumbers(Array(value.toInt))
        case "databaseName" => ds.setDatabaseName(value)
        case "user" => ds.setUser(value)
        case "password" => ds.setPassword(value)
        // the application's Slick pool stays the authority on its properties; the ping skips a key it cannot use
        case name =>
          try ds.setProperty(name, value)
          catch { case ex: SQLException => log.warn(s"readyz: ping ignores the connection property $name: ${ex.getMessage}") }
      }
    }
    ds.setConnectTimeout(2)
    ds.setLoginTimeout(2)
    ds.setSocketTimeout(3)
    ds.setApplicationName("readyz")
    val running = new AtomicBoolean(false)
    () =>
      if (!running.compareAndSet(false, true)) Future.failed(new PingStillRunning)
      else Future {
        try {
          Using.resource(ds.getConnection()) { conn =>
            Using.resource(conn.createStatement()) { st =>
              st.setQueryTimeout(2)
              Using.resource(st.executeQuery("SELECT 1"))(_.next())
            }
          }
          ()
        } finally running.set(false)
      }(pingContext)
  }
}
