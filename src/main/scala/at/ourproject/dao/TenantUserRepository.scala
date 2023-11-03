package at.ourproject.dao

import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

case class KeycloakUser(id: UUID, tenant: String, username: Option[String], firtsname: String, lastname: String, email: String, invitedAt: LocalDateTime, createdAt: LocalDateTime, status: Int)

trait TenantUserRepository {
  def byTenant(tenant: String): Future[Option[KeycloakUser]]
  def create(tenant: KeycloakUser): Future[KeycloakUser]
//
//  def update(id: Int, updateInquest: UpdateInquest): Future[TenantConfig]

}

object TenantUserRepository {
  final case class TenantNotFound(id: Int) extends Exception(s"Tenant with id $id not found.")
}

class SlickTenantUserRepository(databaseConfig: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext)
  extends TenantUserRepository with Db with TenantUserTable {

  override val db = databaseConfig.db
  override val config = databaseConfig

  import config.profile.api._

  override def byTenant(tenant: String): Future[Option[KeycloakUser]] = {
    val q = tenantUsers.filter(_.tenant === tenant).take(1)
    db.run(q.result).map(_.headOption)
  }

  override def create(user: KeycloakUser): Future[KeycloakUser] = db.run {
    (tenantUsers returning tenantUsers.map(_.tenant) into ((_, tenant) => user.copy(tenant = tenant))) += user
  }


  def init(): Unit = {
    db.run(DBIO.seq(tenantUsers.schema.createIfNotExists))
  }
}

