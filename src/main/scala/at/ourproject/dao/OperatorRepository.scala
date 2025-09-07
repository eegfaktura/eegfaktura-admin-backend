package at.ourproject.dao

import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

case class GridOperators(id: String, name: String)

trait OperatorRepositoryComponent {
  def getAll(): Future[Seq[GridOperators]]
}

class OperatorRepository(db: Database, p: JdbcProfile)(implicit ec: ExecutionContext) extends OperatorRepositoryComponent with OperatorTable with Profile {
  override val profile: JdbcProfile = p
  import profile.api._

  override def getAll(): Future[Seq[GridOperators]] = db.run(operators.result)
}

object OperatorRepository {
  def apply(db: Db)(implicit ec: ExecutionContext) = new OperatorRepository(db.db, db.profile)
}
