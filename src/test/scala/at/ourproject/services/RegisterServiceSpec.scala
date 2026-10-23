package at.ourproject.services

import at.energydash.admin.{RegisterPontonRequest, RegisterPontonServiceClient, RegisteredPontonReply}
import at.ourproject.services.RegisterService._
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax.EncoderOps
import org.apache.pekko.Done
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.{ExecutionContext, Future}

class RegisterServiceSpec extends AnyWordSpecLike with Matchers with ScalaFutures {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  // The body of POST /eeg/register as the admin web sends it (eegfaktura-admin, model/eeg.model.ts):
  // it carries fields the backend does not model (settlement, confirmPassword), which are ignored.
  private val registerBody =
    """{
      |  "tenant": "te100002", "rcNumber": "te100002", "communityId": "AT0030000000000TE10000200000001",
      |  "name": "UNITTEST", "description": "UNIT TEST REGISTRATION", "settlement": "", "online": false,
      |  "accountInfo": {"iban": "ATIBAN000111", "owner": "Tester", "sepa": false},
      |  "businessInfo": {"legal": "verein", "settlementInterval": "MONTHLY"},
      |  "grid": {"id": "AT003000", "name": "NETZ GR", "area": "LOCAL", "allocation": "DYNAMIC"},
      |  "contact": {"street": "Allee", "streetNumber": "1a", "city": "Solarvillage", "zip": "3456",
      |              "web": "http://tester.com", "phone": "3123123123"},
      |  "pontonInfo": {"host": "", "port": 0, "username": "te100002", "password": "123456",
      |                 "confirmPassword": "123456", "domain": "edanet.at", "pontonCommType": "KEP"},
      |  "user": {"username": "", "password": "", "confirmPassword": "", "firstname": "Hans",
      |           "lastname": "Huber", "email": "tester@test.com"}
      |}""".stripMargin

  private val expectedEeg = Eeg(
    tenant = "te100002", rcNumber = "te100002", communityId = "AT0030000000000TE10000200000001",
    name = "UNITTEST", description = "UNIT TEST REGISTRATION", online = false,
    accountInfo = AccountInfo(iban = "ATIBAN000111", owner = "Tester", sepa = false),
    businessInfo = BusinessInfo(legal = EegLegalType.verein, settlementInterval = EegSettlementType.MONTHLY),
    grid = Grid(id = "AT003000", name = "NETZ GR", area = GridAreaType.LOCAL, allocation = GridAllocationType.DYNAMIC),
    contact = Contact(street = "Allee", streetNumber = "1a", city = "Solarvillage", zip = "3456",
      web = Some("http://tester.com"), phone = Some("3123123123")),
    pontonInfo = PontonInfo(username = "te100002", password = "123456", domain = "edanet.at",
      host = Some(""), port = Some(0), pontonCommType = PontonCommType.KEP),
    user = UserInfo(username = "", password = "", firstname = "Hans", lastname = "Huber", email = "tester@test.com"))

  /** Records every request; answers with the given status. No network. */
  private class RecordingPontonClient(status: Int) extends RegisterPontonServiceClient {
    @volatile var requests: List[RegisterPontonRequest] = Nil

    override def register(in: RegisterPontonRequest): Future[RegisteredPontonReply] = {
      requests = requests :+ in
      Future.successful(RegisteredPontonReply(status, "reply"))
    }

    override def update(in: RegisterPontonRequest): Future[RegisteredPontonReply] =
      Future.failed(new UnsupportedOperationException("update is not used by registerPonton"))

    override def close(): Future[Done] = Future.successful(Done)

    override def closed: Future[Done] = Future.successful(Done)
  }

  private def onlineEeg(commType: PontonCommType.PontonComm): Eeg =
    expectedEeg.copy(online = true, pontonInfo = expectedEeg.pontonInfo.copy(pontonCommType = commType))

  "The registration request (POST /eeg/register)" should {
    "decode the body the admin web sends, including the community admin (user)" in {
      decode[Eeg](registerBody) shouldBe Right(expectedEeg)
    }

    "encode enumerations by name and decode its own encoding back to the same value" in {
      val json = expectedEeg.asJson
      val c = json.hcursor
      c.downField("businessInfo").downField("legal").as[String] shouldBe Right("verein")
      c.downField("businessInfo").downField("settlementInterval").as[String] shouldBe Right("MONTHLY")
      c.downField("grid").downField("area").as[String] shouldBe Right("LOCAL")
      c.downField("grid").downField("allocation").as[String] shouldBe Right("DYNAMIC")
      c.downField("pontonInfo").downField("pontonCommType").as[String] shouldBe Right("KEP")
      json.as[Eeg] shouldBe Right(expectedEeg)
    }

    "reject an unknown enumeration value" in {
      decode[Eeg](registerBody.replace("\"KEP\"", "\"FAX\"")).isLeft shouldBe true
      decode[Eeg](registerBody.replace("\"verein\"", "\"club\"")).isLeft shouldBe true
    }

    "reject a body without the community admin (user)" in {
      val withoutUser = io.circe.parser.parse(registerBody).map(_.mapObject(_.remove("user"))).toOption.get
      withoutUser.as[Eeg].isLeft shouldBe true
    }
  }

