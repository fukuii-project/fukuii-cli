package org.fukuii.types

import org.fukuii.bytes.{Address, Hex, UInt64}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Withdrawal lists against the bytes a consensus client actually produced.
  *
  * Each expected encoding is sliced out of a real block's RLP —
  * `ethereum/tests, develop, c67e485ff8 (2025-06-04)`, the `BlockchainTests`
  * fixtures — at element 3, where a post-Shanghai block body carries its
  * withdrawals. The fields beside it are the same fixture's own decoded
  * `withdrawals` object.
  *
  * So the expectation is neither derived from this codec nor from the
  * specification: it is the octets that shipped inside a block. The extraction
  * re-encodes each fixture's decoded fields independently and asserts they
  * reproduce the slice before emitting a row, so a mis-sliced block fails to
  * extract rather than becoming a wrong expectation here.
  *
  * ==The last two rows are the ones that matter==
  *
  * `index` and `validatorIndex` are adjacent and the same type, so transposing
  * them produces a well-formed list that decodes into a different withdrawal.
  * Every row where both are zero is blind to that. The corpus carries exactly
  * one pair that is not — `(0, 2^64-1)` and `(2^64-1, 0)` — and their encodings
  * differ, so the two together pin the field order.
  *
  * They are also why the counters are `uint64` rather than a signed word: both
  * are above 2^63.
  */
class WithdrawalPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def word(decimal: String): UInt64 = UInt64.fromBigInt(BigInt(decimal)).toOption.get

  private def withdrawals(raw: Seq[(String, String, String, String)]): Seq[Withdrawal] =
    raw.map { (index, validatorIndex, address, amount) =>
      Withdrawal(word(index), word(validatorIndex), Address.fromHex(address).toOption.get, word(amount))
    }

  private val Recipient = "c94f5374fce5edbc8e2a8697c15331677e6ebf0b"
  private val MaxWord = "18446744073709551615"

  private def one(i: String, v: String, a: String, m: String): Seq[(String, String, String, String)] =
    Seq((i, v, a, m))

  private val lists = Table(
    ("fields", "encodedHex"),
    (one("0", "0", Recipient, "0"), "d9d8808094c94f5374fce5edbc8e2a8697c15331677e6ebf0b80"),
    (
      one("0", "0", "0000000000000000000000000000000000000000", "10000"),
      "dbda8080940000000000000000000000000000000000000000822710"
    ),
    (one("0", "0", Recipient, "10000"), "dbda808094c94f5374fce5edbc8e2a8697c15331677e6ebf0b822710"),
    (
      one("0", "0", "10fb6e0812178b547b4781f2a84456b36dc3ec05", "100000"),
      "dcdb80809410fb6e0812178b547b4781f2a84456b36dc3ec05830186a0"
    ),
    (
      one("0", "0", "2af4594dcbf603a12b3a16175de99e9b1cf7f1e3", "100000"),
      "dcdb8080942af4594dcbf603a12b3a16175de99e9b1cf7f1e3830186a0"
    ),
    (
      one("0", "0", "c000000000000000000000000000000000000001", "100000"),
      "dcdb808094c000000000000000000000000000000000000001830186a0"
    ),
    (
      one("0", "0", "c000000000000000000000000000000000000002", "100000"),
      "dcdb808094c000000000000000000000000000000000000002830186a0"
    ),
    (
      one("0", "0", "c000000000000000000000000000000000000003", "100000"),
      "dcdb808094c000000000000000000000000000000000000003830186a0"
    ),
    (
      one("0", MaxWord, Recipient, "10000"),
      "e3e28088ffffffffffffffff94c94f5374fce5edbc8e2a8697c15331677e6ebf0b822710"
    ),
    (
      one(MaxWord, "0", Recipient, "10000"),
      "e3e288ffffffffffffffff8094c94f5374fce5edbc8e2a8697c15331677e6ebf0b822710"
    )
  )

  property("a withdrawal list encodes to the octets that shipped in the block") {
    forAll(lists) { (fields: Seq[(String, String, String, String)], encodedHex: String) =>
      assert(Hex.encode(RlpCodec.encodeTo(withdrawals(fields))) == encodedHex, "must match the block's own bytes")
    }
  }

  property("the block's own bytes decode back to the same withdrawals") {
    forAll(lists) { (fields: Seq[(String, String, String, String)], encodedHex: String) =>
      val bytes = Hex.decode(encodedHex).toOption.get
      assert(RlpCodec.decodeFrom[Seq[Withdrawal]](bytes) == Right(withdrawals(fields)), "round trip must be exact")
    }
  }

  property("a list of the wrong arity is rejected rather than padded") {
    forAll(lists) { (fields: Seq[(String, String, String, String)], _: String) =>
      val short = RlpItem.Sequence(Vector(RlpCodec[UInt64].encode(withdrawals(fields).head.index)))
      assert(
        Withdrawal.withdrawalCodec.decode(short) == Left(RlpError.WrongArity(Withdrawal.FieldCount, 1)),
        "four fields or none"
      )
    }
  }

  /** The corpus pair, checked against each other rather than against a derived
    * expectation: the two differ only by which counter holds the large value, so
    * if the codec ignored field order they would encode identically.
    */
  property("transposing the two counters changes the encoding") {
    val forward = withdrawals(one("0", MaxWord, Recipient, "10000"))
    val reversed = withdrawals(one(MaxWord, "0", Recipient, "10000"))
    assert(
      Hex.encode(RlpCodec.encodeTo(forward)) != Hex.encode(RlpCodec.encodeTo(reversed)),
      "index and validatorIndex are not interchangeable"
    )
  }
