package at.ourproject.routes

import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directive, Directive0}

import scala.jdk.CollectionConverters._

/**
 * Rollenpruefung fuer die Admin-API.
 *
 * Hintergrund (2026-09-09): saemtliche Routen dieses Dienstes hingen allein an
 * `authenticateOAuth2Async`, also an "ist ueberhaupt angemeldet". Der Keycloak-Client
 * `at.ourproject.vfeeg.admin` ist ein oeffentlicher Client mit Standard-Flow — jeder
 * Realm-Nutzer, auch ein gewoehnlicher EEG-Administrator, konnte sich am Admin-Portal
 * anmelden und damit ein Token mit der passenden Audience erhalten. Die Schreibrouten
 * unter `admin/master/...` nehmen den Tenant zudem aus dem REQUEST-BODY und pruefen ihn
 * nirgends gegen das Token: damit war ein EEG-uebergreifender Schreibzugriff moeglich.
 * `vfeeg/participants` liest ausserdem personenbezogene Daten.
 *
 * Diese Admin-API ist ein Werkzeug des Betriebs, nicht der Energiegemeinschaften. Sie
 * setzt daher durchgehend die Realm-Rolle `superuser` voraus. Eine zusaetzliche
 * Tenant-Pruefung braucht es dann nicht: der EEG-uebergreifende Zugriff ist genau die
 * Faehigkeit, die diese Rolle bewusst hat.
 */
object RoleDirectives {

  val SuperuserRole = "superuser"

  def hasRealmRole(user: AuthenticatedUser, role: String): Boolean =
    user.claims.get("realm_access") match {
      case Some(m: java.util.Map[_, _]) =>
        m.asInstanceOf[java.util.Map[String, Any]].get("roles") match {
          case roles: java.util.List[_] => roles.asScala.map(_.toString).contains(role)
          case _                        => false
        }
      case _ => false
    }

  /**
   * Laesst nur Token mit der Realm-Rolle `superuser` durch. Antwortet sonst mit 403 und
   * einer maschinen- wie menschenlesbaren Begruendung — ein nackter Status hat hier
   * bereits eine Support-Runde gekostet, weil niemand den Grund erkennen konnte.
   */
  def requireSuperuser(user: AuthenticatedUser): Directive0 =
    Directive { inner =>
      if (hasRealmRole(user, SuperuserRole)) inner(())
      else
        complete(
          HttpResponse(
            StatusCodes.Forbidden,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              """{"error":"superuser role required"}"""
            )
          )
        )
    }
}
