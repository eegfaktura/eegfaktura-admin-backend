package at.ourproject.dao

import slick.lifted.ProvenShape

trait MetersTable {
  this: Profile =>

  import profile.api._

  class Meters(tag: Tag) extends Table[EegMeteringPoint](tag, Some("base"), "meteringpoint") {
    def tenant: Rep[String] = column[String]("tenant")
    def meteringPoint: Rep[String] = column[String]("metering_point_id")
    def direction: Rep[String] = column[String]("direction")
    def status: Rep[String] = column[String]("status")
    def consentId: Rep[Option[String]] = column[Option[String]]("consent_id")
    def processState: Rep[String] = column[String]("process_state")
    def activeSince: Rep[Option[java.sql.Date]] = column[Option[java.sql.Date]]("activesince")
    def inactiveSince: Rep[Option[java.sql.Date]] = column[Option[java.sql.Date]]("inactivesince")
    def participantId: Rep[String] = column[String]("participant_id")

    def * : ProvenShape[EegMeteringPoint] = (tenant, meteringPoint, direction, status, consentId, participantId, processState, activeSince, inactiveSince) <> (EegMeteringPoint.tupled, EegMeteringPoint.unapply)
  }

  val meters = TableQuery[Meters]
}
