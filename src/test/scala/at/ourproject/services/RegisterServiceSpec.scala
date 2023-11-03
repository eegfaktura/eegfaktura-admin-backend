package at.ourproject.services

import at.ourproject.services.RegisterService._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import io.circe.generic.auto._
import io.circe.syntax.EncoderOps


class RegisterServiceSpec extends AnyWordSpecLike with Matchers {

  "Registration Service" should {
    "send EEG message to backend" in {
      var eeg = Eeg("te100002", "AT0030000000000TE10000200000001", "UNITTEST", "UNIT TEST REGISTRATION", false,
        AccountInfo(iban = "ATIBAN000111", owner = "Tester", sepa = false),
        BusinessInfo(legal = EegLegalType.verein, businessNr = "1212", taxNumber = "121", vatNumber = "0", settlementInterval = EegSettlementType.MONTHLY),
        Grid(id = "AT03001", name = "NETZ GR", area = GridAreaType.LOCAL, allocation = GridAllocationType.DYNAMIC),
        Contact(owner = "Tester 1", street = "Allee", streetNumber = "1a", city = "Solarvilage", zip = "3456", email = "tester@test.com", web = Some("http://tester.com"), phone = Some("3123123123")),
        PontonInfo(username = "te100002", password = "123456", domain = "eda.test.com", host = "localhost", port = 1883),
        UserInfo(username = "tester", password = "122343", firstname = "tester", lastname = "Korrect", email = "tester@test.com"))

      println(eeg.asJson.toString)

    }

    "register existing user" in {

    }
  }

}
