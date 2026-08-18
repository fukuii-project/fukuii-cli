package org.fukuii.trie

import org.fukuii.bytes.Hex
import org.scalacheck.Gen
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Hex-prefix encoding against the four cases its definition distinguishes, and
  * the round trip over arbitrary paths.
  *
  * The rows are the whole of the encoding: a flag nibble carrying leaf-ness in
  * its second-lowest bit and parity in its lowest, with the first nibble sharing
  * the flag byte exactly when the count is odd. They are stated as expected
  * octets rather than as a re-derivation of the formula, so a transposed flag
  * bit fails here rather than being computed identically twice.
  */
class HexPrefixPropSpec extends AnyPropSpec with ScalaCheckPropertyChecks:

  private def nibbles(values: Seq[Int]): Nibbles = Nibbles.fromValues(values).toOption.get

  private val cases = Table(
    ("name", "path", "isLeaf", "expected"),
    ("empty extension", Seq.empty[Int], false, "00"),
    ("empty leaf", Seq.empty[Int], true, "20"),
    ("even extension", Seq(1, 2, 3, 4), false, "001234"),
    ("even leaf", Seq(1, 2, 3, 4), true, "201234"),
    ("odd extension", Seq(1, 2, 3), false, "1123"),
    ("odd leaf", Seq(1, 2, 3), true, "3123"),
    ("even extension, high nibbles", Seq(15, 0), false, "00f0"),
    ("single-nibble leaf", Seq(9), true, "39")
  )

  property("hex-prefix encoding places the flag and parity exactly where the definition does") {
    forAll(cases) { (name: String, path: Seq[Int], isLeaf: Boolean, expected: String) =>
      assert(Hex.encode(nibbles(path).toCompact(isLeaf)) == expected, name + " must encode to the stated octets")
    }
  }

  property("fromCompact inverts toCompact for the tabled cases") {
    forAll(cases) { (name: String, path: Seq[Int], isLeaf: Boolean, _: String) =>
      assert(
        Nibbles.fromCompact(nibbles(path).toCompact(isLeaf)) == Right((nibbles(path), isLeaf)),
        name + " must survive a round trip through the compact form"
      )
    }
  }

  private val anyPath: Gen[Seq[Int]] =
    Gen.listOfN(12, Gen.choose(0, 15)).flatMap(all => Gen.choose(0, 12).map(all.take))

  property("fromCompact inverts toCompact for an arbitrary path and flag") {
    forAll(anyPath, Gen.oneOf(true, false)) { (path: Seq[Int], isLeaf: Boolean) =>
      assert(
        Nibbles.fromCompact(nibbles(path).toCompact(isLeaf)) == Right((nibbles(path), isLeaf)),
        "every path must survive a round trip through the compact form"
      )
    }
  }

  property("fromCompact refuses an encoding with no bytes at all") {
    assert(Nibbles.fromCompact(IArray.empty[Byte]) == Left(TrieError.EmptyCompactPath), "the flag byte is mandatory")
  }

  property("fromCompact refuses a flag nibble with an unused high bit set") {
    assert(
      Nibbles.fromCompact(IArray(0x40.toByte)) == Left(TrieError.UnknownHexPrefixFlag(4)),
      "the two high bits of the flag nibble are unused and must be zero"
    )
  }

  property("fromCompact refuses a non-zero padding nibble on an even-length path") {
    assert(
      Nibbles.fromCompact(IArray(0x07.toByte)) == Left(TrieError.NonZeroPaddingNibble(7)),
      "an even-length path carries no nibble in the flag byte, so its low half must be zero"
    )
  }
