package domain.api

import domain.model.coap._
import utility.ChunkExtension._
import zio.{Chunk, UIO, ZIO}

import scala.annotation.tailrec
import scala.collection.immutable.HashMap

/**
 * This service provides the functionality to extract CoAP parameters from Chunk[Byte]
 * and transform them to a model representation of a CoAP message for internal usage.
 */
object CoapExtractionService {

  /**
   * Takes a chunk and attempts to convert it into a CoapMessage.
   * <p>
   * Calls via take and drop might return an empty Chunk instead of failing.
   * Therefore error handling has to be done on different layers of the transformation.
   * <p>
   * Error handling is done via short-circuiting since a malformed packet would throw
   * too many and mostly useless errors. Thus, a top-down error search is implemented.
   */
  def extractFromChunk(chunk: Chunk[Byte]): UIO[Either[InvalidCoapMessage, CoapMessage]] =
    ZIO.fromEither(for {
      header <- chunk.takeExactly(4).flatMap(headerFromChunk)
      body   <- chunk.dropExactly(4).flatMap(bodyFromChunk(_, header))
    } yield CoapMessage(header, body)).either

  /**
   * Attempts to form a CoapHeader when handed a Chunk of size 4.
   * Might fail with header parameter dependent errors.
   */
  private def headerFromChunk(chunk: Chunk[Byte]): Either[InvalidCoapMessage, CoapHeader] = {
    val (b1, b2, b3, b4) = (chunk(0), chunk(1), chunk(2), chunk(3))
    for {
      version <- getVersionFrom(b1)
      msgType <- getMsgTypeFrom(b1)
      tLength <- getTLengthFrom(b1)
      prefix  <- getCPrefixFrom(b2)
      suffix  <- getCSuffixFrom(b2)
      msgId   <- getMsgIdFrom(b3, b4)
    } yield CoapHeader(version, msgType, tLength ,prefix, suffix, msgId)
  }

  /**
   * Extracts the token from the body and attempts to recursively read the options from the body.
   * Before each attempt there is a check for a payload marker and end of chunk.
   *
   * @param chunk can be of any size smaller than the maximum datagram size
   *              which should be filtered by the buffer anyway.
   * @param header is required to extract the respective token length.
   * @return Either a CoapBody or an Exception
   */
  private def bodyFromChunk(chunk: Chunk[Byte], header: CoapHeader): Either[InvalidCoapMessage, CoapBody] = {

    /**
     * Recursively iterates over the chunk. It checks the current head for existence and on existence whether the
     * head is equal to the payload marker.
     *
     * @param rem the remainder of the Chunk[Byte] which is also the remainder of Datagram as a whole. The initial value
     *            should be the whole Datagram excluding the Header as well as the Token.
     * @param acc the accumulator that holds the Options that were gathered so far.
     * @param num a tracker for the sum of all deltas and therefore the last accessed option number
     * @return Either an exception or a list of all options and possibly a payload in an option monad
     */
    @tailrec
    def getOptionsAsListFrom(
        rem: Chunk[Byte],
        acc: List[CoapOption] = List.empty,
        num: Int = 0
      ): Either[InvalidCoapMessage, (List[CoapOption], Chunk[Byte])] = {
        rem.headOption match {
          // recursively iterates over the chunk and builds a list of the options or throws an exception
          case Some(b) if b != 0xFF.toByte => parseNextOption(b, rem, num) match {
            case Right(r) => getOptionsAsListFrom(rem.drop(r.offset.value), r :: acc, r.value.number.value)
            case Left(er) => Left(er)
          }
          // a payload marker was detected - according to protocol this fails if there is a marker but no load
          case Some(_) => if (rem.tail.nonEmpty) Right(acc.reverse, rem.drop(1))
                          else Left(InvalidPayloadMarker("Promised payload is missing. Protocol error."))
          case None    => Right(acc.reverse, Chunk.empty)
        }
    }

    // extract the token and continue with the remainder
    val tokenLength = header.tLength.value

    // makes use of the tokenLength defined above
    def extractTokenFrom(chunk: Chunk[Byte]): Either[InvalidCoapMessage, CoapToken] =
      chunk.takeExactly(tokenLength).map(CoapToken)

    // sequentially extract the token, then all the options and lastly gets the payload
    for {
      token              <- extractTokenFrom(chunk)
      remainder          <- chunk.dropExactly(tokenLength)
      optsPay            <- getOptionsAsListFrom(remainder)
      (options, payload) = optsPay
      tokenO             = if (token.value.nonEmpty) Some(token) else None
      optionsO           = if (options.nonEmpty) Some(options) else None
      payloadO           = if (payload.nonEmpty) getPayloadFromWith(payload, getPayloadMediaTypeFrom(options)) else None
    } yield CoapBody(tokenO, optionsO, payloadO)
  }

