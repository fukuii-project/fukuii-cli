package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash, Hex, UInt256, UInt64}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** The seal a consensus engine writes into elements 13 and 14.
  *
  * **Every expectation here is constructed, and it has to be.** The conformance
  * corpora are Ethereum's, so every header in them carries a proof-of-work
  * seal; no fixture set on this machine publishes an authority-round header.
  * The rows are written to say what the two clients that implement that engine
  * do — one decodes the alternative branch when the first element's size is not
  * 32, the other writes the step and signature in place of the pair — rather
  * than what this implementation happens to produce.
  *
  * The proof-of-work direction is certified against real octets in
  * [[BlockHeaderPropSpec]] and is not restated here.
  */
class SealSpec extends AnyFlatSpec:

  private def hash(b: Byte): Hash = Hash.fromBytesTruncating(IArray.fill(32)(b))

  private val proofOfWork = Seal.Ethash(hash(7), BlockNonce.Zero)

  /** A 65-byte signature, which is the width both implementing clients reserve
    * for a sealer's signature.
    */
  private val authorityRound =
    Seal.AuthorityRound(UInt64.fromLong(42).toOption.get, Bytes.fromIArray(IArray.fill(65)(9.toByte)))

  private val header = BlockHeader(
    parentHash = hash(1),
    ommersHash = hash(2),
    beneficiary = Address.fromBytesTruncating(IArray.fill(20)(3.toByte)),
    stateRoot = hash(4),
    transactionsRoot = hash(5),
    receiptsRoot = hash(6),
    logsBloom = Bloom.Empty,
    difficulty = UInt256.fromLong(1).toOption.get,
    number = UInt64.fromLong(1).toOption.get,
    gasLimit = UInt64.fromLong(5000).toOption.get,
    gasUsed = UInt64.Zero,
    timestamp = UInt64.fromLong(1234).toOption.get,
    extraData = Bytes.Empty,
    seal = proofOfWork
  )

  private def sealOf(h: BlockHeader): Vector[RlpItem] =
    RlpCodec[BlockHeader].encode(h) match
      case RlpItem.Sequence(items) => items.slice(13, 15)
      case _: RlpItem.Bytes        => Vector.empty

  "an authority-round header" should "round-trip through the codec" in {
    val sealed_ = header.copy(seal = authorityRound)
    assert(
      RlpCodec.decodeFrom[BlockHeader](RlpCodec.encodeTo(sealed_)) == Right(sealed_),
      "a header sealed by the alternative engine must survive a round trip unchanged"
    )
  }

  it should "still occupy exactly two elements" in {
    assert(
      sealOf(header.copy(seal = authorityRound)).length == Seal.FieldCount,
      "the arity is the same under either engine, which is why nothing else moves"
    )
  }

  /** The discrimination, stated as the property that makes it exact rather than
    * heuristic: a mixed hash is fixed-width so its element is always 32 bytes,
    * and a step is a machine word so its element is at most 8. The ranges
    * cannot meet.
    */
  "the first seal element" should "be 32 bytes for proof of work and shorter otherwise" in {
    val powWidth = sealOf(header).head match
      case RlpItem.Bytes(payload) => payload.length
      case _: RlpItem.Sequence    => -1
    val auraWidth = sealOf(header.copy(seal = authorityRound)).head match
      case RlpItem.Bytes(payload) => payload.length
      case _: RlpItem.Sequence    => -1
    assert(
      powWidth == Hash.Width && auraWidth < Hash.Width,
      s"widths $powWidth and $auraWidth must not overlap, or the two seals collide"
    )
  }

  /** The largest step the type admits, which is the case that would break the
    * discrimination if the step were wider than a machine word.
    */
  "the widest possible step" should "still be narrower than a mixed hash" in {
    val widest = Seal.AuthorityRound(UInt64.MaxValue, Bytes.fromIArray(IArray.fill(65)(1.toByte)))
    val width = sealOf(header.copy(seal = widest)).head match
      case RlpItem.Bytes(payload) => payload.length
      case _: RlpItem.Sequence    => -1
    assert(
      width < Hash.Width,
      s"a step of $width bytes would be read as a mixed hash at 32"
    )
  }

  /** One client encodes a header before it is sealed, so an empty signature is
    * a shape rather than a defect, and it must not be confused with a
    * proof-of-work nonce — which is fixed at eight bytes and never empty.
    */
  "an unsealed authority-round header" should "round-trip with an empty signature" in {
    val unsealed = header.copy(seal = Seal.AuthorityRound(UInt64.Zero, Bytes.Empty))
    assert(
      RlpCodec.decodeFrom[BlockHeader](RlpCodec.encodeTo(unsealed)) == Right(unsealed),
      "a pre-seal header is a state one implementing client writes deliberately"
    )
  }

  /** The reason the seal is a sum rather than two nullable pairs. Under the
    * representation both implementing clients use, a header can carry stale
    * fields from the other branch and the hash depends on which branch is set
    * — one of them says so in its own decoder. Here the two seals are
    * different values of one field, so there is no second branch to leave
    * behind.
    */
  "two headers differing only in their seal" should "have different hashes" in {
    assert(
      header.hash != header.copy(seal = authorityRound).hash,
      "the seal is inside the hash preimage, so it cannot be invisible to it"
    )
  }

  "a seal whose first element is a list" should "be refused" in {
    val items = RlpCodec[BlockHeader].encode(header) match
      case RlpItem.Sequence(v) => v.updated(13, RlpItem.Sequence(Vector.empty))
      case _: RlpItem.Bytes    => Vector.empty
    assert(
      RlpCodec[BlockHeader].decode(RlpItem.Sequence(items)) == Left(RlpError.ExpectedBytes),
      "neither seal has a list in its first element, and the reason must name what was wanted"
    )
  }

  /** A proof-of-work nonce is fixed-width, so a short one is not a nonce that
    * needs padding. This is the arm that would silently accept a truncated
    * seal if the nonce were read as a scalar.
    */
  "a proof-of-work nonce that is not eight bytes" should "be refused" in {
    val items = RlpCodec[BlockHeader].encode(header) match
      case RlpItem.Sequence(v) => v.updated(14, RlpItem.Bytes(IArray.fill(7)(0.toByte)))
      case _: RlpItem.Bytes    => Vector.empty
    assert(
      RlpCodec[BlockHeader].decode(RlpItem.Sequence(items)).isLeft,
      "a seven-byte nonce is not a nonce"
    )
  }

  /** Stated so the encoding is readable from the test rather than only from
    * the implementation: the seal is spliced into the header's own element
    * list, never nested as one element.
    */
  "the seal" should "be spliced into the header rather than nested" in {
    val items = RlpCodec[BlockHeader].encode(header) match
      case RlpItem.Sequence(v) => v
      case _: RlpItem.Bytes    => Vector.empty
    assert(
      items.length == BlockHeader.MandatoryFields &&
        Hex.encode(items(13) match {
          case RlpItem.Bytes(p)    => p
          case _: RlpItem.Sequence => IArray.empty[Byte]
        }) == hash(7).toHex,
      "elements 13 and 14 are the header's own, so nesting them would be a header no client accepts"
    )
  }
