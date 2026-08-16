package org.fukuii.types

import org.fukuii.bytes.{Hash, UInt64}
import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}

/** What executing a transaction produced, as the receipts trie stores it.
  *
  * ==The envelope covers receipts, and that is easy to miss==
  *
  * EIP-2718 defines the tag for both sides: a `Receipt` is either
  * `TransactionType || ReceiptPayload` or a `LegacyReceipt`, which it gives
  * outright as `rlp([status, cumulativeGasUsed, logsBloom, logs])`. So a
  * receipt needs the same envelope machinery a transaction does, and carries
  * the same [[TransactionType]].
  *
  * ==Unlike a transaction, the tag and the field list ARE independent==
  *
  * Every receipt has the same four fields whatever its type — the wire
  * specification says so in as many words, "all receipts have the same encoding
  * regardless of transaction type". That is why the tag is a field here where
  * [[Transaction]] makes it a sum: there, five formats have five field lists
  * and a mismatched pair is not a transaction; here there is one field list, so
  * five identical cases would buy nothing and cost an exhaustive match.
  *
  * ==The first field is two things, and both are live==
  *
  * EIP-658 replaced the intermediate state root with a status code from
  * Byzantium onward, so a receipt from before that fork carries a 32-byte root
  * where a later one carries 0 or 1. **A decoder that models only the status
  * cannot read a pre-Byzantium block**, which for a client that syncs from
  * genesis is a fault rather than a limitation. [[PostStateOrStatus]] carries
  * both, and which one is correct at a given height is a fork rule the layer
  * above owns.
  *
  * That this is not hypothetical is measured: of the receipts the conformance
  * corpus publishes, 5162 carry a 32-byte first field, all of them under
  * Frontier, Homestead, Tangerine Whistle and Spurious Dragon, and all of them
  * untyped — which is what EIP-2718 postdating Byzantium implies.
  *
  * @param logsBloom
  *   the bloom over this receipt's own logs, stored rather than derived because
  *   the consensus encoding carries it and a receipt must re-encode to the
  *   octets it arrived as. It is nonetheless a function of [[logs]] alone —
  *   see [[Receipt.withDerivedBloom]], which is what a caller reading a wire
  *   format that omits the bloom needs.
  */
final case class Receipt(
    transactionType: TransactionType,
    postStateOrStatus: PostStateOrStatus,
    cumulativeGasUsed: UInt64,
    logsBloom: Bloom,
    logs: Seq[Log]
):

  /** The tag as it appears in the octets. */
  def typeNumber: Int = transactionType.number

/** A receipt's first field: the post-transaction state root, or the status code
  * that replaced it.
  *
  * The name is the one the wire specification and one reference client both
  * give the field — `post-state-or-status` — rather than a word chosen here.
  * Naming it after either half alone would say the other does not exist, and
  * naming it "receipt type" would collide with [[TransactionType]], which a
  * receipt also carries.
  *
  * ==The three forms cannot collide, which is what lets one field hold them==
  *
  * A root is exactly 32 bytes, success is the single byte `0x01`, and failure
  * is the empty string that a zero scalar takes. No two share an encoding, so
  * the union is injective and the codec stays round-trippable.
  */
enum PostStateOrStatus:

  /** The root of the world state after this transaction, as receipts carried
    * before EIP-658 replaced it.
    */
  case PostState(stateRoot: Hash)

  /** EIP-658's status code 0, "indicating failure (due to any operation that
    * can cause the transaction or top-level call to revert)".
    */
  case Failed

  /** EIP-658's status code 1. */
  case Successful

object PostStateOrStatus:

  /** Discriminated by width, which is what both reference clients do.
    *
    * A status of anything but 0 or 1 is refused rather than carried. The Yellow
    * Paper asserts only that the status is a non-negative integer, and one
    * reference client is correspondingly loose — it reads any single-byte
    * scalar as a status — but EIP-658 is the proposal that defines the field
    * and it names exactly two values. Admitting a third would also put a value
    * in this type that no network produces and that nothing above could act on.
    */
  given postStateOrStatusCodec: RlpCodec[PostStateOrStatus] with

    def encode(value: PostStateOrStatus): RlpItem = value match
      case PostState(stateRoot) => RlpItem.Bytes(stateRoot.toBytes)
      case Failed               => RlpItem.Bytes(IArray.empty[Byte])
      case Successful           => RlpItem.Bytes(IArray(1.toByte))

    def decode(item: RlpItem): Either[RlpError, PostStateOrStatus] = item match
      case RlpItem.Bytes(payload) =>
        payload.length match
          case 0 => Right(Failed)
          case 1 =>
            // A status of zero is the empty string, so a literal 0x00 byte is
            // that value spelled the one way the scalar rule forbids.
            if payload(0) == 1 then Right(Successful)
            else if payload(0) == 0 then Left(RlpError.NonCanonicalScalar)
            else Left(RlpError.UnknownDiscriminant(payload(0) & 0xff))
          // Anything wider than a status must be a root, and `fromBytes` is
          // what enforces the width — a `case Hash.Width` arm beside this one
          // would read as the check and carry none of it, since a wrong width
          // arrives here and leaves with the same error either way.
          case _ =>
            Hash
              .fromBytes(payload)
              .left
              .map(_ => RlpError.WrongWidth(Hash.Width, payload.length))
              .map(PostState.apply)
      case _: RlpItem.Sequence => Left(RlpError.ExpectedBytes)

