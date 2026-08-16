package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** What a receipt encoding must refuse.
  *
  * [[ReceiptPropSpec]] pins what a valid receipt encodes to. This pins the
  * boundary, and the arity direction is an account's rather than a header's:
  * **a receipt has encoded as four elements at every fork and for every
  * type**, so a shorter and a longer list are both malformed.
  *
  * The first field's rejections are the ones nothing else reaches. The corpus
  * publishes only the three well-formed shapes — a 32-byte root, `0x01`, and
  * the empty string — so every way of getting that field wrong is pinned here
  * by construction or not at all.
  */
class ReceiptSpec extends AnyFlatSpec:

  private val log = Log(
    address = Address.fromBytesTruncating(IArray.fill(20)(1)),
    topics = Seq(Hash.fromBytesTruncating(IArray.fill(32)(2))),
    data = Bytes.fromIArray(IArray(3.toByte))
  )

  private val legacy = Receipt.withDerivedBloom(
    TransactionType.Legacy,
    PostStateOrStatus.Successful,
    UInt64.fromLong(21000).toOption.get,
    Seq(log)
  )

  private val typed = legacy.copy(transactionType = TransactionType.DynamicFee)

  private def fields: Vector[RlpItem] = RlpCodec[Receipt].encode(legacy) match
    case RlpItem.Sequence(items) => items
    case _: RlpItem.Bytes        => Vector.empty

  private def withFields(items: Vector[RlpItem]): Either[RlpError, Receipt] =
    RlpCodec[Receipt].decode(RlpItem.Sequence(items))

  private def withOutcome(item: RlpItem): Either[RlpError, Receipt] =
    withFields(fields.updated(0, item))

  "a receipt of five elements" should "be refused rather than truncated to four" in {
    assert(
      withFields(fields :+ RlpItem.Bytes(IArray.empty)) ==
        Left(RlpError.WrongWidth(Receipt.FieldCount, 5)),
      "the receipt payload has never grown, so a fifth element is malformed"
    )
  }

  it should "be refused when an element is missing" in {
    assert(
      withFields(fields.dropRight(1)) == Left(RlpError.WrongWidth(Receipt.FieldCount, 3)),
      "four fields or none"
    )
  }

  /** The status form and the post-state form differ only in the width of one
    * element, so a width between them is the case a decoder is most likely to
    * accept by accident.
    */
  "a first field of two bytes" should "be neither a status nor a root" in {
    assert(
      withOutcome(RlpItem.Bytes(IArray.fill(2)(1))) ==
        Left(RlpError.WrongWidth(Hash.Width, 2)),
      "the first field is a 32-byte root or a one-byte status, and two bytes is neither"
    )
  }

  it should "be refused at thirty-one bytes, one short of a root" in {
    assert(
      withOutcome(RlpItem.Bytes(IArray.fill(31)(1))) ==
        Left(RlpError.WrongWidth(Hash.Width, 31)),
      "a root is fixed-width, so a short one is not a root that needs padding"
    )
  }

  /** EIP-658 names exactly two status codes. The Yellow Paper asserts only
    * that the status is a non-negative integer and one reference client is
    * correspondingly loose, so this pins the stricter reading deliberately
    * rather than by omission.
    */
  "a status of two" should "be refused rather than carried" in {
    assert(
      withOutcome(RlpItem.Bytes(IArray(2.toByte))) ==
        Left(RlpError.UnknownDiscriminant(2)),
      "the proposal that defines the field names 0 and 1, and nothing else"
    )
  }

  /** Failure is the empty string, because the status is a scalar and a scalar
    * zero has no leading byte. A literal `0x00` is that value spelled the one
    * way the canonicality rule forbids, and reporting it as an unknown status
    * would name the wrong defect.
    */
  "a status written as a literal zero byte" should "be refused as non-canonical" in {
    assert(
      withOutcome(RlpItem.Bytes(IArray(0.toByte))) == Left(RlpError.NonCanonicalScalar),
      "a zero status is the empty string, so 0x00 is a second encoding of it"
    )
  }

  "a first field given as a list" should "be refused as not being a byte string" in {
    assert(
      withOutcome(RlpItem.Sequence(Vector.empty)) == Left(RlpError.ExpectedBytes),
      "the reason must name what was wanted rather than what arrived"
    )
  }

  "a bloom that is not 256 bytes" should "be refused rather than padded" in {
    assert(
      withFields(fields.updated(2, RlpItem.Bytes(IArray.fill(255)(0)))) ==
        Left(RlpError.WrongWidth(Bloom.Width, 255)),
      "the bloom is fixed-width and its leading zeros are part of it"
    )
  }

  /** The legacy shape predates the envelope and carries no tag, so a leading
    * `0x00` is not "the untyped one written explicitly" — it is malformed.
    * Both reference clients refuse it, and accepting it would give one receipt
    * two encodings.
    */
  "a receipt tagged 0x00" should "be refused rather than read as legacy" in {
    val body = Rlp.encode(RlpCodec[Receipt].encode(legacy))
    assert(
      Receipt.fromCanonicalBytes(IArray(0.toByte) ++ body) ==
        Left(RlpError.UnknownDiscriminant(0)),
      "a legacy receipt is a bare list, never a zero-tagged envelope"
    )
  }

  "a receipt tagged with an unassigned type" should "be refused" in {
    val body = Rlp.encode(RlpCodec[Receipt].encode(legacy))
    assert(
      Receipt.fromCanonicalBytes(IArray(5.toByte) ++ body) ==
        Left(RlpError.UnknownDiscriminant(5)),
      "no proposal assigns 0x05, so its payload shape is unknown"
    )
  }

  "an empty input" should "be refused before a tag is read" in {
    assert(
      Receipt.fromCanonicalBytes(IArray.empty[Byte]) == Left(RlpError.EmptyInput),
      "there is no byte to read a tag from"
    )
  }

  /** The trie stores the canonical bytes unwrapped and a list holds them
    * wrapped in a string, so the two forms must not be interchangeable. A
    * typed receipt's canonical bytes fed to the codec would be read as a tag
    * followed by a payload only if the codec accepted raw octets, which it
    * does not.
    */
  "a typed receipt" should "encode to a string whose payload is its canonical form" in {
    val encoded = RlpCodec[Receipt].encode(typed)
    assert(
      encoded == RlpItem.Bytes(Receipt.canonicalBytes(typed)),
      "a typed receipt is a byte string wherever it is a list element"
    )
  }

  it should "round-trip through that string form" in {
    assert(
      RlpCodec.decodeFrom[Receipt](RlpCodec.encodeTo(typed)) == Right(typed),
      "the wrapped form must decode back to the same receipt"
    )
  }

  /** Every other case here builds an item tree directly. This one starts from
    * octets, so a structural RLP failure has to reach the caller through the
    * same channel a typed one does.
    */
  "a receipt whose octets are truncated" should "fail as RLP before the codec sees it" in {
    assert(
      RlpCodec.decodeFrom[Receipt](RlpCodec.encodeTo(legacy).dropRight(1)).isLeft,
      "a short read must not decode into a smaller receipt"
    )
  }
