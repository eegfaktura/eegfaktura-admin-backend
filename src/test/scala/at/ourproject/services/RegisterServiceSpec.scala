package at.ourproject.services

import at.ourproject.services.RegisterService._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import io.circe.generic.auto._


class RegisterServiceSpec extends AnyWordSpecLike with Matchers {

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
