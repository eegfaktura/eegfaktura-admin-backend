package at.ourproject.dao

import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

trait TenantUserTable { this: Db =>

  import config.profile.api._

  class TenantUsers(tag: Tag) extends Table[KeycloakUser](tag, Some("admin"), "keycloakuser") {
    def id: Rep[UUID] = column[UUID]("id", O.PrimaryKey)
    def tenant: Rep[String] = column[String]("tenant")
    def username: Rep[Option[String]] = column[Option[String]]("username")
    def firstname: Rep[String] = column[String]("firstname")
    def lastname: Rep[String] = column[String]("lastname")
    def email: Rep[String] = column[String]("email")
    def invitedAt: Rep[LocalDateTime] = column[LocalDateTime]("invitedAt")
    def createdAt: Rep[LocalDateTime] = column[LocalDateTime]("createdAt")
    def status: Rep[Int] = column[Int]("status")
    def * : ProvenShape[KeycloakUser] = (id, tenant, username, firstname, lastname, email, invitedAt, createdAt, status) <> (KeycloakUser.tupled, KeycloakUser.unapply)
  }

  val tenantUsers = TableQuery[TenantUsers]
}