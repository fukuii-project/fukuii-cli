package org.fukuii.rlp

import org.fukuii.bytes.Hex
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Certification against the Ethereum conformance corpus.
  *
  * Vectors are [[RlpVectors]], generated from the `RLPTests` fixtures of
  * ethereum/tests @ `c67e485ff8b5be9abc8ad15345ec21aa22e290d9`. The encoding
  * rules they check are the Yellow Paper's Appendix B, `Paper.tex`
  * @ `efc5f9a1f356cba376c978eedb63cb0363c2aa85`.
  *
  * Encoding and decoding are asserted SEPARATELY, against the corpus, rather
  * than as a round trip through each other. A codec that round-trips itself
  * perfectly can be wrong in both directions at once, and for a consensus
  * serialization that error is invisible until two clients disagree.
  */
class RlpSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def hex(s: String): IArray[Byte] = Hex.decode(s).toOption.get

  property("encode produces the corpus encoding for every valid vector") {
    forAll(RlpVectors.valid) { (name: String, item: RlpItem, encodedHex: String) =>
      assert(Hex.encode(Rlp.encode(item)) == encodedHex, name)
    }
  }

  property("decode recovers the corpus item for every valid vector") {
    forAll(RlpVectors.valid) { (name: String, item: RlpItem, encodedHex: String) =>
      assert(Rlp.decode(hex(encodedHex)) == Right(item), name)
    }
  }

  property("decode rejects every invalid vector") {
    forAll(RlpVectors.invalid) { (name: String, encodedHex: String) =>
      assert(Rlp.decode(hex(encodedHex)).isLeft, name + " must not decode")
    }
  }

  /** Nesting deeper than the bound is rejected, not followed.
    *
    * One level costs a single byte to encode and several JVM stack frames to
    * decode, so an unbounded decoder is a denial of service on a payload a peer
    * can send in a few kilobytes — and a `StackOverflowError` is not catchable
    * as a decode failure. `0xc1` is a one-item sequence, so repeating it nests.
    */
  private def nested(depth: Int): IArray[Byte] =
    // Each level wraps the one inside it, so the payload grows as you go out
    // and the prefix widens with it. A fixed `0xc1` per level is NOT valid
    // nesting -- it declares a one-byte payload at every level, which is true
    // only of the innermost, and the decoder rejects it as malformed long
    // before any depth bound is reached.
    def prefixFor(len: Int): Array[Byte] =
      if len < 56 then Array((0xc0 + len).toByte)
      else
        val be = BigInt(len).toByteArray.dropWhile(_ == 0.toByte)
        Array((0xf7 + be.length).toByte) ++ be

    @annotation.tailrec
    def build(remaining: Int, len: Int, acc: List[Array[Byte]]): List[Array[Byte]] =
      if remaining == 0 then acc
      else
        val p = prefixFor(len)
        build(remaining - 1, len + p.length, p :: acc)

    IArray.unsafeFromArray(build(depth, 1, Nil).toArray.flatten :+ 0x00.toByte)

  property("decode rejects nesting past the bound instead of overflowing the stack") {
    val tooDeep = nested(Rlp.MaxNestingDepth + 50)
    assert(Rlp.decode(tooDeep) == Left(RlpError.NestingTooDeep(Rlp.MaxNestingDepth)), "must reject, not recurse")
  }

  property("decode still accepts nesting just inside the bound") {
    assert(Rlp.decode(nested(Rlp.MaxNestingDepth - 2)).isRight, "the bound must not reject legitimate nesting")
  }

  property("decode rejects trailing bytes after a complete item") {
    forAll(RlpVectors.valid) { (name: String, _: RlpItem, encodedHex: String) =>
      assert(Rlp.decode(hex(encodedHex + "00")).isLeft, name + " with a trailing byte must not decode")
    }
  }
