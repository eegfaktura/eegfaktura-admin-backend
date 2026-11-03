package at.ourproject.dao

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ParticipantsRepositorySpec extends AnyWordSpecLike with Matchers {

  private def participant(id: String) = EegParticipant(id, "TE100002", "Max", "Muster", "EEG_PRIVATE", "ACTIVE")
  private def meter(id: String, participantId: String) =
    EegMeteringPoint("TE100002", id, "CONSUMPTION", "ACTIVE", None, participantId, "ACTIVE", None, None)

  "withMeters" should {
    "give each participant once, with the meters of its rows" in {
      val rows = Seq(
        participant("p-1") -> Some(meter("m-1", "p-1")),
        participant("p-2") -> Some(meter("m-3", "p-2")),
        participant("p-1") -> Some(meter("m-2", "p-1")))

      ParticipantsRepository.withMeters(rows).map(p => p.id -> p.meters.map(_.meteringPoint)).toMap shouldBe
        Map("p-1" -> Seq("m-1", "m-2"), "p-2" -> Seq("m-3"))
    }

    "keep a participant without a meter, with an empty list" in {
      ParticipantsRepository.withMeters(Seq(participant("p-1") -> None)) shouldBe Seq(participant("p-1"))
    }

    "be empty without rows" in {
      ParticipantsRepository.withMeters(Nil) shouldBe empty
    }
  }
}
