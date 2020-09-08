package infrastructure.chunkstream

import zio.console._
import zio.duration._
import zio.{Schedule, ZIO}

import zio.nio.core.channels.DatagramChannel
import zio.nio.core.{Buffer, SocketAddress}

import zio.stream.ZStream

// TODO: Burn this down
object OutgoingStream {

  def send =
    ZStream.repeatEffectWith( {
      for {
        buffer  <- Buffer.byte(20)
        _       <- buffer.put(5)
        address <- SocketAddress.inetSocketAddress("127.0.0.1", 8080)
        server  <- ZIO.service[DatagramChannel]
        _       <- putStrLn("SEND")
        i       <- server.send(buffer, address)
      } yield i
    }, Schedule.spaced(1.second) && Schedule.recurs(1))
}