package at.ourproject

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success}


object HttpServer {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit = {
    // Akka HTTP still needs a classic ActorSystem to start
    import system.executionContext

    val futureBinding = Http().newServerAt(interface = "0.0.0.0", port = 8085).bind(routes)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        log.info(s"Server online at http://${address.getAddress.getHostAddress}:${address.getPort}/")
      case Failure(ex) =>
        log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }
}