  "registerPonton" should {
    "not call eda-xp and succeed when the communication type is NONE, even for an online EEG" in {
      val client = new RecordingPontonClient(200)
      registerPonton(onlineEeg(PontonCommType.NONE), client).futureValue shouldBe true
      client.requests shouldBe empty
    }

    "not call eda-xp and succeed for an EEG that is not online" in {
      val client = new RecordingPontonClient(200)
      registerPonton(onlineEeg(PontonCommType.KEP).copy(online = false), client).futureValue shouldBe true
      client.requests shouldBe empty
    }

    "register an online EEG with its upper-cased RC number and Ponton credentials" in {
      val client = new RecordingPontonClient(200)
      registerPonton(onlineEeg(PontonCommType.KEP), client).futureValue shouldBe true
      client.requests should have size 1
      val req = client.requests.head
      req.tenant shouldBe "TE100002"
      req.username shouldBe "te100002"
      req.password shouldBe "123456"
      req.domain shouldBe "edanet.at"
    }

    "fail when eda-xp does not answer 200" in {
      val client = new RecordingPontonClient(500)
      registerPonton(onlineEeg(PontonCommType.KEP), client).futureValue shouldBe false
    }

    // known-errors #5 (fixed in 1.0.5): eda-xp gets the communication type (proto field 5), always KEP —
    // also for MAIL, which only POST /eeg/register and /eeg/ponton refuse before this call
    "pass the communication type KEP to eda-xp, whatever the request names" in {
      val client = new RecordingPontonClient(200)
      registerPonton(onlineEeg(PontonCommType.MAIL), client).futureValue shouldBe true
      client.requests.map(_.pontonCommType) shouldBe List("KEP")
    }
  }

  "invalidAccount" should {
    "accept first name, last name and an e-mail" in {
      invalidAccount(AccountUser("Anna", "Ott", " anna@test.local ")) shouldBe None
    }

    "reject a missing name or a bad e-mail" in {
      invalidAccount(AccountUser(" ", "Ott", "anna@test.local")) shouldBe defined
      invalidAccount(AccountUser("Anna", "", "anna@test.local")) shouldBe defined
      invalidAccount(AccountUser("Anna", "Ott", "anna")) shouldBe defined
      invalidAccount(AccountUser("Anna", "Ott", "anna@test")) shouldBe defined
      invalidAccount(AccountUser("Anna", "Ott", "")) shouldBe defined
    }
  }

  "validTenant" should {
    "trim and accept letters, digits, _ and -" in {
      validTenant(" GC100002-001 ") shouldBe Some("GC100002-001")
      validTenant("RC100001") shouldBe Some("RC100001")
    }

    "reject what would break the JSON attribute or is empty" in {
      validTenant("""RC100001","RC999999""") shouldBe None
      validTenant("RC 100001") shouldBe None
      validTenant(" ") shouldBe None
      validTenant(null) shouldBe None
    }
  }

  "registrationAccounts" should {
    "drop duplicates, ignoring case and blanks, and keep the contact only if listed" in {
      val eeg = Eeg("te100002", "te100002", "UNITTEST", "UNIT TEST", "", false,
        AccountInfo(iban = "AT", owner = "T", sepa = false),
        BusinessInfo(legal = EegLegalType.verein, settlementInterval = EegSettlementType.MONTHLY),
        Grid(id = "AT03001", name = "N", area = GridAreaType.LOCAL, allocation = GridAllocationType.DYNAMIC),
        Contact(street = "A", streetNumber = "1", city = "C", zip = "1", web = None, phone = None),
        PontonInfo(username = "", password = "", domain = "", host = None, port = None, pontonCommType = PontonCommType.NONE),
        UserInfo(username = "u", password = "", firstname = "Kontakt", lastname = "Person", email = "kontakt@test.local"),
        Some(List(
          AccountUser("Haupt", "Account", "haupt@test.local"),
          AccountUser("Neben", "Eins", "neben1@test.local"),
          AccountUser("Neben", "Eins", "Neben1@Test.local "),
          AccountUser("Neben", "Zwei", "neben2@test.local"))))

      registrationAccounts(eeg).map(_.map(_.email)) shouldBe Some(List("haupt@test.local", "neben1@test.local", "neben2@test.local"))
      registrationAccounts(eeg.copy(accounts = None)) shouldBe None
      registrationAccounts(eeg.copy(accounts = Some(Nil))) shouldBe None
    }
  }

  "an Eeg without accounts" should {
    "decode from the JSON of older clients" in {
      val json = """{"tenant":"te1","rcNumber":"te1","communityId":"c","name":"n","description":"","online":false,
        |"accountInfo":{"iban":"AT","owner":"o","sepa":false},"businessInfo":{"legal":"verein","settlementInterval":"MONTHLY"},
        |"grid":{"id":"g","name":"n","area":"LOCAL","allocation":"DYNAMIC"},
        |"contact":{"street":"s","streetNumber":"1","city":"c","zip":"1"},
        |"pontonInfo":{"username":"","password":"","domain":"","pontonCommType":"NONE"},
        |"user":{"username":"u","password":"","firstname":"f","lastname":"l","email":"e@test.local"}}""".stripMargin
      decode[Eeg](json).map(_.accounts) shouldBe Right(None)
    }
  }
}
