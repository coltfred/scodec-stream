package scodec.stream.decode

import org.scalacheck._
import Prop._
import scalaz.\/._
import scalaz.stream.Process
import scodec.bits.BitVector
import scodec.Decoder
import scodec.codecs._

object StreamDecoderTest extends Properties("StreamDecoder") {

  property("many/tryMany") = forAll { (ints: List[Int]) =>
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    once(int32).many.decode(bits).chunkAll.runLastOr(Vector()).run.toList == ints &&
    tryMany(int32).decode(bits).chunkAll.runLastOr(Vector()).run.toList == ints
  }

  property("tryMany-example") = secure {
    val bits = repeated(int32).encodeValid(Vector(1,2,3))
    tryMany(int32).decode(bits).runLog.run.toList == List(1,2,3)
  }

  property("many1") = forAll { (ints: List[Int]) =>
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    many1(int32).decode(bits).chunkAll.runLastOr(Vector()).attemptRun.fold(
      err => ints.isEmpty,
      vec => vec.toList == ints
    )
  }

  property("onComplete") = secure {
    val bits = repeated(int32).encodeValid(Vector(1,2,3))
    var cleanedUp = false
    val dec: StreamDecoder[Int] = many1(int32)
      .flatMap { _ => fail("oh noes!") }
      .onComplete { suspend { cleanedUp = true; halt }}
    cleanedUp == false &&
    dec.decode(bits).run.attemptRun.isLeft
  }

  property("isolate") = forAll { (ints: List[Int]) =>
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    val p =
      many(int32).isolate(bits.size).map(_ => 0) ++
      many(int32).isolate(bits.size).map(_ => 1)
    val res = p.decode(bits ++ bits).chunkAll.runLastOr(Vector()).run
    res == (Vector.fill(ints.size)(0) ++ Vector.fill(ints.size.toInt)(1))
  }

  property("or") = forAll { (ints: List[Int]) =>
    val bits = repeated(int32).encodeValid(ints.toIndexedSeq)
    val p1 = once(int32).many.or(halt)
    val p2 = halt.or(many(int32))
    val p3 = once(int32).many | once(int32).many
    val p4 = p3 or p1
    def fail(msg: String): Decoder[Nothing] = new Decoder[Nothing] {
      def decode(bits: BitVector) = left(msg)
    }
    val failing = tryOnce(uint8.flatMap { _ => fail("!!!") })
    // NB: this fails as expected - since `once` does not backtrack
    // val failing = once(uint8.flatMap { _ => fail("!!!") })
    val p5 = failing or p1
    val p6 = p2 or failing

    List(p1,p2,p3,p4,p5,p6).forall { p =>
      p.decode(bits).chunkAll.runLastOr(Vector()).run.toList == ints
    }
  }

  val string = variableSizeBytes(int32, utf8)

  property("sepBy") = forAll { (ints: List[Int], delim: String) =>
    import scodec.stream.encode
    val e = encode.once(int32) ++ encode.many(int32).mapBits(string.encodeValid(delim) ++ _)
    val encoded = e.encode(Process.emitAll(ints).toSource).runLog.run.foldLeft(BitVector.empty)(_ ++ _)
    many(int32).sepBy(string).decode(encoded).runLog.run.toList == ints
  }

  property("decodeResource") = forAll { (strings: List[String]) =>
    // make sure that cleanup action gets run
    import scodec.stream.encode
    val bits = repeated(string).encodeValid(strings.toIndexedSeq)
    var cleanedUp = 0
    val decoded = many(string).decodeResource(())(_ => bits, _ => cleanedUp += 1)
    cleanedUp == 0 && // make sure we don't bump this strictly
    decoded.runLog.run.toList == strings && // normal termination
    decoded.take(2).runLog.run.toList == strings.take(2) && // early termination
    { // exceptions
      val failed = decoded.take(3).map(_ => { sys.error("die"); "fail" }).runLog.attemptRun.isLeft
      strings.isEmpty || failed
    } &&
    cleanedUp == 3
  }

  // def intraFrame[H,A](header: StreamDecoder[H])(
  //                     frameSizeInBits: H => Long)(
  //                     collectFrames: Process1[(H,BitVector), A]): StreamDecoder[A]

  property("frame combinators") = secure {
    val strings = List("a")
    import scodec.stream.{encode => E}
    val frameSize = 16
    val bits = E.once(string).many.chunk(frameSize) // split into frames
                .mapBits(bits => int32.encodeValid(bits.size.toInt) ++ bits) // add header
                .encodeAllValid(strings)
    val reframe = prefixFramePayloads(tryOnce(int32))(identity).takeWhile(_.nonEmpty)
    val decoder: StreamDecoder[String] = (for {
      sizeBits <- reframe firstAfter { _.size < 32 } // prefetch until we have enough to decode an int
      size <- prepend(sizeBits) ++ once(int32)       // push back what we read and decode that int
      stringBits <- reframe firstAfter { _.size < size*8 } // read across frames until we have enough for full string
      s <- prepend(sizeBits ++ stringBits) ++ once(string) // now read the string, discarding the frame structure
    } yield s).many // and do this repeatedly until input is exhausted

    val result = decoder.decodeValidStrict(bits).toList
    result == strings
  }
}

