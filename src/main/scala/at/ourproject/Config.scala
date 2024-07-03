package at.ourproject

import com.typesafe.config.{Config, ConfigFactory}
import io.circe.parser._
import io.circe.{Json, ParsingFailure}

import java.io.ByteArrayInputStream
import scala.io.Source

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

  private def keycloakFile = config.getString("keycloak.configfile")

  private val keycloakConfigJson: Json = {
    val jsonString: String = scala.io.Source.fromFile(keycloakFile).mkString
    val parseResult: Either[ParsingFailure, Json] = parse {
      jsonString
    }
    parseResult match {
      case Left(parsingError) =>
        throw new IllegalArgumentException(s"Invalid JSON object: ${parsingError.message}")
      case Right(json) =>
        (json \\ "admin-cli").head
    }
  }

  private val keycloakConfigAdminJson: Json = {
    val jsonString: String = Source.fromFile(keycloakFile).mkString
    val parseResult: Either[ParsingFailure, Json] = parse {jsonString}
    parseResult match {
      case Left(parsingError) =>
        throw new IllegalArgumentException(s"Invalid JSON object: ${parsingError.message}")
      case Right(json) =>
        (json \\ "admin").head
    }
  }

//  def transform(in: Json): Either[DecodingFailure, Json] =
//    in.hcursor.downField("secret").delete.as[JsonObject].map(Json.fromJsonObject)
//
//  private def verifyConfigJson = transform(keycloakConfigJson) match {
//    case Left(parsingError) => throw new IllegalArgumentException(s"Invalid JSON object: ${parsingError.message}")
//    case Right(json) => json
//  }

  def keycloakConfigStream = new ByteArrayInputStream(keycloakConfigAdminJson.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8.name))

  def getkey: String => String = (key: String) =>
    keycloakConfigJson.hcursor.downField(key).as[String] match {
      case Left(_) => ""
      case Right(v) => v
    }

  private def buildAuthObject: Auth = {
    val c = keycloakConfigJson.hcursor
    (for {
      serverUrl <- c.downField("auth-server-url").as[String]
      clientId <- c.downField("resource").as[String]
      clientSecret <- c.downField("secret").as[String]
      realm <- c.downField("realm").as[String]
    } yield(Auth(serverUrl, clientId, clientSecret, realm))) match {
      case Left(parsingError) => throw new IllegalArgumentException(s"Invalid JSON object: ${parsingError.message}")
      case Right(v) => v
    }
  }

  def keycloakAdminConfig: Configuration = Configuration(buildAuthObject)
}