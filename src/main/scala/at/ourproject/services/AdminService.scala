package at.ourproject.services

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.grpc.GrpcClientSettings
import at.ourproject.admin.{AdminEegServiceClient, UpdateEegRequest}
import at.ourproject.routes.{UpdateClassEnum, UpdateMessage}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object AdminService {

  trait Command

  case class MessageUpdate(updateMsg: UpdateMessage, replyTo: ActorRef[Command]) extends Command

  case object MessageUpdateOk extends Command

  case class MessageUpdateFail(status: Int, msg: String) extends Command

  def apply(): Behavior[Command] = Behaviors.setup[Command] { context =>
    implicit def system: ActorSystem[Nothing] = context.system

    implicit def ec: ExecutionContext = system.executionContext

    //      context.system.receptionist ! Receptionist.Register(NodeServiceKey, context.self)

    val adminClient = AdminEegServiceClient(GrpcClientSettings.fromConfig("register.RegisterEegService"))

    Behaviors.receiveMessage { message => {
      message match {
        case MessageUpdate(msg, replyTo) =>
          msg.updateClass match {
            case UpdateClassEnum.PROCESSSTATUS =>
              adminClient.updateValue(UpdateEegRequest(
                updateClass = UpdateEegRequest.UPDATE_CLASS.PROCESSSTATUS,
                tenant = msg.tenant,
                participantId = msg.participantId,
                meteringPoint = msg.meteringPoint,
                value = msg.value,
              )) onComplete {
                case Success(reply) if reply.status == 200 || reply.status == 201 => replyTo ! MessageUpdateOk
                case Success(reply) => replyTo ! MessageUpdateFail(reply.status, reply.message)
                case Failure(ex) => replyTo ! MessageUpdateFail(500, ex.getMessage)
              }
            case UpdateClassEnum.INACTIVESINCE =>
              adminClient.updateValue(UpdateEegRequest(
                updateClass = UpdateEegRequest.UPDATE_CLASS.INACTIVESINCE,
                tenant = msg.tenant,
                participantId = msg.participantId,
                meteringPoint = msg.meteringPoint,
                value = msg.value,
              )) onComplete {
                case Success(reply) if reply.status == 200 || reply.status == 201 => replyTo ! MessageUpdateOk
                case Success(reply) => replyTo ! MessageUpdateFail(reply.status, reply.message)
                case Failure(ex) => replyTo ! MessageUpdateFail(500, ex.getMessage)
              }
            case UpdateClassEnum.ACTIVESINCE =>
              adminClient.updateValue(UpdateEegRequest(
                updateClass = UpdateEegRequest.UPDATE_CLASS.ACTIVESINCE,
                tenant = msg.tenant,
                participantId = msg.participantId,
                meteringPoint = msg.meteringPoint,
                value = msg.value,
              )) onComplete {
                case Success(reply) if reply.status == 200 || reply.status == 201 => replyTo ! MessageUpdateOk
                case Success(reply) => replyTo ! MessageUpdateFail(reply.status, reply.message)
                case Failure(ex) => replyTo ! MessageUpdateFail(500, ex.getMessage)
              }
            case UpdateClassEnum.EEG =>
              adminClient.updateValue(UpdateEegRequest(
                updateClass = UpdateEegRequest.UPDATE_CLASS.EEG,
                tenant = msg.tenant,
                value = msg.value,
              )) onComplete {
                case Success(reply) if reply.status == 200 || reply.status == 201 => replyTo ! MessageUpdateOk
                case Success(reply) => replyTo ! MessageUpdateFail(reply.status, reply.message)
                case Failure(ex) => replyTo ! MessageUpdateFail(500, ex.getMessage)
              }
            case UpdateClassEnum.PARTICIPANT =>
              adminClient.updateValue(UpdateEegRequest(
                updateClass = UpdateEegRequest.UPDATE_CLASS.PARTICIPANT,
                tenant = msg.tenant,
                participantId = msg.participantId,
                value = msg.value,
              )) onComplete {
                case Success(reply) if reply.status == 200 || reply.status == 201 => replyTo ! MessageUpdateOk
                case Success(reply) => replyTo ! MessageUpdateFail(reply.status, reply.message)
                case Failure(ex) => replyTo ! MessageUpdateFail(500, ex.getMessage)
              }
          }
      }
      Behaviors.same
    }}
  }
}
