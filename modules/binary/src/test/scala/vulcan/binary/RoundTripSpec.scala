package vulcan.binary

import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import org.apache.avro.util.Utf8
import org.apache.avro.{Schema, SchemaBuilder}
import org.scalacheck.Arbitrary
import org.scalatest.Assertion
import scodec.bits.BitVector
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import vulcan.Codec
import vulcan.internal.converters.collection._
import vulcan.binary.examples.{CaseClassThreeFields, SealedTraitEnum}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.ByteBuffer
import org.apache.avro.generic.GenericFixed
import cats.kernel.Eq
import vulcan.binary.examples.SealedTraitCaseClass

class RoundTripSpec extends BaseSpec with RoundTripHelpers {
  implicit val arbFloat: Arbitrary[java.lang.Float] = Arbitrary(
    implicitly[Arbitrary[Float]].arbitrary.map(java.lang.Float.valueOf(_))
  )

  implicit val arbDouble: Arbitrary[java.lang.Double] = Arbitrary(
    implicitly[Arbitrary[Double]].arbitrary.map(java.lang.Double.valueOf(_))
  )

  implicit val arbInteger: Arbitrary[java.lang.Integer] = Arbitrary(
    implicitly[Arbitrary[Int]].arbitrary.map(java.lang.Integer.valueOf(_))
  )

  implicit val arbLong: Arbitrary[java.lang.Long] = Arbitrary(
    implicitly[Arbitrary[Long]].arbitrary.map(java.lang.Long.valueOf(_))
  )

  implicit val arbUtf8: Arbitrary[Utf8] = Arbitrary(
    implicitly[Arbitrary[String]].arbitrary.map(new Utf8(_))
  )

  implicit val arbBool: Arbitrary[java.lang.Boolean] = Arbitrary(
    implicitly[Arbitrary[Boolean]].arbitrary.map(java.lang.Boolean.valueOf(_))
  )

  implicit val arbRecord: Arbitrary[GenericRecord] = Arbitrary(
    arbitrary[CaseClassThreeFields]
      .map(Codec.encode(_).value.asInstanceOf[GenericRecord])
  )

  implicit def arbList[A: Arbitrary]: Arbitrary[java.util.List[A]] = Arbitrary(
    arbitrary[List[A]].map(_.asJava)
  )

  implicit val arbEnumSymbol: Arbitrary[GenericData.EnumSymbol] = Arbitrary(
    arbitrary[SealedTraitEnum].map(entry => SealedTraitEnum.codec.encode(entry).value)
  )

  def genBytes(length: Int): Gen[Array[Byte]] =
    Gen.listOfN(length, arbitrary[Byte]).map(_.toArray)

  describe("float") {
    it("roundtrip") {
      implicit val eq: Eq[java.lang.Float] = Eq.fromUniversalEquals
      roundtrip[java.lang.Float](SchemaBuilder.builder().floatType())
    }
  }

  describe("double") {
    it("roundtrip") {
      implicit val eq: Eq[java.lang.Double] = Eq.fromUniversalEquals
      roundtrip[java.lang.Double](SchemaBuilder.builder().doubleType())
    }
  }

  describe("int") {
    it("roundtrip") {
      implicit val eq: Eq[java.lang.Integer] = Eq.fromUniversalEquals
      roundtrip[java.lang.Integer](SchemaBuilder.builder().intType())
    }
  }

  describe("long") {
    it("roundtrip") {
      implicit val eq: Eq[java.lang.Long] = Eq.fromUniversalEquals
      roundtrip[java.lang.Long](SchemaBuilder.builder().longType())
    }
  }

  describe("string") {
    it("roundtrip") {
      implicit val eq: Eq[Utf8] = Eq.fromUniversalEquals
      roundtrip[Utf8](SchemaBuilder.builder().stringType())
    }
  }

  describe("boolean") {
    it("roundtrip") {
      implicit val eq: Eq[java.lang.Boolean] = Eq.fromUniversalEquals
      roundtrip[java.lang.Boolean](SchemaBuilder.builder().booleanType())
    }
  }

  describe("array") {
    it("roundtrip") {
      implicit val eq: Eq[java.util.List[java.lang.Long]] = Eq.fromUniversalEquals
      roundtrip[java.util.List[java.lang.Long]](
        SchemaBuilder.builder().array().items(SchemaBuilder.builder().longType())
      )
    }
  }

