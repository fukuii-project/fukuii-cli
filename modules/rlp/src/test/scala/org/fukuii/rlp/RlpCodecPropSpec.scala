package org.fukuii.rlp

import org.fukuii.bytes.{Hex, UInt256, UInt64}
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** The scalar codec against published RLP, end to end.
  *
  * Every row is a fixture from `ethereum/tests, develop, c67e485ff8 (2025-06-04)`,
  * file `RLPTests/rlptest.json`, whose `in` is an integer. The third column is
  * the fixture's own `out` — the complete RLP encoding including its prefix,
  * not a payload this file derived.
  *
  * That is the difference between this table and `UInt256PropSpec`'s, which
  * checks the byte form of the value alone. Here the assertion is that going
  * from a value all the way to bytes produces exactly the octets the corpus
  * publishes, so a prefix error or an off-by-one in the length form is caught
  * rather than hidden behind a payload comparison.
  */
class RlpCodecPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val scalars = Table(
    ("name", "decimal", "rlpHex"),
    ("zero", "0", "80"),
    ("smallint", "1", "01"),
    ("smallint2", "16", "10"),
    ("smallint3", "79", "4f"),
    ("smallint4", "127", "7f"),
    ("mediumint1", "128", "8180"),
    ("mediumint2", "1000", "8203e8"),
    ("mediumint3", "100000", "830186a0"),
    ("mediumint4", "83729609699884896815286331701780722", "8f102030405060708090a0b0c0d0e0f2"),
    (
      "mediumint5",
      "105315505618206987246253880190783558935785933862974822347068935681",
      "9c0100020003000400050006000700080009000a000b000c000d000e01"
    )
  )

  property("a word encodes to exactly the octets the corpus publishes") {
    forAll(scalars) { (name: String, decimal: String, rlpHex: String) =>
      val value = UInt256.fromBigInt(BigInt(decimal)).toOption.get
      assert(Hex.encode(RlpCodec.encodeTo(value)) == rlpHex, name + " must produce the published encoding")
    }
  }

  property("the published octets decode back to the same word") {
    forAll(scalars) { (name: String, decimal: String, rlpHex: String) =>
      val bytes = Hex.decode(rlpHex).toOption.get
      assert(
        RlpCodec.decodeFrom[UInt256](bytes).map(_.toBigInt) == Right(BigInt(decimal)),
        name + " must survive the round trip through bytes"
      )
    }
  }

  /** The machine word follows the same scalar rule, so every fixture that fits
    * one must encode identically through both codecs. A divergence would mean
    * one of them is not the scalar rule.
    */
  property("the machine word agrees with the corpus wherever it can hold the value") {
    forAll(scalars) { (name: String, decimal: String, rlpHex: String) =>
      val n = BigInt(decimal)
      assert(
        n >= BigInt(2).pow(64) || Hex.encode(RlpCodec.encodeTo(UInt64.fromBigInt(n).toOption.get)) == rlpHex,
        name + " must agree with the published encoding when it fits"
      )
    }
  }

  /** Prefixing a canonical scalar with a zero byte produces well-formed RLP
    * carrying a non-canonical quantity: the structure is valid, so `Rlp.decode`
    * accepts it, and only the codec can reject it.
    */
  property("a scalar with a leading zero byte is rejected by the codec") {
    forAll(scalars) { (name: String, decimal: String, _: String) =>
      val payload = UInt256.fromBigInt(BigInt(decimal)).toOption.get.toMinimalBytes
      val padded = IArray(0.toByte) ++ payload
      val wellFormed = Rlp.encode(RlpItem.Bytes(padded))
      assert(
        RlpCodec.decodeFrom[UInt256](wellFormed) == Left(RlpError.NonCanonicalScalar),
        name + " must be rejected in its padded spelling"
      )
    }
  }
