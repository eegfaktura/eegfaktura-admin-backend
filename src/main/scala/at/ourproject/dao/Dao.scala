package at.ourproject.dao

import scala.concurrent.ExecutionContext

trait Dao extends Repositories {
  this: Db =>

  implicit def executionContext: ExecutionContext
  def eegRepository: EegRepositoryComponent = EegRepository(this)
}