  private def parseNextOption(
    optionHeader: Byte,
    chunk: Chunk[Byte],
    num: Int
  ): Either[InvalidCoapMessage, CoapOption] = {
    // option header is always one byte - empty check happens during parameter extraction
    val optionBody = chunk.drop(1)
    for {
      // extract delta value from header, possibly extend to second and third byte and pass resulting offset
      deltaTriplet  <- getDelta(optionHeader, optionBody)
      (delta, extDelta, deltaOffset) = deltaTriplet
      // extract length value from header, possible extension which depends on the offset of the delta value, pass offset
      lengthTriplet <- getLength(optionHeader, optionBody, deltaOffset)
      (length, extLength, lengthOffset) = lengthTriplet
      // get the new number as a sum of all previous deltas given by the num parameter and the newest delta
      number        <- CoapOptionNumber(num + delta.value)
      // get the value starting at the position based on the two offsets, ending at that value plus the length value
      value         <- getValue(optionBody, length, deltaOffset + lengthOffset, number)
      // offset can be understood as the size of the parameter group
      offset         = CoapOptionOffset(deltaOffset.value + lengthOffset.value + length.value + 1)
    } yield CoapOption(delta, extDelta, length, extLength, value, offset)
  }

  // TODO: Refactor
  private def getDelta(
    headerByte: Byte,
    remainder: Chunk[Byte]
  ): Either[InvalidCoapMessage, (CoapOptionDelta, Option[CoapOptionExtendedDelta], CoapOptionOffset)] =
    (headerByte & 0xF0) >>> 4 match {
      case 13 => for {
          i <- getFirstByteFrom(remainder)
          d <- CoapOptionDelta(13)
          e <- CoapOptionExtendedDelta(i + 13)
        } yield (d, Some(e), CoapOptionOffset(1))
      case 14 => for {
          i <- getFirstTwoBytesAsInt(remainder.take(2))
          d <- CoapOptionDelta(14)
          e <- CoapOptionExtendedDelta(i + 269)
        } yield (d, Some(e), CoapOptionOffset(2))
      case 15 => Left(InvalidOptionDelta("15 is a reserved value."))
      case other if 0 to 12 contains other => for {
          d <- CoapOptionDelta(other)
        } yield (d, None, CoapOptionOffset(0))
      case e => Left(InvalidOptionDelta(s"Illegal delta value of $e. Initial value must be between 0 and 15."))
    }

  // TODO: Refactor
  private def getLength(
    headerByte: Byte,
    remainder: Chunk[Byte],
    deltaOffset: CoapOptionOffset
  ): Either[InvalidCoapMessage, (CoapOptionLength, Option[CoapOptionExtendedLength], CoapOptionOffset)] =
    headerByte & 0x0F match {
      case 13 => for {
          i <- getFirstByteFrom(remainder.drop(deltaOffset.value).take(1))
          l <- CoapOptionLength(13)
          e <- CoapOptionExtendedLength(i + 13)
        } yield (l, Some(e), CoapOptionOffset(2))
      case 14 => for {
          i <- getFirstTwoBytesAsInt(remainder.drop(deltaOffset.value).take(2))
          l <- CoapOptionLength(14)
          e <- CoapOptionExtendedLength(i + 269)
        } yield (l, Some(e), CoapOptionOffset(2))
      case 15 => Left(InvalidOptionLength("15 is a reserved length value."))
      case other if 0 to 12 contains other => for {
          l <- CoapOptionLength(other)
       } yield (l, None, CoapOptionOffset(0))
      case e => Left(InvalidOptionLength(s"Illegal length value of $e. Initial value must be between 0 and 15"))
    }