  describe("null") {
    it("roundtrip") {
      implicit val eq: Eq[Null] = Eq.fromUniversalEquals
      scodecThenJava(null, SchemaBuilder.builder().nullType)
      javaThenScodec(null, SchemaBuilder.builder().nullType)
    }
  }

  describe("Record") {
    implicit val eq: Eq[GenericRecord] = Eq.fromUniversalEquals
    it("roundtrip") { roundtrip[GenericRecord](Codec[CaseClassThreeFields].schema.value) }
  }

  describe("Enum") {
    implicit val eq: Eq[GenericData.EnumSymbol] = Eq.fromUniversalEquals
    it("roundtrip") {
      roundtrip[GenericData.EnumSymbol](Codec[SealedTraitEnum].schema.value)
    }
  }

  describe("Bytes") {
    it("roundtrip") {
      implicit val eq: Eq[ByteBuffer] = Eq.fromUniversalEquals
      implicit val arb: Arbitrary[ByteBuffer] = Arbitrary(
        Gen.chooseNum(0, 1024).flatMap(genBytes).map(ByteBuffer.wrap)
      )
      roundtrip[ByteBuffer](Codec.bytes.schema.value)
    }
  }

  describe("Fixed") { 
    it("roundtrip") {
      val fixedSchema = Schema.createFixed("foo", null, "com.example", 10)
      implicit val arbitrary: Arbitrary[GenericFixed] = Arbitrary(genBytes(10).map{ b =>
        new GenericFixed {
          def getSchema() = fixedSchema
          def bytes = b
        }
      })

      implicit val eqS: Eq[Schema] = Eq.fromUniversalEquals
      implicit val eq: Eq[GenericFixed] = Eq.and(Eq.by(_.getSchema), Eq.by(_.bytes().toVector))

      roundtrip[GenericFixed](fixedSchema)
    }
  }

  describe("Union") { 
    it("roundtrip") {
      implicit val arb: Arbitrary[AnyRef] = Arbitrary(
        arbitrary[SealedTraitCaseClass].map(Codec[SealedTraitCaseClass].encode(_).value)
      )

      implicit val eq: Eq[AnyRef] = Eq.fromUniversalEquals

      roundtrip[AnyRef](SealedTraitCaseClass.sealedTraitCaseClassCodec.schema.value)
    }
  }
}

trait RoundTripHelpers {
  self: BaseSpec =>

  def roundtrip[A: Eq](schema: Schema)(
    implicit arbitrary: Arbitrary[A]
  ): Assertion = {
    forAll { (a: A) =>
      scodecThenScodec(a, schema)
      scodecThenJava(a, schema)
      javaThenScodec(a, schema)
    }
  }

  def scodecThenScodec[A: Eq](value: A, schema: Schema) = {
    val encoded = forWriterSchema(schema).encode(value).fold(err => fail(err.toString), identity)
    val result = forWriterSchema(schema).decode(encoded).fold(err => fail(err.toString), identity)

    
    assert(Eq[A].eqv(result.value.asInstanceOf[A], value))
    assert(result.remainder.isEmpty)
  }

  def scodecThenJava[A: Eq](value: A, schema: Schema): Assertion = {
    val encoded = forWriterSchema(schema).encode(value).fold(err => fail(err.toString), identity)
    val bais = new ByteArrayInputStream(encoded.toByteArray)
    val decoder = DecoderFactory.get.binaryDecoder(bais, null)
    val result = new GenericDatumReader[Any](schema).read(null, decoder)

    assert(Eq[A].eqv(result.asInstanceOf[A], value))
  }

  def javaThenScodec[A: Eq](value: A, schema: Schema) = {
    val baos = new ByteArrayOutputStream
    val encoder = EncoderFactory.get.binaryEncoder(baos, null)
    new GenericDatumWriter[Any](schema).write(value, encoder)
    encoder.flush()
    val encoded = baos.toByteArray

    val result = forWriterSchema(schema).decode(BitVector(encoded)).getOrElse(fail())

    assert(Eq[A].eqv(result.value.asInstanceOf[A], value))
    assert(result.remainder.isEmpty)
  }
}