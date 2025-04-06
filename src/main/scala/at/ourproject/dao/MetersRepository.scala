package at.ourproject.dao

case class EegMeteringPoint (tenant: String, meteringPoint: String, direction: String, status: String, consentId: Option[String])

object MetersRepository {

}
