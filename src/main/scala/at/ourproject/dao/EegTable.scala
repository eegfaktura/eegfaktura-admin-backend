package at.ourproject.dao

import slick.lifted.ProvenShape

trait EegTable { this: Profile =>

  import profile.api._

  class Eegs(tag: Tag) extends Table[Eeg](tag, Some("base"), "eeg") {
    def tenant: Rep[String] = column[String]("tenant", O.PrimaryKey)
    def name: Rep[String] = column[String]("name")
    def description: Rep[Option[String]] = column[Option[String]]("description")
    def area: Rep[String] = column[String]("area")
    def legal: Rep[String] = column[String]("legal")
    def gridOperatorCode: Rep[String] = column[String]("gridoperator_code")
    def gridOperatorId: Rep[String] = column[String]("gridoperator_name")
    def contactPerson: Rep[Option[String]] = column[Option[String]]("contactPerson")
    def allocationMode: Rep[String] = column[String]("allocationMode")

    def * : ProvenShape[Eeg] = (tenant.?, name, description, area, legal, gridOperatorCode, gridOperatorId, contactPerson, allocationMode) <> (Eeg.tupled, Eeg.unapply)
  }

  val eegs = TableQuery[Eegs]
}
