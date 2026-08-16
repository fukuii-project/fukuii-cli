package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash, Hex, UInt256, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** The header shapes the conformance corpora do not contain.
  *
  * [[BlockHeaderPropSpec]] pins the encoding against real octets and is the
  * stronger evidence wherever it reaches. It cannot reach here: those corpora
  * hold headers of 15, 16, 20 and 21 elements and nothing else, so three
  * boundaries that decide whether a real header is accepted have no fixture at
  * all — the two intermediate tail lengths, and a tail longer than this type
  * models.
  *
  * **These expectations are therefore constructed, and that is a weaker kind of
  * evidence than a published byte string.** They are written to say what the
  * specifications say rather than what the implementation does: a tail grows by
  * whole proposals, EIP-4844 contributes two fields at once, and a decoder must
  * not refuse a field set a later proposal adds.
  *
  * Every fixture is declared before the first test registration. Interleaving
  * them leaves the class partly initialized where a test is registered, which
  * the compiler's initialization checker rejects outright.
  */
class BlockHeaderSpec extends AnyFlatSpec:

  private def hash(b: Byte): Hash      = Hash.fromBytesTruncating(IArray.fill(32)(b))
  private def word(n: Long): UInt64    = UInt64.fromBigInt(BigInt(n)).toOption.get
  private def big(n: Long): UInt256    = UInt256.fromBigInt(BigInt(n)).toOption.get
  private def elements(h: BlockHeader) = RlpCodec[BlockHeader].encode(h)

  private def itemsOf(item: RlpItem): Vector[RlpItem] = item match
    case RlpItem.Sequence(items) => items
    case _: RlpItem.Bytes        => Vector.empty

  private val base = BlockHeader(
    parentHash = hash(1),
    ommersHash = hash(2),
    beneficiary = Address.fromBytesTruncating(IArray.fill(20)(3.toByte)),
    stateRoot = hash(4),
    transactionsRoot = hash(5),
    receiptsRoot = hash(6),
    logsBloom = Bloom.Empty,
    difficulty = UInt256.fromBigInt(BigInt(17179869184L)).toOption.get,
    number = UInt64.fromBigInt(BigInt(1)).toOption.get,
    gasLimit = UInt64.fromBigInt(BigInt(5000)).toOption.get,
    gasUsed = UInt64.Zero,
    timestamp = UInt64.fromBigInt(BigInt(1234)).toOption.get,
    extraData = Bytes.Empty,
    seal = Seal.Ethash(mixHash = hash(7), nonce = BlockNonce.Zero)
  )

  private val withWithdrawals = base.copy(tail = Some(BaseFeeTail(big(7), Some(WithdrawalsTail(hash(8))))))

  private val withBlobGas = base.copy(tail =
    Some(BaseFeeTail(big(7), Some(WithdrawalsTail(hash(8), Some(BlobGasTail(word(9), word(10)))))))
  )

  private val withRequests = base.copy(tail =
    Some(
      BaseFeeTail(
        big(7),
        Some(
          WithdrawalsTail(
            hash(8),
            Some(
              BlobGasTail(word(9), word(10), Some(BeaconRootTail(hash(11), Some(RequestsTail(hash(12))))))
            )
          )
        )
      )
    )
  )

  /** One element past everything this type models — the shape a proposal still
    * at `Review` would produce.
    */
  private val unmodeled = RlpItem.Bytes(IArray.fill(32)(13.toByte))
  private val extended   = RlpItem.Sequence(itemsOf(elements(withRequests)) :+ unmodeled)
  private val decodedExtended = RlpCodec[BlockHeader].decode(extended)

  private val truncatedBlobPair = RlpItem.Sequence(itemsOf(elements(withBlobGas)).dropRight(1))
  private val shortOfMandatory  = RlpItem.Sequence(itemsOf(elements(base)).take(14))

  // ── the tail's arithmetic: 0, 1, 2, 4, 5, 6, and never 3 ──

  "fieldCount" should "be 15 with no tail" in {
    assert(base.fieldCount == 15, "the mandatory fields alone")
  }

  it should "be 17 for the withdrawals tail, which the corpora never carry" in {
    assert(withWithdrawals.fieldCount == 17, "base fee plus withdrawals root")
  }

  it should "jump from 17 to 19, because the blob proposal adds two fields at once" in {
    assert(withBlobGas.fieldCount == 19, "no header has 18 fields")
  }

  "a 17-element header" should "decode rather than being refused as malformed" in {
    assert(
      RlpCodec.decodeFrom[BlockHeader](Rlp.encode(elements(withWithdrawals))) == Right(withWithdrawals),
      "17 is a real shape and refusing it refuses every post-withdrawals block before blobs"
    )
  }

  /** The corpora jump straight from sixteen elements to twenty, so this length
    * has no fixture anywhere — and a decoder that reads one field too far here
    * runs off the end of the list rather than returning a value. A mutation of
    * the stop condition survived the whole suite until this existed.
    */
  "a 19-element header" should "decode rather than being read past its end" in {
    assert(
      RlpCodec.decodeFrom[BlockHeader](Rlp.encode(elements(withBlobGas))) == Right(withBlobGas),
      "the blob-gas pair with no beacon root is a real shape, and an unfixtured one"
    )
  }

  "an 18-element header" should "be refused rather than read past its end" in {
    assert(
      RlpCodec[BlockHeader].decode(truncatedBlobPair) == Left(RlpError.WrongWidth(19, 18)),
      "one half of the blob-gas pair is a shape no proposal defines"
    )
  }

  "a header shorter than the mandatory fields" should "be refused" in {
    assert(
      RlpCodec[BlockHeader].decode(shortOfMandatory) == Left(RlpError.WrongWidth(15, 14)),
      "fifteen fields or nothing"
    )
  }

  "a byte string where a header is expected" should "be refused" in {
    assert(
      RlpCodec[BlockHeader].decode(RlpItem.Bytes(IArray.empty)) == Left(RlpError.ExpectedSequence),
      "a header is a list"
    )
  }

  // ── a tail longer than this type models ──

  "a tail longer than the modeled fields" should "decode rather than being refused" in {
    assert(decodedExtended.isRight, "out of scope is not the same as invalid")
  }

  it should "carry the unmodeled element verbatim" in {
    assert(
      decodedExtended.toOption.flatMap(_.unmodeledTail).map(_.items) == Some(Vector(unmodeled)),
      "an element that is not understood is still held"
    )
  }

  it should "re-encode to exactly the elements it was given" in {
    assert(
      decodedExtended.map(RlpCodec[BlockHeader].encode) == Right(extended),
      "dropping the element would be a different block hash on every block that carries one"
    )
  }

  it should "hash to keccak over those same bytes" in {
    assert(
      decodedExtended.map(_.hash) == Right(Keccak256.hash(Rlp.encode(extended))),
      "the block hash is taken over the header's own encoding, unmodeled elements included"
    )
  }

  // ── the two fixed-width fields a scalar codec would silently shorten ──

  "a zero nonce" should "encode as eight bytes rather than the empty string" in {
    assert(
      Hex.encode(Rlp.encode(RlpCodec[BlockNonce].encode(BlockNonce.Zero))) == "880000000000000000",
      "a scalar would make this 0x80 and change every block hash"
    )
  }

  "an empty bloom" should "encode as 256 bytes rather than the empty string" in {
    assert(
      Rlp.encode(RlpCodec[Bloom].encode(Bloom.Empty)).length == 259,
      "three bytes of length prefix and 256 of payload"
    )
  }
