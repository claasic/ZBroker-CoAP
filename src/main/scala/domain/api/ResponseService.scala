package domain.api

import domain.api.CoapDeserializerService.IgnoredMessageWithId
import domain.model.coap.header._
import domain.model.coap._

import zio.Chunk

object ResponseService {

  def getAcknowledgment(msg: CoapMessage): Chunk[Byte] =
    CoapSerializerService.generateFromMessage(acknowledgeMessage(msg.header.msgID))

  def getResetMessage(msg: IgnoredMessageWithId): Chunk[Byte] =
    CoapSerializerService.generateFromMessage(resetMessage(msg._2))

  def hasResponse(msg: Either[IgnoredMessageWithId, CoapMessage]): Boolean =
    msg match {
      case Right(message) if message.isConfirmable => true
      case Left((_, _))                            => true
      case _                                       => false
    }

  private def resetMessage(id: CoapId): CoapMessage =
    CoapMessage.reset(id)

  private def acknowledgeMessage(id: CoapId): CoapMessage =
    CoapMessage.ack(id)
}

/*
  private def generateResponse(msg: Either[IgnoredMessageWithId, CoapMessage]): Either[SuccessfulFailure, CoapMessage] =
    msg match {
      case Right(message) if message.isConfirmable => Right(acknowledgeMessage(message.header.msgID))
      case Left((_, id))                           => Right(resetMessage(id))
      case _                                       => Left(NoResponseAvailable)
    }
 */