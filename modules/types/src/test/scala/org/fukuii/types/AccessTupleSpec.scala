package org.fukuii.types

import org.fukuii.bytes.{Address, Hash}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** What an access-list entry must refuse.
  *
  * **The accepting direction is already covered, and only the accepting
  * direction.** An access tuple is reached through a transaction, so every
  * type-1, type-2, type-3 and type-4 vector in `transaction-vectors.txt`
  * exercises this codec — and exercises it only where the bytes are well
  * formed. Nothing had ever read what it says when it refuses.
  *
  * That is the gap this file closes, and it is the shape this module has paid
  * for twice already: a codec reached through a parent type is exercised in one
  * direction by construction, so the question worth asking of it is not whether
  * it is tested but whether anything has read its rejecting branch.
  */
class AccessTupleSpec extends AnyFlatSpec:

  private def hash(b: Byte): Hash    = Hash.fromBytesTruncating(IArray.fill(32)(b))
  private val address: Address       = Address.fromBytesTruncating(IArray.fill(20)(3.toByte))
  private val tuple                  = AccessTuple(address, Seq(hash(1), hash(2)))

  private def itemsOf(item: RlpItem): Vector[RlpItem] = item match
    case RlpItem.Sequence(items) => items
    case _: RlpItem.Bytes        => Vector.empty

  private val encoded  = RlpCodec[AccessTuple].encode(tuple)
  private val tooLong  = RlpItem.Sequence(itemsOf(encoded) :+ RlpItem.Bytes(IArray.empty))
  private val tooShort = RlpItem.Sequence(itemsOf(encoded).dropRight(1))

  "an access tuple of three elements" should "be refused rather than truncated to two" in {
    assert(
      RlpCodec[AccessTuple].decode(tooLong) == Left(RlpError.WrongArity(AccessTuple.FieldCount, 3)),
      "EIP-2930 fixes the shape at an address and a key list, so a third element is malformed"
    )
  }

  "an access tuple of one element" should "be refused" in {
    assert(
      RlpCodec[AccessTuple].decode(tooShort) == Left(RlpError.WrongArity(AccessTuple.FieldCount, 1)),
      "two fields or none"
    )
  }

  "a byte string where an access tuple is expected" should "be refused" in {
    assert(
      RlpCodec[AccessTuple].decode(RlpItem.Bytes(IArray.empty)) == Left(RlpError.ExpectedSequence),
      "an access tuple is a list"
    )
  }

  "an access tuple whose address is the wrong width" should "be refused" in {
    val short = RlpItem.Sequence(itemsOf(encoded).updated(0, RlpItem.Bytes(IArray.fill(19)(1.toByte))))
    assert(
      RlpCodec[AccessTuple].decode(short) == Left(RlpError.WrongWidth(Address.Width, 19)),
      "an address is twenty bytes, and a shorter one is not a shorter address"
    )
  }

  /** The width rule EIP-2930 states as a width rather than a quantity, which is
    * the whole reason the keys are a fixed-width type: a scalar codec would
    * accept this by dropping the leading zeros, and the transaction's hash would
    * change with it.
    */
  "an access tuple whose storage key is the wrong width" should "be refused" in {
    val short = RlpItem.Sequence(
      itemsOf(encoded).updated(1, RlpItem.Sequence(Vector(RlpItem.Bytes(IArray.fill(31)(1.toByte)))))
    )
    assert(
      RlpCodec[AccessTuple].decode(short) == Left(RlpError.WrongWidth(Hash.Width, 31)),
      "a storage key is a 32-byte word, not a quantity that may be shortened"
    )
  }

  "an access tuple touching no slots" should "round-trip with an empty key list" in {
    val empty = AccessTuple(address, Seq.empty)
    assert(
      RlpCodec.decodeFrom[AccessTuple](RlpCodec.encodeTo(empty)) == Right(empty),
      "an entry listing no slots is a real entry, and its arity is still two"
    )
  }
