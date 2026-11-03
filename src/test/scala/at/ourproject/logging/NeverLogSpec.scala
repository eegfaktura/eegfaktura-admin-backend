package at.ourproject.logging

import at.ourproject.logging.NeverLogScanner._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

// The never-log guard of the logging concept (point 4): CI fails when a log call of src/main/scala has a hit of
// NeverLogScanner. Fix the call; only a value that is safe by what it is gets an entry below, with the reason.
class NeverLogSpec extends AnyWordSpec with Matchers {

  private case class Allowed(file: String, term: String, reason: String)

  private val Allowlist = Seq(
    Allowed("Config.scala", "msg", "fixed text naming a missing variable, three lines above"),
    Allowed("Registration.scala", "reason", "text of LogSettings.validate: the name and value of a log variable"),
    Allowed("logging/AccessLog.scala", "method", "the HTTP method"),
    Allowed("logging/AccessLog.scala", "result", "the outcome: success, failure or refused"),
    Allowed("logging/LogContext.scala", "message", "the adapter behind RequestContext.log: Pekko's text, checked where Pekko writes it"),
    Allowed("routes/AdminRoutes.scala", "reason", "the backend's fixed refusal text or the message of the exception logged with it"),
    Allowed("routes/HealthRoutes.scala", "name", "name of a JDBC connection property, from the config"),
    Allowed("routes/KeycloakJwtAuthenticator.scala", "kid", "key id of a token header, no person"),
    Allowed("routes/KeycloakJwtAuthenticator.scala", "alg", "algorithm of a token header"),
    Allowed("routes/KeycloakJwtAuthenticator.scala", "iss", "issuer URL of a token, debug only"),
    Allowed("routes/KeycloakJwtAuthenticator.scala", "aud", "audience of a token, Keycloak client ids, debug only"),
    Allowed("routes/KeycloakJwtAuthenticator.scala", "exp", "expiry time of a token, debug only"),
    Allowed("routes/KeycloakJwtAuthenticator.scala", "e.toString", "a refused token is expected, not an error: type and text " +
      "of a JOSE exception or of the check itself (issuer, audience, expiry) say enough, a stack per refused token is noise"),
    Allowed("services/RegisterService.scala", "userExists", "a Boolean"),
  )

  private val Root = Paths.get("src", "main", "scala")

  private def sources: List[(String, String)] = {
    val files = Files.walk(Root)
    try files.iterator.asScala.filter(_.toString.endsWith(".scala")).toList.map { (p: Path) =>
      Root.relativize(p).toString.replace('\\', '/') -> new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    } finally files.close()
  }

  private def hitsOf(src: String): List[Hit] = {
    val masked = mask(src)
    scan("Snippet.scala", src, masked, modelFields(Seq(masked))).hits
  }

  private def rules(src: String) = hitsOf(src).map(h => h.term -> h.rule)

  "the sources" should {
    lazy val results = {
      val masked = sources.map { case (file, src) => (file, src, mask(src)) }
      val models = modelFields(masked.map(_._3))
      masked.map { case (file, src, m) => scan(file, src, m, models) }
    }
    lazy val found = results.flatMap(_.hits)

    "have no hit outside the allowlist" in {
      val open = found.filterNot(h => Allowlist.exists(a => h.file.endsWith(a.file) && a.term == h.term))
      withClue(open.mkString("\n", "\n", "\n")) { open shouldBe empty }
    }

    "really be scanned" in {
      results.map(_.calls).sum should be > 40
    }

    "have no allowlist entry without a use or a reason" in {
      Allowlist.filterNot(a => found.exists(h => h.file.endsWith(a.file) && a.term == h.term)) shouldBe empty
      Allowlist.filter(_.reason.trim.isEmpty) shouldBe empty
    }
  }

