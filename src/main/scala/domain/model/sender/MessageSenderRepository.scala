package domain.model.sender

import domain.model.chunkstream.ChunkStreamRepository.Channel
import domain.model.exception.{GatewayError, NoResponseAvailable}
import domain.model.exception.NoResponseAvailable.NoResponseAvailable
import zio.console.Console
import zio.{Chunk, Has, ZIO}
import zio.nio.core.SocketAddress

// TODO Remove Console from Environment
object MessageSenderRepository {

  type MessageSenderRepository = Has[MessageSenderRepository.Service]

  trait Service {
    def sendMessage(to: SocketAddress, msg: Either[NoResponseAvailable, Chunk[Byte]]): ZIO[Channel with Console, GatewayError, Unit]
  }

  def sendMessage(
    to: SocketAddress,
    msg: Either[NoResponseAvailable, Chunk[Byte]]
  ): ZIO[MessageSenderRepository with Channel with Console, GatewayError, Unit] =
    ZIO.accessM(_.get.sendMessage(to, msg))
}

