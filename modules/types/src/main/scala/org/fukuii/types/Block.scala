package org.fukuii.types

import org.fukuii.bytes.Hash
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** A header and the data it commits to.
  *
  * ==Two elements here, three or four on the wire==
  *
  * The encoding is flat — `[header, transactions, ommers, withdrawals?]` — and
  * this type nests the last three inside [[BlockBody]] rather than carrying
  * them alongside the header. The reason is that the body travels on its own:
  * the wire protocol has a message that asks for bodies by block hash and
  * answers with exactly `[transactions, ommers, withdrawals]`, so the grouping
  * is a real unit rather than a tidiness. Flattening it would mean two
  * near-identical encoders and a second place for the withdrawals rule to be
  * wrong.
  *
  * @param body
  *   the transactions, ommers and withdrawals, as one value because a peer
  *   requests them as one.
  */
final case class Block(header: BlockHeader, body: BlockBody):

  /** The block hash, which is the HEADER's hash and never a digest of this
    * whole encoding.
    *
    * Stated as a member rather than left to callers because the wrong reading
    * is available and looks right: a block is a value, it has an encoding, and
    * hashing that encoding produces a perfectly stable 32-byte number that no
    * other client will ever agree with. Every commitment a block makes to its
    * own contents already lives in the header, which is why hashing the header
    * alone is sufficient as well as correct.
    */
  def hash: Hash = header.hash

/** The part of a block a peer can request on its own: everything the header
  * commits to but does not contain.
  *
  * ==Withdrawals are an optional TRAILING element, so absent and empty differ==
  *
  * A body from before the withdrawals proposal has two elements; one after it
  * has three, and the third may legitimately be an empty list. Those are
  * different encodings of different facts — "this fork has no withdrawals" and
  * "this block had none" — so the field is an option over a sequence rather
  * than a sequence that happens to be empty. Collapsing them would give one
  * value two encodings and lose the distinction the arity is carrying.
  *
  * ==It does not tolerate a longer tail, and that is measured rather than
  * assumed==
  *
  * [[BlockHeader]] carries elements it cannot name, because header fields keep
  * being added and a header it refused would be a block it refused. The body
  * has not moved the same way: the executable specification's newest fork
  * defines the same three fields the withdrawals fork did, and the block-level
  * access lists the newest wire version adds are explicitly not part of the
  * body — they are fetched by their own message and committed to by a header
  * field. So a fourth element is malformed today, and admitting one would cost
  * the injectivity that [[BlockHeader.unmodeledTail]] had to work to keep.
  *
  * @param ommers
  *   the Yellow Paper's word, and the one the header's own `ommersHash` field
  *   already uses here. A network that has replaced proof of work cannot have
  *   any, which is a validity rule the layer above owns rather than something
  *   this type forbids.
  */
final case class BlockBody(
    transactions: Seq[Transaction],
    ommers: Seq[BlockHeader],
    withdrawals: Option[Seq[Withdrawal]] = None
)

object BlockBody:

  /** The fields every body has carried since the first block. */
  val MandatoryFields: Int = 2

  /** The fields a body carries once withdrawals exist. */
  val WithWithdrawalsFields: Int = 3

  /** The body's own elements.
    *
    * Visible inside this package because a block splices them in beside its
    * header rather than nesting them, so both encoders must agree about them
    * exactly. Two copies of this list is one place for the withdrawals rule to
    * be wrong.
    */
  private[types] def fieldsOf(value: BlockBody): Vector[RlpItem] =
    Vector(
      RlpCodec[Seq[Transaction]].encode(value.transactions),
      RlpCodec[Seq[BlockHeader]].encode(value.ommers)
    ) ++ value.withdrawals.map(RlpCodec[Seq[Withdrawal]].encode)

  /** Reads what [[fieldsOf]] writes, from a block's elements or a body's. */
  private[types] def fromFields(items: Vector[RlpItem]): Either[RlpError, BlockBody] =
    if items.length != MandatoryFields && items.length != WithWithdrawalsFields then
      Left(RlpError.WrongWidth(WithWithdrawalsFields, items.length))
    else
      for
        transactions <- RlpCodec[Seq[Transaction]].decode(items(0))
        ommers       <- RlpCodec[Seq[BlockHeader]].decode(items(1))
        withdrawals <-
          if items.length == MandatoryFields then Right(None)
          else RlpCodec[Seq[Withdrawal]].decode(items(2)).map(Some.apply)
      yield BlockBody(transactions, ommers, withdrawals)

  /** `[transactions, ommers]`, then the withdrawals list if the fork has one.
    *
    * Count-driven, like the header's tail and for the same reason: which arity
    * a network produces at a given height is a fork rule, and deciding it here
    * would put a consensus surface in a type that has no owner for one.
    */
  given blockBodyCodec: RlpCodec[BlockBody] with
    def encode(value: BlockBody): RlpItem = RlpItem.Sequence(fieldsOf(value))
    def decode(item: RlpItem): Either[RlpError, BlockBody] = item match
      case _: RlpItem.Bytes        => Left(RlpError.ExpectedSequence)
      case RlpItem.Sequence(items) => fromFields(items)

object Block:

  /** `[header, transactions, ommers]`, then the withdrawals list if the fork
    * has one.
    *
    * The body's own elements are spliced in rather than nested: a block is a
    * flat list of three or four elements, so encoding [[Block.body]] as one
    * element would produce a well-formed list that no client accepts and a
    * block hash that matches nothing.
    */
  /** A block is its header plus the body's elements, so its arity is the
    * body's plus one.
    */
  val MandatoryFields: Int       = BlockBody.MandatoryFields + 1
  val WithWithdrawalsFields: Int = BlockBody.WithWithdrawalsFields + 1

  given blockCodec: RlpCodec[Block] with

    def encode(value: Block): RlpItem =
      RlpItem.Sequence(
        RlpCodec[BlockHeader].encode(value.header) +: BlockBody.fieldsOf(value.body)
      )

    def decode(item: RlpItem): Either[RlpError, Block] = item match
      case _: RlpItem.Bytes => Left(RlpError.ExpectedSequence)
      case RlpItem.Sequence(items) =>
        if items.length != MandatoryFields && items.length != WithWithdrawalsFields then
          Left(RlpError.WrongWidth(WithWithdrawalsFields, items.length))
        else
          for
            header <- RlpCodec[BlockHeader].decode(items(0))
            body   <- BlockBody.fromFields(items.drop(1))
          yield Block(header, body)
