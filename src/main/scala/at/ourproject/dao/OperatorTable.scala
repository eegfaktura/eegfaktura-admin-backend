package at.ourproject.dao

import slick.lifted.ProvenShape

trait OperatorTable { this: Profile =>

  import profile.api._

  class Operators(tag: Tag) extends Table[GridOperators](tag, Some("base"), "gridoperators") {
    def id: Rep[String] = column[String]("id")
    def name: Rep[String] = column[String]("name")

    def * : ProvenShape[GridOperators] = (id, name) <> (GridOperators.tupled, GridOperators.unapply)
  }

  val operators = TableQuery[Operators]
}
