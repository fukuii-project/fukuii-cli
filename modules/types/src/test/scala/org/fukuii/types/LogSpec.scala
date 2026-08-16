package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** What a log encoding must refuse.
  *
  * [[LogPropSpec]] pins what a valid log encodes to. This pins the boundary,
  * and the arity direction is the same as an account's rather than a header's:
  * **a log has encoded as three elements at every fork**, so both a shorter and
  * a longer list are malformed, where a header must tolerate a tail it does not
  * model.
  *
  * The topic *count* is the opposite case and is deliberately not refused here
  * — see [[LogPropSpec]]'s row for a log carrying five.
  */
class LogSpec extends AnyFlatSpec:

  private def hash(b: Byte): Hash = Hash.fromBytesTruncating(IArray.fill(32)(b))

  private val log = Log(
    address = Address.fromBytesTruncating(IArray.fill(20)(1)),
    topics = Seq(hash(2)),
    data = Bytes.fromIArray(IArray(3.toByte))
  )

  private def itemsOf(item: RlpItem): Vector[RlpItem] = item match
    case RlpItem.Sequence(items) => items
    case _: RlpItem.Bytes        => Vector.empty

  private val encoded = RlpCodec[Log].encode(log)

  "a log of four elements" should "be refused rather than truncated to three" in {
    val tooLong = RlpItem.Sequence(itemsOf(encoded) :+ RlpItem.Bytes(IArray.empty))
    assert(
      RlpCodec[Log].decode(tooLong) == Left(RlpError.WrongArity(Log.FieldCount, 4)),
      "the log encoding has never grown, so a fourth element is malformed"
    )
  }

  it should "be refused when an element is missing" in {
    val tooShort = RlpItem.Sequence(itemsOf(encoded).dropRight(1))
    assert(
      RlpCodec[Log].decode(tooShort) == Left(RlpError.WrongArity(Log.FieldCount, 2)),
      "three fields or none"
    )
  }

  "a byte string where a log is expected" should "be refused" in
    assert(
      RlpCodec[Log].decode(RlpItem.Bytes(IArray.empty)) == Left(RlpError.ExpectedSequence),
      "a log is a list"
    )

  "an address that is not twenty bytes" should "be refused rather than padded" in {
    val short = RlpItem.Sequence(itemsOf(encoded).updated(0, RlpItem.Bytes(IArray.fill(19)(1))))
    assert(
      RlpCodec[Log].decode(short) == Left(RlpError.WrongWidth(Address.Width, 19)),
      "an address is fixed-width, so a short one is a different address and not this one"
    )
  }

  "a topic that is not thirty-two bytes" should "be refused" in {
    val short = RlpItem.Sequence(
      itemsOf(encoded).updated(1, RlpItem.Sequence(Vector(RlpItem.Bytes(IArray.fill(31)(2)))))
    )
    assert(
      RlpCodec[Log].decode(short) == Left(RlpError.WrongWidth(Hash.Width, 31)),
      "a topic is a 32-byte word, and its leading zeros are part of it"
    )
  }

  "topics given as a byte string" should "be refused rather than read as one topic" in {
    val flattened = RlpItem.Sequence(itemsOf(encoded).updated(1, RlpItem.Bytes(IArray.fill(32)(2))))
    assert(
      RlpCodec[Log].decode(flattened) == Left(RlpError.ExpectedSequence),
      "the topics are a nested list, so a log with one topic is not a log with a 32-byte field"
    )
  }

  /** The mirror of the case above, and the one that was reported wrongly: a
    * list where the data belongs is refused, and it must say the item should
    * have been a byte string. Reporting `ExpectedSequence` there names the
    * thing it was handed rather than the thing it wanted.
    */
  "data given as a list" should "be refused as not being a byte string" in {
    val listed = RlpItem.Sequence(itemsOf(encoded).updated(2, RlpItem.Sequence(Vector.empty)))
    assert(
      RlpCodec[Log].decode(listed) == Left(RlpError.ExpectedBytes),
      "the data is a byte string, and the reason must name what was expected"
    )
  }

  /** Every other case here builds an item tree directly. This one starts from
    * octets, so it covers the composition a real caller uses — a structural RLP
    * failure has to reach the caller through the same channel a typed one does.
    */
  "a log whose octets are truncated" should "fail as RLP before the codec sees it" in {
    val bytes = Rlp.encode(encoded)
    assert(
      RlpCodec.decodeFrom[Log](bytes.dropRight(1)).isLeft,
      "a short read must not decode into a shorter log"
    )
  }
