package at.ourproject

import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

//object AppConfig {
//  case class KeycloakFile(path: String)
//
//  val config = ConfigFactory.load()
//
//  def keycloakFile = config.getString("keycloak.configfile")
//
//}

object KeycloakConfig {
  case class Auth(url: String, clientId: String, clientSecret: String, realm: String)
//  case class ConfigJson (realm: String,`auth-server-url`: String, `ssl-required`: String,
//  resource: String, `public-client`: Boolean, `verify-token-audience`: Boolean, `use-resource-role-mappings`: Boolean,
//  `confidential-port`: Int)

  case class Configuration(auth: Auth)

  val config: Config = ConfigFactory.load()

  def keycloakAdminConfig: Configuration = adminConfigFrom(config)

  // The admin-cli client secret has no default; fail fast when it is missing or empty.
  private[ourproject] def adminConfigFrom(c: Config): Configuration = {
    val secret = if (c.hasPath("keycloak.secret")) c.getString("keycloak.secret").trim else ""
    if (secret.isEmpty) {
      val msg = "KEYCLOAK_ADMIN_CLI_SECRET is not set (keycloak.secret): the admin-cli client secret is required"
      LoggerFactory.getLogger(getClass).error(msg)
      throw new IllegalStateException(msg)
    }
    Configuration(
      Auth(
        url = c.getString("keycloak.url"),
        clientId = c.getString("keycloak.clientId"),
        clientSecret = secret,
        realm = c.getString("keycloak.realm")
      )
    )
  }

  def keycloakJWTConfig: Configuration = Configuration(
    Auth(
      url = config.getString("keycloakAuthenticator.url"),
      clientId = config.getString("keycloakAuthenticator.clientId"),
      clientSecret = "",
      realm = config.getString("keycloakAuthenticator.realm")
    )
  )
}