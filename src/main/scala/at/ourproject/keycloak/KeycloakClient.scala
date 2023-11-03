package at.ourproject.keycloak

import at.ourproject.Config.Configuration
import at.ourproject.keycloak.KeycloakClient.User
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.resource.{RealmResource, UsersResource}
import org.keycloak.admin.client.{CreatedResponseUtil, Keycloak, KeycloakBuilder}
import org.keycloak.representations.idm.{CredentialRepresentation, GroupRepresentation, UserRepresentation}
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}

import scala.util.Try

class KeycloakClient(keycloakAdminClient: Keycloak, config: Configuration) {
  private def realmResource(client: Keycloak): RealmResource = client.realm(config.auth.realm)
  private def usersResource(realm: RealmResource): UsersResource = realm.users()

  import scala.jdk.CollectionConverters._

  def checkUserAlreadyExist(email: String): Boolean = {
    try {
      getUserRepresentation(email)
      true
    } catch {
      case e: Exception => false
    }
  }

  def getUserRepresentation(email: String): UserRepresentation = {
    val realm = realmResource(keycloakAdminClient)
    val users = usersResource(realm)

    try {
      val userResources = users.searchByEmail(email, true)
      userResources.get(0)
    } catch {
      case e: Exception => throw new RuntimeException(s"No valid user found $email: ${e.getMessage}")
    }
  }

  def createUser(user: User, rcNumber: String, userGroups: List[GroupRepresentation]) = {
    val realm = realmResource(keycloakAdminClient)
    val users = usersResource(realm)

    try {
      val userRepresentation = new UserRepresentation()
      userRepresentation.setEnabled(true)
      userRepresentation.setEmailVerified(true)
      userRepresentation.setUsername(user.username)
      userRepresentation.setFirstName(user.firstName)
      userRepresentation.setLastName(user.lastName)
      userRepresentation.setEmail(user.email)
      userRepresentation.setAttributes(Map("tenant" -> List(s"""["${rcNumber.toUpperCase()}"]""").asJava).asJava)

      val response = users.create(userRepresentation)
      val userId = CreatedResponseUtil.getCreatedId(response)

      // Set password for User
      val passwordCred = new CredentialRepresentation()
      passwordCred.setTemporary(false)
      passwordCred.setType(CredentialRepresentation.PASSWORD)
      passwordCred.setValue(user.password)

      val userResource = users.get(userId)
      // Set password credential
      userResource.resetPassword(passwordCred)

      // Join groups to user
      for (g <- userGroups) userResource.joinGroup(g.getId)

//      val clientRep = realm.clients().findByClientId("at.ourproject.vfeeg.app").get(0)
//      val roles = realm.roles().list().asScala.filter(r => r.getName.toLowerCase == "admin" || r.getName.toLowerCase == "user").toList
//      userResource.roles().clientLevel(clientRep.getId).add(roles.asJava);//

      // Send password reset E-Mail
      // VERIFY_EMAIL, UPDATE_PROFILE, CONFIGURE_TOTP, UPDATE_PASSWORD, TERMS_AND_CONDITIONS
//      users.get(userId).executeActionsEmail(List("UPDATE_PASSWORD", "VERIFY_EMAIL").asJava)

    } catch {
      case e: Exception => throw new RuntimeException(s"Cannot create User ${user.email}: ${e.getMessage}")
    }
  }

  def addTenantToUser(userId: String, tenant: String): Unit = {
//    import io.circe.generic.JsonCodec
    import io.circe.generic.auto._
    import io.circe.parser.decode
    import io.circe.syntax._

    val realm = realmResource(keycloakAdminClient)
    val users = usersResource(realm)

    val userResource = users.get(userId).toRepresentation
    val userAttributes = userResource.getAttributes.asScala

    val tenants = userAttributes.get("tenant").map(t => if (t.size() > 0) t.get(0) else "").getOrElse("")
    if (tenants.isEmpty) {
      userResource.setAttributes(Map("tenant" -> List(s"""["${tenant.toUpperCase()}"]""").asJava).asJava)
    } else {
      val newTenants = decode[List[String]](tenants) match {
        case Right(value) => if (value.contains(tenant)) value else value :+ tenant
        case _ => List(tenant)
      }
      val a = newTenants.asJson.noSpaces

      userResource.singleAttribute("tenant", a)
      users.get(userId).update(userResource)
    }
  }

  def getGroups() = {
    val realm = realmResource(keycloakAdminClient)

    try {
      realm.groups().groups().asScala.toList
    }
  }

  def getUserId(email: String): Try[String] = Try {
    val user = getUserRepresentation(email)
    user.getId
  }

  def findUserByTenant(tenant: String) = {
    val realm = realmResource(keycloakAdminClient)
    val users = usersResource(realm)

    Try {
      try {
//        val userResources = users.searchByAttributes(s"tenantId:${tenant}")
        val userResources = users.list()
        println(s"userResponse ${userResources.size()}")
        println(s"userResponse ${userResources.get(0)}")
        if (userResources == null) {
          throw new RuntimeException("No valid user found")
        }
        userResources.asScala.toList
      } catch {
        case e: Exception => throw new RuntimeException(s"No valid user found with $tenant: ${e.getMessage}")
      }
    }
  }
}


object KeycloakClient {
  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

  case class User(username: String, firstName: String, lastName: String, email: String, password: String)

  def apply(config: Configuration): KeycloakClient = {
    val keycloakAdminClient = KeycloakBuilder.builder()
      .serverUrl(config.auth.url)
      .realm(config.auth.realm)
      .clientId(config.auth.clientId)
      .clientSecret(config.auth.clientSecret)
      .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
      .build()
    new KeycloakClient(keycloakAdminClient, config)
  }
}
