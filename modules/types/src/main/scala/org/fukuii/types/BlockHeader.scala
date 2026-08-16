package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** A block header: fifteen mandatory fields, then a tail that has grown once
  * per proposal that needed a new commitment.
  *
  * ==The tail is a chain because a gap in it has no encoding==
  *
  * The tail is positional, so a field is identified by how many elements
  * precede it. A representation of six independent optionals therefore admits
  * sixty-four states where the encoding defines seven, and the extra
  * fifty-seven are values with no canonical bytes — a header carrying a
  * requests hash but no base fee cannot be written down.
  *
  * The two reference implementations both encode such a header anyway, and
  * **they disagree about how**, which is the strongest available argument for
  * making it unrepresentable rather than picking a rule:
  *
  *   - one writes an empty string in the absent field's place and continues, so
  *     it emits bytes its own decoder rejects;
  *   - the other stops at the first absent field, silently discarding every
  *     later one — and for a header, a silently discarded field is a different
  *     block hash.
  *
  * Because the disagreement lies entirely inside the region a chain cannot
  * express, this design does not resolve it. It declines to construct the
  * input on which the two differ.
  *
  * So each link carries the fields its proposal introduced and the option of
  * the next link, and a prefix is the only shape there is.
  *
  * ==It tolerates a longer tail than it models==
  *
  * [[UnmodeledTail]] carries trailing elements this type has no field for,
  * verbatim. Two proposals that add header fields are at `Review` rather than
  * `Final`, so they are out of scope by the same rule everything else here is
  * scoped by — but out of scope is not the same as safe to reject, and a
  * decoder that rejected them would reject headers a network already produces.
  *
  * Carrying them verbatim rather than dropping them is what keeps [[hash]]
  * correct: the block hash is taken over the header's own encoding, so an
  * element that decodes and does not re-encode is a different hash on every
  * block that carries one.
  *
  * This is not a hypothetical allowance. The executable specification's newest
  * fork already defines a header of twenty-three elements — two more than
  * anything modelled here — so the tolerance is exercised by a fork that
  * exists, and the field order below was checked position by position against
  * that same definition.
  *
  * ==Where the integer widths depart from the executable specification==
  *
  * That specification types `number`, `gasLimit` and `gasUsed` as unbounded
  * integers and `timestamp` as 256-bit. This carries all four as the 64-bit
  * machine word instead, which is what the reference clients do and what the
  * wire has ever carried.
  *
  * **The departure cannot cause a divergence, which is the only reason it is
  * acceptable.** A header whose block number exceeded the 64-bit range would
  * fail to decode here and fail validation there, so it is rejected either way
  * and only the stage differs. No reachable chain approaches the bound: at one
  * block per second it is beyond 10^11 years away, and a gas limit is held far
  * below it by rules the layer above owns.
  *
  * ==The engine is pluggable, and [[Seal]] is the whole of what it changes==
  *
  * Every field here means the same thing under every consensus engine except
  * the two the engine fills with its proof, and those are [[Seal]]. So a
  * header is not proof-of-work-shaped with alternatives bolted on; it is
  * engine-neutral with one two-element hole, which is what lets a second
  * engine arrive without duplicating the fifteen fields or the tail chain.
  *
  * @param extraData
  *   consensus rules cap this at 32 bytes on the networks this client targets.
  *   That is a validity rule rather than an encoding one and is not enforced
  *   here — deciding what is valid at block N on network X is the layer above.
  */
