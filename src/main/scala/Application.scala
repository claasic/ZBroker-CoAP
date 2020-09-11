
import domain.api.CoapExtractionService
import domain.model.chunkstream.ChunkStreamRepository

import infrastructure.environment.{ChunkStreamRepositoryEnvironment, ConfigRepositoryEnvironment, EndpointEnvironment}
import infrastructure.persistance.chunkstream.OutgoingStream // TODO: remove after testing

import zio.App
import zio.console._
import zio.stream._

object Application extends App {

  val program =
    (for {
      _ <- ZStream.fromEffect(putStrLn("booting up ..."))
      _ <- ZStream.mergeAll(2, 16)(
        ChunkStreamRepository
          .getChunkStream
          .tap(b => putStrLn(b._2.toString))
          .mapM({ case (_, c) => CoapExtractionService.extractFromChunk(c) })
          .tap(a => putStrLn(a.fold(_.fullMsg, _.toString)))
        , OutgoingStream.send)
    } yield ()).runDrain

  val partialLayer = (
    ConfigRepositoryEnvironment.fromMemory
    >+> EndpointEnvironment.fromChannel
    >+> ChunkStreamRepositoryEnvironment.fromSocket)

  def run(args: List[String]) =
    program.tap(l => putStrLn(l.toString)).provideCustomLayer(partialLayer).orDie.exitCode

}

