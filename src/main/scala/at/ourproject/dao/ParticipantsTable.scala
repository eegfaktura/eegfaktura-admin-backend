package at.ourproject.dao

import slick.lifted.ProvenShape

trait ParticipantsTable {
  this: Profile =>

  import profile.api._

  class Participants(tag: Tag) extends Table[EegParticipant](tag, Some("base"), "participant") {
    def id: Rep[String] = column[String]("id")
    def tenant: Rep[String] = column[String]("tenant")
    def firstname: Rep[String] = column[String]("firstname")
    def lastname: Rep[String] = column[String]("lastname")
    def businessRole: Rep[String] = column[String]("businessRole")
    def status: Rep[String] = column[String]("status")

    def * : ProvenShape[EegParticipant] = (id, tenant, firstname, lastname, businessRole, status) <> (intoParticipant, fromParticipant)
  }

  val participants = TableQuery[Participants]

  private def intoParticipant(pairs: (String, String, String, String, String, String)): EegParticipant =
    EegParticipant(pairs._1, pairs._2, pairs._3, pairs._4, pairs._5, pairs._6, Nil)

  private def fromParticipant(participant: EegParticipant): Option[(String, String, String, String, String, String)] =
    Some(participant.id, participant.tenant, participant.firstName, participant.lastName, participant.businessRole, participant.status)
}
