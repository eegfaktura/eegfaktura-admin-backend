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

    def * : ProvenShape[EegMeteringPoint] = (tenant, meteringPoint, direction, status, consentId) <> (EegMeteringPoint.tupled, EegMeteringPoint.unapply)
  }

  val meters = TableQuery[Meters]
}