final case class BlockHeader(
    parentHash: Hash,
    ommersHash: Hash,
    beneficiary: Address,
    stateRoot: Hash,
    transactionsRoot: Hash,
    receiptsRoot: Hash,
    logsBloom: Bloom,
    difficulty: UInt256,
    number: UInt64,
    gasLimit: UInt64,
    gasUsed: UInt64,
    timestamp: UInt64,
    extraData: Bytes,
    seal: Seal,
    tail: Option[BaseFeeTail] = None
):

  private def withdrawalsTail: Option[WithdrawalsTail] = tail.flatMap(_.next)
  private def blobGasTail: Option[BlobGasTail]         = withdrawalsTail.flatMap(_.next)
  private def beaconRootTail: Option[BeaconRootTail]   = blobGasTail.flatMap(_.next)
  private def requestsTail: Option[RequestsTail]       = beaconRootTail.flatMap(_.next)

  def baseFeePerGas: Option[UInt256]      = tail.map(_.baseFeePerGas)
  def withdrawalsRoot: Option[Hash]       = withdrawalsTail.map(_.withdrawalsRoot)
  def blobGasUsed: Option[UInt64]         = blobGasTail.map(_.blobGasUsed)
  def excessBlobGas: Option[UInt64]       = blobGasTail.map(_.excessBlobGas)
  def parentBeaconBlockRoot: Option[Hash] = beaconRootTail.map(_.parentBeaconBlockRoot)
  def requestsHash: Option[Hash]          = requestsTail.map(_.requestsHash)

  /** Trailing elements carried but not interpreted — see [[UnmodeledTail]].
    *
    * Public because a caller deciding whether it understands a header needs to
    * ask: a header with elements nobody here can name is one a later proposal
    * defines, and that is a fact about the header rather than an internal of
    * the chain.
    */
  def unmodeledTail: Option[UnmodeledTail] = requestsTail.flatMap(_.next)

  /** The number of RLP elements this header encodes to.
    *
    * Read from the chain rather than from a fork rule: which lengths a given
    * network accepts at a given height is the layer above's to decide.
    */
  def fieldCount: Int = BlockHeader.MandatoryFields + BaseFeeTail.lengthOf(tail)

  /** The block hash — `keccak(rlp(header))`, and the header's whole reason for
    * being byte-exact.
    *
    * A `lazy val` because it is asked for repeatedly, is a digest over a few
    * hundred bytes, and cannot change: every field is immutable and carries
    * structural equality, so the value is a function of the fields alone. It is
    * outside the constructor parameters, so it takes no part in `equals`,
    * `hashCode` or `copy`.
    */
  lazy val hash: Hash = Keccak256.hash(RlpCodec.encodeTo(this))

/** The tail introduced by the fee-market proposal, EIP-1559. */
final case class BaseFeeTail(baseFeePerGas: UInt256, next: Option[WithdrawalsTail] = None)

/** The tail introduced by the withdrawals proposal, EIP-4895. */
final case class WithdrawalsTail(withdrawalsRoot: Hash, next: Option[BlobGasTail] = None)

/** The two fields introduced together by the blob-transaction proposal,
  * EIP-4844.
  *
  * They are one link rather than two because the proposal defines them as a
  * pair — no header carries one without the other, and separating them would
  * reintroduce exactly the unencodable state this chain exists to exclude.
  */
final case class BlobGasTail(
    blobGasUsed: UInt64,
    excessBlobGas: UInt64,
    next: Option[BeaconRootTail] = None
)

/** The tail introduced by the beacon-root proposal, EIP-4788. */
final case class BeaconRootTail(parentBeaconBlockRoot: Hash, next: Option[RequestsTail] = None)

/** The tail introduced by the execution-requests proposal, EIP-7685. */
final case class RequestsTail(requestsHash: Hash, next: Option[UnmodeledTail] = None)

/** Trailing elements this type carries but does not interpret.
  *
  * ==Non-empty by construction, and that is a canonicality requirement==
  *
  * An empty one would encode to exactly the same bytes as its own absence, so
  * two distinct values would share an encoding and the codec would stop being
  * injective. Splitting the head off the rest makes the empty case
  * unrepresentable rather than merely rejected.
  */
final case class UnmodeledTail(head: RlpItem, rest: Vector[RlpItem] = Vector.empty):
  def items: Vector[RlpItem] = head +: rest

object BaseFeeTail:

  /** How many elements a tail chain contributes, counted by walking it. */
  def lengthOf(tail: Option[BaseFeeTail]): Int = tail match
    case None    => 0
    case Some(t) => 1 + lengthOfWithdrawals(t.next)

  private def lengthOfWithdrawals(tail: Option[WithdrawalsTail]): Int = tail match
    case None    => 0
    case Some(t) => 1 + lengthOfBlobGas(t.next)

  private def lengthOfBlobGas(tail: Option[BlobGasTail]): Int = tail match
    case None    => 0
    case Some(t) => 2 + lengthOfBeaconRoot(t.next)

  private def lengthOfBeaconRoot(tail: Option[BeaconRootTail]): Int = tail match
    case None    => 0
    case Some(t) => 1 + lengthOfRequests(t.next)

  private def lengthOfRequests(tail: Option[RequestsTail]): Int = tail match
    case None    => 0
    case Some(t) => 1 + t.next.map(_.items.length).getOrElse(0)

