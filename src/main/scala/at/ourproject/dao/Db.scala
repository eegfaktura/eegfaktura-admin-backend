package at.ourproject.dao

import slick.jdbc.JdbcBackend.Database
import slick.jdbc.{JdbcProfile, PostgresProfile}

trait Db {

  val db: Database
  val profile: JdbcProfile
//  val config: DatabaseConfig[JdbcProfile]

}

//object Db {
//  def getConfig: DatabaseConfig[JdbcProfile] = {
//    DatabaseConfig.forConfig[JdbcProfile]("slick.pgsql.local.default")
//  }
//}

trait DbInstance extends Db {
  override val db = Database.forConfig("slick.pgsql.vfeeg")
  override val profile = PostgresProfile
//  override val config = DatabaseConfig.forConfig[JdbcProfile]("slick.pgsql.local.default")
}

trait Profile {
  val profile: JdbcProfile
}