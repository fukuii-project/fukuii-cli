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

  property("decode rejects trailing bytes after a complete item") {
    forAll(RlpVectors.valid) { (name: String, _: RlpItem, encodedHex: String) =>
      assert(Rlp.decode(hex(encodedHex + "00")).isLeft, name + " with a trailing byte must not decode")
    }
  }
