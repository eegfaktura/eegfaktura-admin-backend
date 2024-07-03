package at.ourproject.dao

import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

case class Eeg(tenant: Option[String], name: String, description: String, area: String, legal: String, gridOperatorCode: String, gridOperatorId: String, contactPerson: String, allocationMode: String)
trait EegRepositoryComponent {
  def getAll() : Future[Seq[Eeg]]

  def getByTenant(tenant: String): Future[Option[Eeg]]
}

object EegRepository {
  final case class TenantNotFound(id: Int) extends Exception(s"Tenant with id $id not found.")

  def apply(db: Db)(implicit ec: ExecutionContext) = new EegRepository(db.db, db.profile)
}

class EegRepository(db: Database, p: JdbcProfile)(implicit ec: ExecutionContext) extends EegRepositoryComponent with EegTable with Profile {

  override val profile: JdbcProfile = p
  import profile.api._

  override def getAll(): Future[Seq[Eeg]] = db.run(eegs.result)

  override def getByTenant(tenant: String): Future[Option[Eeg]] = ???


}
