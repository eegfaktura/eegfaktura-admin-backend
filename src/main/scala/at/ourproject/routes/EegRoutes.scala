package at.ourproject.routes

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import at.ourproject.dao.Dao
import at.ourproject.json.JsonFormater._
import at.ourproject.logging.AccessLog
import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._

import scala.concurrent.Future

class EegRoutes (daos: Dao, akkaAuthenticator: Credentials => Future[Option[AuthenticatedUser]]) extends Router {

  private val eegRoutes = {
      pathPrefix("vfeeg") {
        KeycloakJwtAuthenticator.authenticated(akkaAuthenticator) { user: AuthenticatedUser =>
          RoleDirectives.requireSuperuser(user) {
          AccessLog.path("eeg") {
            get {
              complete(daos.eegRepository.getAll)
            }
          } ~
          AccessLog.path("participants") {
            get {
              AccessLog.tenantParameter { tenant =>
                complete(daos.participantRepository.getFullParticipantByTenant(tenant))
              }
            }
          } ~
          AccessLog.path("operators") {
            get {
              complete(daos.operatorRepository.getAll)
            }
          }
                  }
        }
      }
  }

  override def route: Route = eegRoutes
}
