package org.fukuii.bytes

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Tabulated scalar vectors. Holds no fixed examples of its own; those live in
  * [[UInt256Spec]].
  *
  * Every row is a fixture from the conformance corpus's RLP tests —
  * `ethereum/tests, develop, c67e485ff8 (2025-06-04)`, file `RLPTests/rlptest.json` —
  * whose `in` is an integer. The corpus states each one's canonical RLP; the
  * minimal big-endian form below is that encoding's payload.
  *
  * The derivation is the Yellow Paper's scalar rule, RLP(i) = RLP(BE(i)) with
  * zero as the empty sequence, and it is self-checking rather than trusted: the
  * extraction re-derived each fixture's whole RLP encoding from the integer and
  * compared it against the fixture's own `out`, so a wrong big-endian form would
  * have failed to extract rather than becoming a wrong expectation here.
  *
  * `bigint` is the row that matters most and it is a REJECT case: it is exactly
  * 2^256, a perfectly valid RLP scalar whose minimal form is 33 bytes, and it is
  * the smallest value that is not a word. The corpus supplies the boundary, so
  * nothing here had to invent it.
  */
class UInt256PropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val scalars = Table(
    ("name", "decimal", "minimalHex"),
    ("zero", "0", ""),
    ("smallint", "1", "01"),
    ("smallint2", "16", "10"),
    ("smallint3", "79", "4f"),
    ("smallint4", "127", "7f"),
    ("mediumint1", "128", "80"),
    ("mediumint2", "1000", "03e8"),
    ("mediumint3", "100000", "0186a0"),
    ("mediumint4", "83729609699884896815286331701780722", "102030405060708090a0b0c0d0e0f2"),
    (
      "mediumint5",
      "105315505618206987246253880190783558935785933862974822347068935681",
      "0100020003000400050006000700080009000a000b000c000d000e01"
    )
  )

  /** 2^256, from the corpus's `bigint` fixture. */
  private val aboveMax = "115792089237316195423570985008687907853269984665640564039457584007913129639936"

  property("the minimal big-endian form is the corpus encoding's payload") {
    forAll(scalars) { (name: String, decimal: String, minimalHex: String) =>
      val value = UInt256.fromBigInt(BigInt(decimal)).toOption.get
      assert(Hex.encode(value.toMinimalBytes) == minimalHex, name + " must encode to its published payload")
    }
  }

  property("reading the minimal form back gives the same value") {
    forAll(scalars) { (name: String, decimal: String, minimalHex: String) =>
      val bytes = Hex.decode(minimalHex).toOption.get
      assert(UInt256.fromBytes(bytes).map(_.toBigInt) == Right(BigInt(decimal)), name + " must survive a round trip")
    }
  }

  property("the fixed-width form is always the full width") {
    forAll(scalars) { (name: String, decimal: String, _: String) =>
      val fixed = UInt256.fromBigInt(BigInt(decimal)).toOption.get.toBytes
      assert(fixed.length == UInt256.Width, name + " must occupy the full width whatever its magnitude")
    }
  }

  property("the fixed-width form ends in the minimal one") {
    forAll(scalars) { (name: String, decimal: String, minimalHex: String) =>
      val fixed = UInt256.fromBigInt(BigInt(decimal)).toOption.get.toBytes
      assert(Hex.encode(fixed).endsWith(minimalHex), name + " must be left-padded, so the value is unchanged")
    }
  }

  /** `fromBytes` reads a magnitude, so a leading zero is accepted and then
    * normalized away — the type has one representation per value.
    *
    * Rejecting a non-minimal scalar is a decoder's obligation rather than this
    * type's, and it belongs with the RLP codec: at that layer a leading zero is
    * a non-canonical encoding, not an alternative spelling.
    */
  property("a leading zero byte does not survive a re-encode") {
    forAll(scalars) { (name: String, _: String, minimalHex: String) =>
      val padded = Hex.decode("00" + minimalHex).toOption.get
      assert(
        UInt256.fromBytes(padded).map(_.toMinimalBytes).map(Hex.encode) == Right(minimalHex),
        name + " must read back through the minimal form"
      )
    }
  }

  property("2^256 is a valid RLP scalar and is not a word") {
    assert(UInt256.fromBigInt(BigInt(aboveMax)) == Left(BytesError.OutOfRange), "the corpus boundary must be rejected")
  }
