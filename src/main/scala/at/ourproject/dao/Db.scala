package at.ourproject.dao

import slick.jdbc.JdbcBackend.Database
import slick.jdbc.{JdbcProfile, PostgresProfile}

trait Db {

  val db: Database
  val profile: JdbcProfile

}

trait DbInstance extends Db {
  override val db = Database.forConfig("slick.pgsql.vfeeg")
  override val profile = PostgresProfile
}

trait Profile {
  val profile: JdbcProfile
}