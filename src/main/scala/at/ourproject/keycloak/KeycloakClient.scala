package at.ourproject.keycloak

import at.ourproject.KeycloakConfig.Configuration
import at.ourproject.keycloak.KeycloakClient.User
import at.ourproject.services.RegisterService.UserInfo
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.resource.{RealmResource, UserResource, UsersResource}
import org.keycloak.admin.client.{CreatedResponseUtil, Keycloak, KeycloakBuilder}
import org.keycloak.representations.idm.{CredentialRepresentation, GroupRepresentation, UserRepresentation}
import org.slf4j.{Logger, LoggerFactory}
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}

import scala.util.Try

class KeycloakClient(keycloakAdminClient: Keycloak, config: Configuration) {
  private def realmResource(client: Keycloak): RealmResource = client.realm(config.auth.realm)
  private def usersResource(realm: RealmResource): UsersResource = realm.users()

  import scala.jdk.CollectionConverters._

  val log: Logger = LoggerFactory.getLogger(getClass)

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

  def normalizeName(name: String): String = {
    val replacements = Map (
    "ü" -> "ue",
    "ä" -> "ae",
    "ö" -> "oe",
    "ß" -> "ss"
    )

    replacements.foldLeft (name) {
    case (str, (char, replacement) ) => str.replace (char, replacement)
    }
  }

