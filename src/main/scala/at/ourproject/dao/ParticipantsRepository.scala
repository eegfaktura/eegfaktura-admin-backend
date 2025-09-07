package at.ourproject.dao

import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

case class EegParticipant (id: String, tenant: String, firstName: String, lastName: String, businessRole: String, status: String, meters: Seq[EegMeteringPoint] = Nil)


trait EegParticipantsRepositoryComponent {
  def getByTenant(tenant: String): Future[Seq[EegParticipant]]
  def getById(id: String): Future[EegParticipant]
  def getFullParticipantByTenant(tenant: String): Future[Seq[EegParticipant]]
}

object ParticipantsRepository {
  def apply(db: Db)(implicit ec: ExecutionContext) = new ParticipantsRepository(db.db, db.profile)
}
class ParticipantsRepository(db: Database, p: JdbcProfile)(implicit ec: ExecutionContext) extends EegParticipantsRepositoryComponent with ParticipantsTable with MetersTable with Profile {

  override val profile: JdbcProfile = p
  import profile.api._

  override def getByTenant(tenant: String): Future[Seq[EegParticipant]] = db.run(participants.filter(_.tenant === tenant).result)

  override def getById(id: String): Future[EegParticipant] = {
    val innerJoin = for {
      p <- participants if p.id === id
      m <- meters if p.id === m.participantId
    } yield (p, m)

    db.run(innerJoin.result).map{
      dataTuples =>
        val grouped = dataTuples.groupBy(_._1.id)
        grouped.map {
          case (r, tuples) =>
            var (e, _) = tuples.head
            EegParticipant(e.id, e.tenant, e.firstName, e.lastName, e.businessRole, e.status, tuples.map(_._2).filter(m => m.participantId == e.id))
        }.head
    }
  }

  override def getFullParticipantByTenant(tenant: String): Future[Seq[EegParticipant]] = {
    val innerJoin = for {
      p <- participants if p.tenant === tenant
      m <- meters if p.tenant === m.tenant
    } yield (p, m)

    db.run(innerJoin.result).map {
      dataTuples =>
        val grouped = dataTuples.groupBy(_._1.id)
        grouped.map {
          case (r, tuples) =>
          var (e, _) = tuples.head
            EegParticipant(e.id, e.tenant, e.firstName, e.lastName, e.businessRole, e.status, tuples.map(_._2).filter(m => m.participantId == e.id))
        }.toSeq
    }
  }

}