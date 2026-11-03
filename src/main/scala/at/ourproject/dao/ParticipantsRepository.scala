package at.ourproject.dao

import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

case class EegParticipant (id: String, tenant: String, firstName: String, lastName: String, businessRole: String, status: String, meters: Seq[EegMeteringPoint] = Nil)


trait EegParticipantsRepositoryComponent {
  def getById(id: String): Future[Option[EegParticipant]]
  def getFullParticipantByTenant(tenant: String): Future[Seq[EegParticipant]]
}

object ParticipantsRepository {
  def apply(db: Db)(implicit ec: ExecutionContext) = new ParticipantsRepository(db.db, db.profile)

  /** One participant per id with the meters of its rows. */
  private[dao] def withMeters(rows: Seq[(EegParticipant, Option[EegMeteringPoint])]): Seq[EegParticipant] =
    rows.groupBy(_._1.id).values.map(tuples => tuples.head._1.copy(meters = tuples.flatMap(_._2))).toSeq
}
class ParticipantsRepository(db: Database, p: JdbcProfile)(implicit ec: ExecutionContext) extends EegParticipantsRepositoryComponent with ParticipantsTable with MetersTable with Profile {
  import ParticipantsRepository.withMeters

  override val profile: JdbcProfile = p
  import profile.api._

  /** None also for a participant without a meter (inner join, as before). */
  override def getById(id: String): Future[Option[EegParticipant]] = {
    val innerJoin = for {
      p <- participants if p.id === id
      m <- meters if p.id === m.participantId
    } yield (p, m)

    db.run(innerJoin.result).map(rows => withMeters(rows.map { case (p, m) => (p, Some(m)) }).headOption)
  }

  // Each participant with its own meters. A participant without a meter is listed only if the community has a meter
  // at all: the result the former join on the tenant gave, kept as it was.
  private[dao] def fullParticipantsQuery(tenant: String) =
    participants
      .filter(p => p.tenant === tenant && meters.filter(_.tenant === tenant).exists)
      .joinLeft(meters).on((p, m) => p.id === m.participantId && m.tenant === p.tenant)

  override def getFullParticipantByTenant(tenant: String): Future[Seq[EegParticipant]] =
    db.run(fullParticipantsQuery(tenant).result).map(withMeters)

}