object BlockHeader:

  /** The fields every header has carried since the first block. */
  val MandatoryFields: Int = 15

  /** `[parentHash, ommersHash, beneficiary, stateRoot, transactionsRoot,
    * receiptsRoot, logsBloom, difficulty, number, gasLimit, gasUsed, timestamp,
    * extraData]`, then the [[Seal]]'s two elements, then the tail.
    *
    * The seal is spliced in rather than nested, for the reason [[Block]]
    * splices its body in: elements 13 and 14 are the header's own, and nesting
    * them as one element would be a well-formed header no client accepts.
    *
    * ==Two fields are fixed-width where a reader expects a scalar==
    *
    * A proof-of-work nonce is eight bytes and `logsBloom` is 256, leading zeros
    * included. A scalar drops those, so a zero nonce would encode as one byte
    * rather than nine and every block hash over it would be wrong. Both are
    * value types whose own codecs are fixed-width, so the mistake is not
    * available here.
    */
  given blockHeaderCodec: RlpCodec[BlockHeader] with

    def encode(value: BlockHeader): RlpItem =
      val head = Vector(
        RlpCodec[Hash].encode(value.parentHash),
        RlpCodec[Hash].encode(value.ommersHash),
        RlpCodec[Address].encode(value.beneficiary),
        RlpCodec[Hash].encode(value.stateRoot),
        RlpCodec[Hash].encode(value.transactionsRoot),
        RlpCodec[Hash].encode(value.receiptsRoot),
        RlpCodec[Bloom].encode(value.logsBloom),
        RlpCodec[UInt256].encode(value.difficulty),
        RlpCodec[UInt64].encode(value.number),
        RlpCodec[UInt64].encode(value.gasLimit),
        RlpCodec[UInt64].encode(value.gasUsed),
        RlpCodec[UInt64].encode(value.timestamp),
        RlpCodec[Bytes].encode(value.extraData)
      ) ++ Seal.fieldsOf(value.seal)
      RlpItem.Sequence(head ++ encodeTail(value.tail))

    private def encodeTail(tail: Option[BaseFeeTail]): Vector[RlpItem] = tail match
      case None => Vector.empty
      case Some(t) =>
        RlpCodec[UInt256].encode(t.baseFeePerGas) +: (t.next match
          case None => Vector.empty
          case Some(w) =>
            RlpCodec[Hash].encode(w.withdrawalsRoot) +: (w.next match
              case None => Vector.empty
              case Some(b) =>
                Vector(
                  RlpCodec[UInt64].encode(b.blobGasUsed),
                  RlpCodec[UInt64].encode(b.excessBlobGas)
                ) ++ (b.next match
                  case None => Vector.empty
                  case Some(r) =>
                    RlpCodec[Hash].encode(r.parentBeaconBlockRoot) +: (r.next match
                      case None    => Vector.empty
                      case Some(q) => RlpCodec[Hash].encode(q.requestsHash) +: q.next.map(_.items).getOrElse(Vector.empty)
                    )
                )
            )
        )

    /** Count-driven: the number of elements decides how much tail is present,
      * and anything past the last modeled field is carried rather than
      * refused.
      */
    def decode(item: RlpItem): Either[RlpError, BlockHeader] = item match
      case RlpItem.Bytes(_) => Left(RlpError.ExpectedSequence)
      case RlpItem.Sequence(items) =>
        if items.length < MandatoryFields then
          Left(RlpError.WrongWidth(MandatoryFields, items.length))
        else
          for
            parentHash       <- RlpCodec[Hash].decode(items(0))
            ommersHash       <- RlpCodec[Hash].decode(items(1))
            beneficiary      <- RlpCodec[Address].decode(items(2))
            stateRoot        <- RlpCodec[Hash].decode(items(3))
            transactionsRoot <- RlpCodec[Hash].decode(items(4))
            receiptsRoot     <- RlpCodec[Hash].decode(items(5))
            logsBloom        <- RlpCodec[Bloom].decode(items(6))
            difficulty       <- RlpCodec[UInt256].decode(items(7))
            number           <- RlpCodec[UInt64].decode(items(8))
            gasLimit         <- RlpCodec[UInt64].decode(items(9))
            gasUsed          <- RlpCodec[UInt64].decode(items(10))
            timestamp        <- RlpCodec[UInt64].decode(items(11))
            extraData        <- RlpCodec[Bytes].decode(items(12))
            seal             <- Seal.fromFields(items(13), items(14))
            tail             <- decodeTail(items)
          yield BlockHeader(
            parentHash,
            ommersHash,
            beneficiary,
            stateRoot,
            transactionsRoot,
            receiptsRoot,
            logsBloom,
            difficulty,
            number,
            gasLimit,
            gasUsed,
            timestamp,
            extraData,
            seal,
            tail
          )

    /** The tail's own arithmetic, kept in one place because an off-by-one in it
      * shifts every field after the error onto the wrong index.
      *
      * ==The one length that is not a prefix==
      *
      * Tail lengths run 0, 1, 2, 4, 5, 6 — the jump from 2 to 4 is EIP-4844
      * contributing two fields at once. So a header of eighteen elements
      * carries exactly one half of that pair, which no proposal defines, and it
      * is refused here rather than half-decoded.
      *
      * It has to be refused *first*, before any element is read: the field
      * after it is read by index, and at this length that index is past the
      * end. Rejecting late would raise where the contract is to return a value.
      *
      * **The two boundaries are certified differently, and only one of them by
      * a fixture.** Seventeen-element headers are published in quantity, and
      * `BlockPropSpec` reaches one through a block that carries it. Eighteen
      * appears only inside fixtures that expect the block to be REJECTED,
      * which corroborates this arithmetic rather than exercising it, so that
      * boundary is pinned by construction in `BlockHeaderSpec`.
      *
      * The header's own vector file reaches neither: its rows were drawn from
      * a corpus whose headers run 15, 16, 20 and 21, and a claim that no
      * corpus holds a seventeen-element header would now be false — the
      * absence was a property of one fixture set rather than of the field.
      */
    private def decodeTail(items: Vector[RlpItem]): Either[RlpError, Option[BaseFeeTail]] =
      val n = items.length
      if n == MandatoryFields then Right(None)
      else if n == MandatoryFields + 3 then Left(RlpError.WrongWidth(MandatoryFields + 4, n))
      else
        for
          baseFee <- RlpCodec[UInt256].decode(items(15))
          rest    <- decodeWithdrawals(items)
        yield Some(BaseFeeTail(baseFee, rest))

    private def decodeWithdrawals(items: Vector[RlpItem]): Either[RlpError, Option[WithdrawalsTail]] =
      if items.length == MandatoryFields + 1 then Right(None)
      else
        for
          root <- RlpCodec[Hash].decode(items(16))
          rest <- decodeBlobGas(items)
        yield Some(WithdrawalsTail(root, rest))

    private def decodeBlobGas(items: Vector[RlpItem]): Either[RlpError, Option[BlobGasTail]] =
      if items.length == MandatoryFields + 2 then Right(None)
      else
        for
          used   <- RlpCodec[UInt64].decode(items(17))
          excess <- RlpCodec[UInt64].decode(items(18))
          rest   <- decodeBeaconRoot(items)
        yield Some(BlobGasTail(used, excess, rest))

    private def decodeBeaconRoot(items: Vector[RlpItem]): Either[RlpError, Option[BeaconRootTail]] =
      if items.length == MandatoryFields + 4 then Right(None)
      else
        for
          root <- RlpCodec[Hash].decode(items(19))
          rest <- decodeRequests(items)
        yield Some(BeaconRootTail(root, rest))

    private def decodeRequests(items: Vector[RlpItem]): Either[RlpError, Option[RequestsTail]] =
      if items.length == MandatoryFields + 5 then Right(None)
      else
        RlpCodec[Hash]
          .decode(items(20))
          .map: requests =>
            val extra = items.drop(MandatoryFields + 6)
            Some(RequestsTail(requests, extra.headOption.map(UnmodeledTail(_, extra.drop(1)))))
