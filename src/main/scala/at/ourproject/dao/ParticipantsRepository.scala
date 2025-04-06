package at.ourproject.dao

import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

case class EegParticipant (id: String, tenant: String, firstName: String, lastName: String, businessRole: String, status: String, meters: Seq[EegMeteringPoint] = Nil)


trait EegParticipantsRepositoryComponent {
  def getByTenant(tenant: String): Future[Seq[EegParticipant]]
  def getFullParticipantByTenant(tenant: String): Future[Seq[EegParticipant]]
}

object ParticipantsRepository {
  def apply(db: Db)(implicit ec: ExecutionContext) = new ParticipantsRepository(db.db, db.profile)
}
class ParticipantsRepository(db: Database, p: JdbcProfile)(implicit ec: ExecutionContext) extends EegParticipantsRepositoryComponent with ParticipantsTable with MetersTable with Profile {

  override val profile: JdbcProfile = p
  import profile.api._

  override def getByTenant(tenant: String): Future[Seq[EegParticipant]] = db.run(participants.filter(_.tenant === tenant).result)


  override def getFullParticipantByTenant(tenant: String): Future[Seq[EegParticipant]] = {
//    val q = (participants filter (_.tenant === tenant) joinLeft meters on (_.tenant === _.tenant))

    val innerJoin = for {
      p <- participants if p.tenant === tenant
      m <- meters if p.tenant === m.tenant
    } yield (p, m)

    db.run(innerJoin.result).map {
      dataTuples =>
        val grouped = dataTuples.groupBy(_._1)
        grouped.map {
          case (r, rr) => EegParticipant(r.id, r.tenant, r.firstName, r.lastName, r.businessRole, r.status, rr.map(_._2))
        }.toSeq
    }

//    val q = (participants filter (_.tenant === tenant) join meters) on (_.tenant === _.tenant)
//    db.run(q.result).map(d => d.map(_._1).toSeq)
//    val q = (meters filter (_.tenant === tenant))
//    db.run(q.result).map(_.map(a => new EegParticipant(a.tenant, a.meteringPoint, "", "", "")))
  }

}