  private def getValue(
    chunk: Chunk[Byte],
    length: CoapOptionLength,
    offset: CoapOptionOffset,
    number: CoapOptionNumber
  ): Either[InvalidCoapMessage, CoapOptionValue] =
    chunk.dropExactly(offset.value).flatMap(_.takeExactly(length.value)).map(CoapOptionValue(number, _))

  private def getFirstByteFrom(bytes: Chunk[Byte]): Either[InvalidCoapMessage, Int] =
    bytes.takeExactly(1).map(_.head.toInt)

  // TODO: 0xFF necessary?
  private def getFirstTwoBytesAsInt(bytes: Chunk[Byte]): Either[InvalidCoapMessage, Int] =
    bytes.takeExactly(2).map(chunk => ((chunk(0) << 8) & 0xFF) | (chunk(1) & 0xFF))

  private def getVersionFrom(b: Byte): Either[InvalidCoapMessage, CoapVersion] =
    CoapVersion((b & 0xF0) >>> 6)

  private def getMsgTypeFrom(b: Byte): Either[InvalidCoapMessage, CoapType] =
    CoapType((b & 0x30) >> 4)

  private def getTLengthFrom(b: Byte): Either[InvalidCoapMessage, CoapTokenLength] =
    CoapTokenLength(b & 0x0F)

  private def getCPrefixFrom(b: Byte): Either[InvalidCoapMessage, CoapCodePrefix] =
    CoapCodePrefix((b & 0xE0) >>> 5)

  private def getCSuffixFrom(b: Byte): Either[InvalidCoapMessage, CoapCodeSuffix] =
    CoapCodeSuffix(b & 0x1F)

  private def getMsgIdFrom(third: Byte, fourth: Byte): Either[InvalidCoapMessage, CoapId] =
    CoapId(((third & 0xFF) << 8) | (fourth & 0xFF))

  /**
   * As long as the number of options per message are low the function complexity does not matter in comparison
   * to the overhead and therefore this is preferable to an HashMap solution
   */
  private def getPayloadMediaTypeFrom(list: List[CoapOption]): CoapPayloadMediaType =
    list.find(_.value.number.value == 12) match {
        case Some(option) => option.value.content match {
          case c : IntCoapOptionContent => CoapPayloadMediaType.fromInt(c.value)
          case _                        => SniffingMediaType
        }
        case None => SniffingMediaType
      }


  // TODO: FULLY IMPLEMENT
  private def getPayloadFromWith(chunk: Chunk[Byte], payloadMediaType: CoapPayloadMediaType): Option[CoapPayload] =
    payloadMediaType match {
        case TextMediaType     => Some(CoapPayload(TextCoapPayloadContent(chunk)))
        case SniffingMediaType => Some(CoapPayload(TextCoapPayloadContent(chunk)))
        case _                 => None
      }

  /**
   * Converts a List[CoapOption] to a HashMap so that each Option is directly addressable via
   * its CoapOptionNumber. This might be required in situations where there is a direct check
   * for the existence of an option - probably an overkill anyway.
   */
  private def convertOptionListToMap(list: List[CoapOption]): HashMap[CoapOptionNumber, List[CoapOption]] =
    list.foldRight(HashMap[CoapOptionNumber, List[CoapOption]]()) { (c, acc) =>
      val number = c.value.number
      if (acc.isDefinedAt(number))
        if (CoapOptionNumber.getProperties(number)._4) acc + (number -> (c :: acc(number)))
        else acc
      else acc + (number -> List(c))
    }
}


