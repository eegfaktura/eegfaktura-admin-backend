package at.ourproject.services

import at.ourproject.services.RegisterService._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import io.circe.generic.auto._
import io.circe.syntax.EncoderOps


class RegisterServiceSpec extends AnyWordSpecLike with Matchers {

  "Registration Service" should {
    "send EEG message to backend" in {
      var eeg = Eeg("te100002", "AT0030000000000TE10000200000001", "UNITTEST", "UNIT TEST REGISTRATION", "", false,
        AccountInfo(iban = "ATIBAN000111", owner = "Tester", sepa = false),
        BusinessInfo(legal = EegLegalType.verein, settlementInterval = EegSettlementType.MONTHLY),
        Grid(id = "AT03001", name = "NETZ GR", area = GridAreaType.LOCAL, allocation = GridAllocationType.DYNAMIC),
        Contact(street = "Allee", streetNumber = "1a", city = "Solarvilage", zip = "3456", web = Some("http://tester.com"), phone = Some("3123123123")),
        PontonInfo(username = "te100002", password = "123456", domain = "eda.test.com", host = Some("localhost"), port = Some(1883), pontonCommType = PontonCommType.NONE),
        UserInfo(username = "tester", password = "122343", firstname = "tester", lastname = "Korrect", email = "tester@test.com"))

      println(eeg.asJson.toString)

    }

    "register existing user" in {

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
      import io.circe.parser.decode
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
