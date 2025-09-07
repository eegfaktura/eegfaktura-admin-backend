package at.ourproject.dao

import scala.concurrent.ExecutionContext

trait Dao extends Repositories {
  this: Db =>

  implicit def executionContext: ExecutionContext

  override def eegRepository: EegRepositoryComponent = EegRepository(this)
  override def participantRepository: ParticipantsRepository = ParticipantsRepository(this)
  override def meteringRepository: MetersRepository = MetersRepository(this)
  override def operatorRepository: OperatorRepositoryComponent = OperatorRepository(this)
}
