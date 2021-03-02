package vulcan.binary

import cats.data.Chain
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult, SizeBound, codecs, Codec => Scodec}
import vulcan.internal.converters.collection._

import java.util

class ArrayScodec[A](codec: Scodec[A]) extends Scodec[util.List[A]] {
  override def decode(bits: BitVector): Attempt[DecodeResult[util.List[A]]] = {
    def decodeBlock(bits: BitVector): Attempt[DecodeResult[List[A]]] =
      blockScodec(codec).decode(bits)

    def loop(remaining: BitVector, acc: Chain[A]): Attempt[DecodeResult[Chain[A]]] =
      decodeBlock(remaining).flatMap {
        case DecodeResult(value, remainder) =>
          if (value.isEmpty) Attempt.successful(DecodeResult(acc, remainder))
          else loop(remainder, acc ++ Chain.fromSeq(value))
      }

    loop(bits, Chain.empty).map(_.map(_.toList.asJava))
  }

  override def encode(value: util.List[A]): Attempt[BitVector] = {
    intScodec.encode(value.size).flatMap { bits =>
      codecs
        .list(codec)
        .encode(value.asScala.toList)
        .map(bits ++ _)
        .flatMap(
          bits =>
            (if (value.size != 0) intScodec.encode(0)
             else Attempt.successful(BitVector.empty)).map(bits ++ _)
        )
    }
  }

  override val sizeBound: SizeBound = intScodec.sizeBound.atLeast
}

object ArrayScodec {
  def apply[A](elementCodec: Scodec[A]) = new ArrayScodec(elementCodec)
}