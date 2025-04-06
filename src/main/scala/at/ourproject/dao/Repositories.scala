package at.ourproject.dao

trait Repositories {
  def eegRepository: EegRepositoryComponent
  def participantRepository: EegParticipantsRepositoryComponent
}
