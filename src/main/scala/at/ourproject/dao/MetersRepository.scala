package at.ourproject.dao

import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

case class EegMeteringPoint (tenant: String, meteringPoint: String, direction: String,
                             status: String, consentId: Option[String], participantId: String,
                             processState: String, activeSince: Option[java.sql.Date], inactiveSince: Option[java.sql.Date])

trait EegMeteringPointRepositoryComponent {
  def getMeteringPoint(tenant: String, participantId: String, meteringPoint: String): Future[EegMeteringPoint]
}

object MetersRepository {
  def apply(db: Db)(implicit ec: ExecutionContext) = new MetersRepository(db.db, db.profile)
}

class MetersRepository(db: Database, p: JdbcProfile)(implicit ec: ExecutionContext) extends EegMeteringPointRepositoryComponent with MetersTable with Profile {
  override val profile: JdbcProfile = p

  import profile.api._

  override def getMeteringPoint(tenant: String, participantId: String, meteringPoint: String): Future[EegMeteringPoint] =
    db.run(meters
      .filter(_.tenant === tenant)
      .filter(_.participantId === participantId)
      .filter(_.meteringPoint ===meteringPoint)
      .result.head)
}