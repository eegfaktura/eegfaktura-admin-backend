package at.ourproject

import com.typesafe.config.{Config, ConfigFactory}
import io.circe.parser._

//object Config {
//  case class Auth(url: String, clientId: String, clientSecret: String, realm: String)
//  case class Configuration(auth: Auth)
//
//  def config(): Configuration = Configuration(
//    Auth("https://login.ourproject.at/auth", "admin-cli", "qzCRMVWS6PnDwJ3v5JkZfWcTbBTZrBBU", "VFEEG"))
////    Auth("http://localhost:8180/auth", "admin-cli", "bgqHFY8RHf6e2oHGyXYSepJ0xnzMr5kr", "vfeeg"))
////    Auth("http://localhost:8180/auth", "realm-management", "QfNtbYYJdCwQ1vfCZpSi3792Vx7vTdoS", "vfeeg"))
//}
//
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

  def keycloakAdminConfig: Configuration = Configuration(
    Auth(
      url = config.getString("keycloak.url"),
      clientId = config.getString("keycloak.clientId"),
      clientSecret = config.getString("keycloak.secret"),
      realm = config.getString("keycloak.realm")
    )
  )

  def keycloakJWTConfig: Configuration = Configuration(
    Auth(
      url = config.getString("keycloakAuthenticator.url"),
      clientId = config.getString("keycloakAuthenticator.clientId"),
      clientSecret = "",
      realm = config.getString("keycloakAuthenticator.realm")
    )
  )
}