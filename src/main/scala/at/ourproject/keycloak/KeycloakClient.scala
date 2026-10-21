package at.ourproject.keycloak

import at.ourproject.KeycloakConfig.Configuration
import at.ourproject.keycloak.KeycloakClient.{AccountView, User, hasTenant}
import at.ourproject.services.RegisterService.{AccountUser, UserInfo}
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
      case e: Exception => throw new RuntimeException(s"No valid user found: ${e.getMessage}")
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

  /** Up to 6 characters of the normalised last name plus up to 2 of the first name. */
  def buildUsername(firstname: String, lastname: String): String =
    s"${normalizeName(lastname).take(6)}${firstname.take(2)}"

  /** Username for an existing user without one: first name plus up to 2 characters of the last name. */
  def fallbackUsername(firstname: String, lastname: String): String =
    s"$firstname${lastname.take(2)}"

  def createUser(user: User, rcNumber: String, userGroups: List[GroupRepresentation]): Unit = {
    val realm = realmResource(keycloakAdminClient)
    val users = usersResource(realm)

    log.info(s"Create Keycloak-User for tenant ${rcNumber.toUpperCase()}")

    try {
      val userRepresentation = new UserRepresentation()
      userRepresentation.setEnabled(true)
      userRepresentation.setEmailVerified(true)
      userRepresentation.setFirstName(user.firstName)
      userRepresentation.setLastName(user.lastName)
      userRepresentation.setEmail(user.email)
      userRepresentation.setAttributes(Map("tenant" -> List(KeycloakClient.tenantAttribute(List(rcNumber.toUpperCase()))).asJava).asJava)

      // The e-mail is checked before; a conflict here is a username another person already has
      // (same name prefix): try the next candidate. A username given by the operator is used as it is.
      val candidates = user.username match {
        case Some(given) => List(given)
        case None => KeycloakClient.usernameCandidates(buildUsername(user.firstName, user.lastName))
      }
      def create(rest: List[String]): jakarta.ws.rs.core.Response = {
        userRepresentation.setUsername(rest.head)
        val response = users.create(userRepresentation)
        if (response.getStatus == 409 && rest.tail.nonEmpty) {
          response.close()
          create(rest.tail)
        } else response
      }
      val response = create(candidates)
      log.info(s"CREATE USER RESPONSE: ${response.getStatus.toString}")
      val userId = CreatedResponseUtil.getCreatedId(response)

      // Set password for User
      val passwordCred = new CredentialRepresentation()
      passwordCred.setTemporary(true)
      passwordCred.setType(CredentialRepresentation.PASSWORD)
      // No fixed fallback password: without a password from the request the
      // user gets a random one and a mail to set their own.
      val givenPassword = user.password.filter(_.nonEmpty)
      passwordCred.setValue(givenPassword.getOrElse(KeycloakClient.randomPassword()))

      log.info("Apply Password Credentials ...")
      val userResource = users.get(userId)
      // Set password credential
      userResource.resetPassword(passwordCred)
      if (givenPassword.isEmpty) sendUpdatePasswordMail(userResource, userId)

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
      case e: Exception => throw new RuntimeException(s"Cannot create User: ${e.getMessage}")
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
   * @param user User resource which will be changed
   * @param tenant       Tenant to be added
   */
  private def addTenantToUserResource(user: UserRepresentation, tenant: String): UserRepresentation = {
    // keep the other attributes and the tenants the user already has, whatever form they are stored in
    val attributes = Option(user.getAttributes).getOrElse(new java.util.HashMap[String, java.util.List[String]]())
    val values = Option(attributes.get("tenant")).map(_.asScala.toList).getOrElse(Nil)
    attributes.put("tenant", new java.util.ArrayList[String](KeycloakClient.withTenant(values, tenant).asJava))
    user.setAttributes(attributes)
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

        log.info(s"Configure Keycloak-User: $userId")

        // Users can change their own e-mail: an account with an unconfirmed address could have taken the address of
        // the person meant here and would get the community. Only a confirmed address is linked (platform#166).
        if (!Option(user.isEmailVerified).exists(_.booleanValue()))
          throw new IllegalStateException(KeycloakClient.EmailNotVerified)

        // The groups hold for all communities of the account: an account that already has a community but not
        // EEG_ADMIN would silently become admin there too (e.g. a member login). Decided 21.10.2026: refuse. Other
        // groups are added (EEG_OWNER grants nothing today).
        val current = userResource.groups().asScala.map(_.getName).toSet
        val existingTenants = KeycloakClient.tenantsOf(
          Option(user.getAttributes).flatMap(a => Option(a.get("tenant"))).map(_.asScala.toList).getOrElse(Nil))
        if (existingTenants.nonEmpty && userGroups.exists(g => KeycloakClient.RightsGroups.contains(g.getName) && !current.contains(g.getName)))
          throw new IllegalStateException(KeycloakClient.LessRights)

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
          user.setUsername(fallbackUsername(userInfo.firstname, userInfo.lastname))
          log.debug("Update User Username")
        }

        // Before the password: update() with this representation would drop the required action UPDATE_PASSWORD
        // that resetPassword adds.
        userResource.update(addTenantToUserResource(user, tenant.toUpperCase()))

        val groups = userResource.groups()
        userGroups.foreach(g => if (!groups.contains(g)) userResource.joinGroup(g.getId))

        // Only a user without any credential gets one: a random temporary
        // password plus a mail to set their own. If the credentials cannot be
        // read, nothing is changed — an existing password must never be replaced.
        try {
          val creds = userResource.credentials().asScala.toList
          if (creds.isEmpty) {
            addCredentials(userResource = userResource, password = KeycloakClient.randomPassword())
            sendUpdatePasswordMail(userResource, userId)
          }
        } catch {
          case e: Throwable => log.warn(s"Could not read credentials of user $userId, left unchanged: ${e.getMessage}")
        }
      }
    } catch {
      case e: IllegalStateException => throw e
      case e: Throwable =>
        log.error(e.getMessage)
        throw new RuntimeException(s"Cannot configure user: ${e.getMessage}")
    }
  }

  /**
   * Give a person access to a community: a new account (random temporary password and a mail to set
   * their own) or, if the e-mail is known, the existing account gets the community and the groups.
   */
  def ensureAccount(tenant: String, user: AccountUser, userGroups: List[GroupRepresentation]): AccountStatus.Value =
    getUserId(user.email) match {
      case scala.util.Success(id) =>
        configureUser(id, tenant, UserInfo("", "", user.firstname, user.lastname, user.email), userGroups)
        AccountStatus.linked
      case scala.util.Failure(_) =>
        createUser(User(None, user.firstname, user.lastname, user.email, None), tenant, userGroups)
        AccountStatus.created
    }

  /** Accounts with access to the community. Keycloak cannot search the JSON list in the attribute: all users are read page by page. */
  def listAccounts(tenant: String): List[AccountView] = {
    val users = usersResource(realmResource(keycloakAdminClient))
    val pageSize = 100

    def page(first: Int): List[UserRepresentation] = {
      val chunk = users.list(first, pageSize).asScala.toList
      if (chunk.size < pageSize) chunk else chunk ++ page(first + pageSize)
    }

    page(0)
      .filter(u => Option(u.getAttributes).flatMap(a => Option(a.get("tenant"))).exists(v => hasTenant(v.asScala.toList, tenant)))
      .map(u => AccountView(
        username = u.getUsername,
        firstname = Option(u.getFirstName).getOrElse(""),
        lastname = Option(u.getLastName).getOrElse(""),
        email = Option(u.getEmail).getOrElse(""),
        enabled = u.isEnabled,
        passwordPending = Option(u.getRequiredActions).exists(_.contains("UPDATE_PASSWORD"))))
      .sortBy(a => (a.lastname.toLowerCase, a.firstname.toLowerCase))
  }

  /**
   * Ask Keycloak to mail the user a link to set their own password. A failure
   * (e.g. no SMTP in the realm) is only logged: the operator then sets the
   * password in the Keycloak console.
   */
  private def sendUpdatePasswordMail(userResource: UserResource, userId: String): Unit = {
    try {
      userResource.executeActionsEmail(List("UPDATE_PASSWORD").asJava)
    } catch {
      case e: Throwable => log.warn(s"Could not send the set-password mail to user $userId: ${e.getMessage}")
    }
  }

  /**
   * Add a temporary password credential to the user resource
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
}


object AccountStatus extends Enumeration {
  val created, linked, failed = Value
}

object KeycloakClient {
  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

  case class User(username: Option[String], firstName: String, lastName: String, email: String, password: Option[String])

  /** What the admin tool shows of an account: no id, no attributes. */
  case class AccountView(username: String, firstname: String, lastname: String, email: String, enabled: Boolean, passwordPending: Boolean)

  val EmailNotVerified = "Es gibt schon einen Account mit dieser E-Mail-Adresse, die Adresse ist dort aber nicht bestätigt. Bitte in Keycloak prüfen."

  val LessRights = "Es gibt schon einen Account mit dieser E-Mail-Adresse, er hat bisher weniger Rechte. Die Rechte gelten für alle seine EEGs: bitte in Keycloak klären."

  /** The groups that grant rights; an existing account is not raised to them (platform#166). */
  val RightsGroups: Set[String] = Set("EEG_ADMIN")

  /** Expected refusals (not a Keycloak fault): the operator has to clear them in Keycloak. */
  val Refusals: Set[String] = Set(EmailNotVerified, LessRights)

  /** The tenant attribute: a JSON list as text. */
  def tenantAttribute(tenants: List[String]): String = {
    import io.circe.syntax._
    tenants.asJson.noSpaces
  }

  /** The username, then the username with 2 … 9 appended. */
  def usernameCandidates(base: String): List[String] = base :: (2 to 9).map(i => s"$base$i").toList

  private def jsonList(value: String): Option[List[String]] = io.circe.parser.decode[List[String]](value).toOption
  private def jsonString(value: String): Option[String] = io.circe.parser.decode[String](value).toOption

  /**
   * The tenants in the attribute values. The token mapper reads every value as JSON, and both forms occur: one
   * value with a list (`["RC100001","GC100002-001"]`, as this service writes it) or one value per tenant
   * (`"RC100001"`, e.g. set in the Keycloak console or the realm import); a plain value is taken as it is (the
   * token mapper cannot read it, a tenant added to such values is written as JSON string).
   */
  def tenantsOf(values: List[String]): List[String] =
    values.flatMap(v => jsonList(v).orElse(jsonString(v).map(List(_))).getOrElse(List(v.trim))).filter(_.nonEmpty)

  /** Do the attribute values contain the tenant? Case-insensitive. */
  def hasTenant(values: List[String], tenant: String): Boolean = tenantsOf(values).exists(_.equalsIgnoreCase(tenant))

  /** The attribute values with the tenant added, in the form they already have; unchanged if it is there. */
  def withTenant(values: List[String], tenant: String): List[String] =
    if (hasTenant(values, tenant)) values
    else if (values.isEmpty || values.exists(v => jsonList(v).isDefined)) List(tenantAttribute(tenantsOf(values) :+ tenant))
    else values :+ io.circe.Json.fromString(tenant).noSpaces

  private val secureRandom = new java.security.SecureRandom()

  /** Random temporary password; one character of each class for common realm password policies. */
  def randomPassword(): String = {
    val bytes = new Array[Byte](24)
    secureRandom.nextBytes(bytes)
    "Aa1!" + java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

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