  def createUser(user: User, rcNumber: String, userGroups: List[GroupRepresentation]): Unit = {
    val realm = realmResource(keycloakAdminClient)
    val users = usersResource(realm)

    log.info(s"Create Keycloak-User: ${user.email}")
    log.info(s"Create Keycloak-User: ${user}")

    val buildUsername = (firstname: String, lastname: String) => s"${normalizeName(lastname).substring(0,6)}${firstname.substring(0,2)}"

    try {
      val userRepresentation = new UserRepresentation()
      userRepresentation.setEnabled(true)
      userRepresentation.setEmailVerified(true)
      userRepresentation.setUsername(user.username.getOrElse(buildUsername(user.firstName, user.lastName)))
      userRepresentation.setFirstName(user.firstName)
      userRepresentation.setLastName(user.lastName)
      userRepresentation.setEmail(user.email)
      userRepresentation.setAttributes(Map("tenant" -> List(s"""["${rcNumber.toUpperCase()}"]""").asJava).asJava)

      val response = users.create(userRepresentation)
      log.info(s"CREATE USER RESPONSE: ${response.getStatus.toString}")
      val userId = CreatedResponseUtil.getCreatedId(response)

      // Set password for User
      val passwordCred = new CredentialRepresentation()
      passwordCred.setTemporary(true)
      passwordCred.setType(CredentialRepresentation.PASSWORD)
      passwordCred.setValue(user.password.getOrElse("EEGFaktura#123"))

      log.info("Apply Password Credentials ...")
      val userResource = users.get(userId)
      // Set password credential
      userResource.resetPassword(passwordCred)

      log.info("Apply user groups ...")
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
    val realm = realmResource(keycloakAdminClient)
    val users = usersResource(realm)

    val userResource = users.get(userId)
    userResource.update(addTenantToUserResource(userResource.toRepresentation, tenant.toUpperCase()))
  }

  /**
   * Add appropriate tenant configuration to the user resource.
   *
   * @param userResource User resource which will be changed
   * @param tenant       Tenant to be added
   */
  private def addTenantToUserResource(user: UserRepresentation, tenant: String): UserRepresentation = {
    import io.circe.generic.auto._
    import io.circe.parser.decode
    import io.circe.syntax._

    val userAttributes = user.getAttributes match {
      case null => collection.mutable.Map[String, java.util.List[String]]()
      case x => x.asScala
    }

    val tenants = userAttributes.get("tenant").map(t => if (t.size() > 0) t.get(0) else "").getOrElse("")
    if (tenants.isEmpty) {
      user.setAttributes(Map("tenant" -> List(s"""["${tenant}"]""").asJava).asJava)
    } else {
      val newTenants = decode[List[String]](tenants) match {
        case Right(value) => if (value.contains(tenant)) value else value :+ tenant
        case _ => List(tenant)
      }
      val a = newTenants.asJson.noSpaces

      user.singleAttribute("tenant", a)
    }
    user
  }

  /**
   * Configure existing user to be a valid eegfaktura user.
   *
   * @param userId Keycloak UserId to be configured
   * @param tenant User should be part of the tenant
   * @param userInfo User Credentials
   * @param userGroups Expected User Groups to be assigned
   */
  def configureUser(userId: String, tenant: String, userInfo: UserInfo, userGroups: List[GroupRepresentation]): Unit = {
    val realm = realmResource(keycloakAdminClient)
    val users = usersResource(realm)

    try {
      val userResource = users.get(userId)

      if (userResource != null) {
        val user = userResource.toRepresentation

        log.info(s"Configure Keycloak-User: ${user.getEmail}")

        user.setEmailVerified(true)

        val firstName = user.getFirstName
        if (firstName == null || firstName.isEmpty) {
          user.setFirstName(userInfo.firstname)
          log.debug("Update User Firstname")
        }

        val lastName = user.getLastName
        if (lastName == null || lastName.isEmpty) {
          user.setLastName(userInfo.lastname)
          log.debug("Update User Lastname")
        }

        val userName = user.getUsername
        if (userName == null || userName.isEmpty) {
          user.setUsername(s"${userInfo.firstname}${userInfo.lastname.subSequence(0, 2)}")
          log.debug("Update User Username")
        }

        val groups = userResource.groups()
        userGroups.foreach(g => if (!groups.contains(g)) userResource.joinGroup(g.getId))

        try {
          val creds = userResource.credentials().asScala.toList
          if (creds.isEmpty) addCredentials(userResource = userResource, password = "Start!1234")
        } catch {
          case _: Throwable => addCredentials(userResource = userResource, password = "Start!1234")
        }
        userResource.update(addTenantToUserResource(user, tenant.toUpperCase()))
      }
    } catch {
      case e: Throwable => log.error(e.getMessage)
    }
  }

  /**
   * Add a standard password credentials to the user resource
   *
   * @param userResource User resource to be changed
   * @param password Temporal password for the specified user
   */
  private def addCredentials(userResource: UserResource, password: String): Unit = {
    // Set password for User
    val passwordCred = new CredentialRepresentation()
    passwordCred.setTemporary(true)
    passwordCred.setType(CredentialRepresentation.PASSWORD)
    passwordCred.setValue(password)

    // Set password credential
    userResource.resetPassword(passwordCred)
  }

  def getGroups: List[GroupRepresentation] = {
    val realm = realmResource(keycloakAdminClient)

    try {
      realm.groups().groups().asScala.toList
    } catch {
      case e: Throwable => throw new RuntimeException(e)
    }
  }

  def getUserId(email: String): Try[String] = Try {
    val user = getUserRepresentation(email)
    user.getId
  }

  def findUserByTenant(tenant: String): Try[List[UserRepresentation]] = {
    val realm = realmResource(keycloakAdminClient)
    val users = usersResource(realm)

    Try {
      try {
//        val userResources = users.searchByAttributes(s"tenantId:${tenant}")
        val userResources = users.list()
        if (userResources == null) {
          throw new RuntimeException("No valid user found")
        }
        println(s"userResponse ${userResources.size()}")
        println(s"userResponse ${userResources.get(0)}")
        userResources.asScala.toList
      } catch {
        case e: Exception => throw new RuntimeException(s"No valid user found with $tenant: ${e.getMessage}")
      }
    }
  }
}


object KeycloakClient {
  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

  case class User(username: Option[String], firstName: String, lastName: String, email: String, password: Option[String])

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