object Receipt:

  /** The number of fields in the consensus payload, fixed. Every receipt has
    * encoded as four elements at every fork and for every type.
    */
  val FieldCount: Int = 4

  /** A receipt whose bloom is computed from its own logs.
    *
    * **This exists for the wire formats that omit the bloom.** From eth/69 a
    * receipt travels as `[tx-type, post-state-or-status, cumulative-gas, logs]`
    * with no bloom at all, and the specification states the consequence
    * directly: such receipts "need to be re-encoded into the format used by the
    * Ethereum consensus protocol, and their bloom filters have to be
    * recomputed". A peer that cannot recompute one cannot check the receipts
    * root, which is the only thing that makes a received receipt trustworthy.
    *
    * It is equally what a block producer needs, which is why it is not named
    * for the protocol version that first required it.
    */
  def withDerivedBloom(
      transactionType: TransactionType,
      postStateOrStatus: PostStateOrStatus,
      cumulativeGasUsed: UInt64,
      logs: Seq[Log]
  ): Receipt =
    Receipt(transactionType, postStateOrStatus, cumulativeGasUsed, Bloom.fromLogs(logs), logs)

  /** The form the receipts trie stores and a hash is taken over: `type ||
    * rlp(payload)` for a typed receipt, plain `rlp([...])` for a legacy one.
    *
    * ==This is NOT what the codec produces for a typed receipt==
    *
    * EIP-2718 makes the trie value these bytes exactly — the trie maps
    * `rlp(index)` to `Receipt`, and `Receipt` is the concatenation, not an RLP
    * item wrapping it. But wherever a receipt is an ELEMENT OF A LIST, as in
    * the wire protocol's per-block receipt lists, it must be an RLP item, so a
    * typed one appears there as a byte string wrapping these bytes.
    *
    * Putting the wrapped form in the trie, or the unwrapped form in a list, is
    * a wrong receipts root and therefore a wrong block hash. It is the same
    * distinction [[Transaction.canonicalBytes]] documents, and it is just as
    * easy to get backwards.
    */
  def canonicalBytes(receipt: Receipt): IArray[Byte] =
    val body = Rlp.encode(RlpItem.Sequence(payloadFields(receipt)))
    if receipt.transactionType == TransactionType.Legacy then body
    else IArray(receipt.typeNumber.toByte) ++ body

  /** Reads the form [[canonicalBytes]] produces. */
  def fromCanonicalBytes(bytes: IArray[Byte]): Either[RlpError, Receipt] =
    if bytes.isEmpty then Left(RlpError.EmptyInput)
    else
      val head = bytes(0) & 0xff
      if head > TransactionType.MaxTypeNumber then Rlp.decode(bytes).flatMap(decodePayload(TransactionType.Legacy, _))
      else typedTag(head).flatMap(tag => Rlp.decode(bytes.drop(1)).flatMap(decodePayload(tag, _)))

  /** `[postStateOrStatus, cumulativeGasUsed, logsBloom, logs]`, in that order.
    *
    * The type tag is not among them: a typed receipt carries it as a leading
    * byte outside the list, never as an element. The wire protocol from eth/69
    * does the opposite and makes it element zero of a four-element list — which
    * is a different encoding of the same values, and so a different type with
    * its own instance rather than a flag on this one.
    */
  private def payloadFields(receipt: Receipt): Vector[RlpItem] =
    Vector(
      RlpCodec[PostStateOrStatus].encode(receipt.postStateOrStatus),
      RlpCodec[UInt64].encode(receipt.cumulativeGasUsed),
      RlpCodec[Bloom].encode(receipt.logsBloom),
      RlpCodec[Seq[Log]].encode(receipt.logs)
    )

  /** A leading byte inside EIP-2718's range, as a format that has one.
    *
    * `0x00` is refused: the legacy shape predates the envelope and carries no
    * tag, so a receipt beginning with a zero byte is malformed rather than
    * untyped. Both reference clients refuse it the same way.
    */
  private def typedTag(number: Int): Either[RlpError, TransactionType] =
    TransactionType.fromNumber(number) match
      case Some(TransactionType.Legacy) | None => Left(RlpError.UnknownDiscriminant(number))
      case Some(tag)                           => Right(tag)

  private def decodePayload(
      transactionType: TransactionType,
      item: RlpItem
  ): Either[RlpError, Receipt] = item match
    case _: RlpItem.Bytes        => Left(RlpError.ExpectedSequence)
    case RlpItem.Sequence(items) =>
      if items.length != FieldCount then Left(RlpError.WrongArity(FieldCount, items.length))
      else
        for
          outcome <- RlpCodec[PostStateOrStatus].decode(items(0))
          gasUsed <- RlpCodec[UInt64].decode(items(1))
          bloom <- RlpCodec[Bloom].decode(items(2))
          logs <- RlpCodec[Seq[Log]].decode(items(3))
        yield Receipt(transactionType, outcome, gasUsed, bloom, logs)

  /** The form a receipt takes as an ELEMENT OF A LIST, which is the only
    * whole-value encoding of a receipt that round-trips.
    *
    * A legacy receipt is the list itself; a typed one is a byte string holding
    * [[canonicalBytes]]. The two cannot be confused on decode: an RLP list's
    * own first byte is at least `0xc0`, well above the `0x7f` EIP-2718 caps a
    * tag at.
    */
  given receiptCodec: RlpCodec[Receipt] with

    def encode(value: Receipt): RlpItem =
      if value.transactionType == TransactionType.Legacy then RlpItem.Sequence(payloadFields(value))
      else RlpItem.Bytes(canonicalBytes(value))

    def decode(item: RlpItem): Either[RlpError, Receipt] = item match
      case sequence: RlpItem.Sequence => decodePayload(TransactionType.Legacy, sequence)
      case RlpItem.Bytes(payload)     =>
        if payload.isEmpty then Left(RlpError.EmptyInput)
        else
          typedTag(payload(0) & 0xff).flatMap: tag =>
            Rlp.decode(payload.drop(1)).flatMap(decodePayload(tag, _))
