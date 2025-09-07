package at.ourproject.dao

import at.ourproject.dao.SettlementIntervalType.SettlementIntervalType
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}
import io.circe.{Decoder, Encoder}

object SettlementIntervalType extends Enumeration {
  type SettlementIntervalType = Value

  implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
  implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

  val ANNUAL: SettlementIntervalType.Value = Value("ANNUAL")
  val BIANNUAL: SettlementIntervalType.Value = Value("BIANNUAL")
  val QUARTER: SettlementIntervalType.Value = Value("QUARTER")
  val MONTHLY: SettlementIntervalType.Value = Value("MONTHLY")
}

case class Eeg(tenant: Option[String], name: String, description: Option[String], area: String,
               legal: String,
               gridOperatorCode: String, gridOperatorId: String,
               contactPerson: Option[String], allocationMode: String, online: Boolean, settlementInterval: SettlementIntervalType)
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

  override def getByTenant(tenant: String): Future[Option[Eeg]] = db.run(eegs.filter(_.tenant === tenant).result.headOption)


}