  "the scanner" should {
    "find a bare value, in a string and as an argument" in {
      rules("""log.info(s"registered $eeg")""") shouldBe List("eeg" -> BareValue)
      rules("""log.info(s"registered ${eeg}")""") shouldBe List("eeg" -> BareValue)
      rules("""log.info("registered {}", eeg)""") shouldBe List("eeg" -> BareValue)
    }

    "find toString" in {
      rules("""log.warn(s"got ${user.toString}")""") shouldBe List("user.toString" -> ToStringCall)
      rules("""log.warn(user.toString())""") shouldBe List("user.toString" -> ToStringCall)
    }

    "find them behind a call too" in {
      rules("""log.warn(s"got ${find(a).toString}")""").map(_._2) should contain(ToStringCall)
      rules("""log.error(s"failed: ${new Oops("a").getMessage}")""").map(_._2) should contain(MessageOnly)
      rules("""log.info(s"${find(a).email}")""").map(_._2) should contain(PersonalName)
    }

    "find a field that holds a model" in {
      val src = """case class Eeg(user: UserInfo); case class UserInfo(name: String); class A { log.info(s"${eeg.user}") }"""
      rules(src) shouldBe List("eeg.user" -> ModelField)
    }

    "find getMessage without the exception, but not with it" in {
      rules("""log.error(s"failed: ${e.getMessage}")""") shouldBe List("e.getMessage" -> MessageOnly)
      rules("""case Failure(e) => log.error(s"failed: ${e.getMessage}", e)""") shouldBe empty
      rules("""catch { case e: IllegalStateException => log.error("failed", e) }""") shouldBe empty
    }

    "skip a bare value by its declared type, not by its name" in {
      rules("""val status: Int = 1; val n: Long = 2; val ok: Boolean = true; log.info(s"x $status $n $ok")""") shouldBe empty
      rules("""case Failure(ex) => log.warn("failed", ex)""") shouldBe empty
      rules("""log.error("failed", e)""") shouldBe List("e" -> BareValue) // nothing says e is an exception
      rules("""val status: String = "a"; log.info(s"x $status")""") shouldBe List("status" -> BareValue)
    }

    "find personal data and secrets, whatever the receiver, spelling or level" in {
      val calls = Seq("eeg.user.email", "user.firstName", "user.first_name", "userInfo.lastname", "u.getLastName()",
        "account.iban", "account.bic", "cfg.clientSecret", "user.password", "cfg.passwd", "cfg.pwd", "accessToken",
        "jwt", "bearerValue", "request.authorization", "credential", "creds", "cookie", "apiKey", "api_key",
        "m.vorname", "m.nachname", "m.name1", "m.name2", "mailAddress", "recipient", "sentTo", "accountHolder",
        "claims.sub", "claims.getSubject", "userId", "user.userName", "payload", "response.body")
      calls.foreach { term =>
        withClue(term) {
          hitsOf(s"""log.debug(s"x $${$term}")""").map(_.rule) should contain(PersonalName)
          hitsOf(s"""context.log.warn("x {}", $term)""").map(_.rule) should contain(PersonalName)
        }
      }
    }

    "check every segment of a dotted value against the names" in {
      Seq("eeg.contact.street", "eeg.contact.streetNumber", "eeg.contact.phone", "eeg.user.username", "user.getUsername",
        "user.userName", "eeg.accountInfo.owner").foreach { term =>
        withClue(term) { hitsOf(s"""log.info(s"x $${$term}")""").map(_.rule) shouldBe List(PersonalName) }
      }
    }

    "compare whole words, not parts of one" in {
      Seq("tenant.substring", "eeg.subscription", "eeg.somebody", "eeg.subway", "eeg.bicycle", "eeg.grid.name",
        "eeg.contact.city").foreach { term =>
        withClue(term) { hitsOf(s"""log.info(s"x $${$term}")""") shouldBe empty }
      }
    }

    "find a list word with a plural s" in {
      Seq("e.tokens", "user.emails", "cfg.passwords", "mail.recipients", "account.ibans", "account.IBANs", "accessTokens")
        .foreach { term => withClue(term) { hitsOf(s"""log.info(s"x $${$term}")""").map(_.rule) shouldBe List(PersonalName) } }
    }

    "find a list word of five or more letters inside an all-lower-case identifier" in {
      Seq("cfg.accesstoken", "cfg.userpassword", "m.mailaddress", "rc.ownership", "r.clientsecret")
        .foreach { term => withClue(term) { hitsOf(s"""log.info(s"x $${$term}")""").map(_.rule) shouldBe List(PersonalName) } }
    }

    "find a list word directly before a placeholder in the text of a literal" in {
      Seq("""log.info(String.format("password=%s", v))""", """log.info("token: {}", v)""", """log.info(s"secret=$v")""",
        """log.info(s"iban ${v}")""", """log.debug(f"Emails: $v%s")""").foreach { call =>
        withClue(call) { hitsOf(call).map(_.rule) should contain(BeforePlaceholder) }
      }
      Seq("""log.info("token refused")""", """log.info("tenant {} for the token", v)""", """log.info(s"$v has no token")""")
        .foreach { call => withClue(call) { hitsOf(call).map(_.rule) should not contain BeforePlaceholder } }
    }

    "check the value of a key-value pair, not its key" in {
      rules("""line.addKeyValue("user.email", "x")""") shouldBe empty
      rules("""line.addKeyValue("x", user.email)""") shouldBe List("user.email" -> PersonalName)
    }

    "see the other ways to log" in {
      rules("""System.err.println(s"x $eeg")""").map(_._1) shouldBe List("eeg")
      rules("""LoggerFactory.getLogger(getClass).error(s"x $eeg")""").map(_._1) shouldBe List("eeg")
      rules("""line.log(s"x $eeg")""").map(_._1) shouldBe List("eeg")
    }

    "follow a call over several lines" in {
      hitsOf("val a = 1\nlog.info(\n  s\"x $eeg\"\n)") shouldBe List(Hit("Snippet.scala", 2, "eeg", BareValue))
    }

    "pass text, ids and method results" in {
      rules("""log.info("Apply Password Credentials for the token ...")""") shouldBe empty
      rules("""log.info(s"tenant ${tenant.toUpperCase()} of ${eeg.rcNumber}")""") shouldBe empty
      rules("""log.info(s"costs $$5, 'quoted' \" (")""") shouldBe empty
    }

    "ignore comments and code outside log calls" in {
      rules("""// log.info(s"x $eeg")""") shouldBe empty
      rules("/* log.info(s\"x $eeg\") */ val a = eeg.toString") shouldBe empty
    }

    "read nested strings in an interpolation" in {
      rules("""log.info(s"x ${a.getOrElse("-")} $eeg")""") shouldBe List("eeg" -> BareValue)
    }
  }
}
