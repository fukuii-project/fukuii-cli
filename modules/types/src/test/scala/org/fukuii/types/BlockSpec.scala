package org.fukuii.types

import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** What a block encoding must refuse, and the one distinction it must keep.
  *
  * [[BlockPropSpec]] pins what a valid block encodes to. This pins the
  * boundary — and unlike a header, which must tolerate a tail it cannot name,
  * a block's arity is closed at three or four. That is measured rather than
  * assumed: the executable specification's newest fork defines the same body
  * the withdrawals fork did, and the newest wire version's block access lists
  * are fetched by their own message rather than carried here.
  */
class BlockSpec extends AnyFlatSpec:

  private val body = BlockBody(transactions = Seq.empty, ommers = Seq.empty)

  private def blockItems: Vector[RlpItem] =
    RlpCodec[Block].encode(Block(BlockSpec.header, body)) match
      case RlpItem.Sequence(items) => items
      case _: RlpItem.Bytes        => Vector.empty

  private def decodeBlock(items: Vector[RlpItem]): Either[RlpError, Block] =
    RlpCodec[Block].decode(RlpItem.Sequence(items))

  private def decodeBody(items: Vector[RlpItem]): Either[RlpError, BlockBody] =
    RlpCodec[BlockBody].decode(RlpItem.Sequence(items))

  "a block of two elements" should "be refused" in {
    assert(
      decodeBlock(blockItems.dropRight(1)) ==
        Left(RlpError.WrongWidth(Block.WithWithdrawalsFields, 2)),
      "a block is a header plus a body, so two elements is missing one"
    )
  }

  "a block of five elements" should "be refused rather than truncated to four" in {
    val tooLong = blockItems ++ Vector(RlpItem.Sequence(Vector.empty), RlpItem.Sequence(Vector.empty))
    assert(
      decodeBlock(tooLong) == Left(RlpError.WrongWidth(Block.WithWithdrawalsFields, 5)),
      "no fork defines a fifth element, and carrying one would cost injectivity"
    )
  }

  "a byte string where a block is expected" should "be refused" in {
    assert(
      RlpCodec[Block].decode(RlpItem.Bytes(IArray.empty)) == Left(RlpError.ExpectedSequence),
      "a block is a list"
    )
  }

  "a block whose header element is a byte string" should "be refused" in {
    assert(
      decodeBlock(blockItems.updated(0, RlpItem.Bytes(IArray.empty))) ==
        Left(RlpError.ExpectedSequence),
      "the first element is a header, which is a list"
    )
  }

  "a body of one element" should "be refused" in {
    assert(
      decodeBody(Vector(RlpItem.Sequence(Vector.empty))) ==
        Left(RlpError.WrongWidth(BlockBody.WithWithdrawalsFields, 1)),
      "a body is transactions and ommers at minimum"
    )
  }

  "a body of four elements" should "be refused" in {
    val tooLong = Vector.fill(4)(RlpItem.Sequence(Vector.empty))
    assert(
      decodeBody(tooLong) == Left(RlpError.WrongWidth(BlockBody.WithWithdrawalsFields, 4)),
      "the body has three fields at most"
    )
  }

  /** The canonicality property this type turns on. A fork with no withdrawals
    * and a block with none are different facts one element apart, so the two
    * must not share an encoding — if they did, one value would decode to the
    * other and a body would stop round-tripping.
    */
  "an absent withdrawals element" should "not encode the same as an empty one" in {
    val absent = BlockBody(Seq.empty, Seq.empty, None)
    val empty  = BlockBody(Seq.empty, Seq.empty, Some(Seq.empty))
    assert(
      RlpCodec.encodeTo(absent).toSeq != RlpCodec.encodeTo(empty).toSeq,
      "three elements and two are different bodies, not two spellings of one"
    )
  }

  it should "round-trip back to absent rather than to an empty list" in {
    val absent = BlockBody(Seq.empty, Seq.empty, None)
    assert(
      RlpCodec.decodeFrom[BlockBody](RlpCodec.encodeTo(absent)) == Right(absent),
      "a pre-withdrawals body must not gain a field by being decoded"
    )
  }

  "an empty withdrawals list" should "round-trip back to an empty list" in {
    val empty = BlockBody(Seq.empty, Seq.empty, Some(Seq.empty))
    assert(
      RlpCodec.decodeFrom[BlockBody](RlpCodec.encodeTo(empty)) == Right(empty),
      "a block that had no withdrawals is not a block from before they existed"
    )
  }

  /** A block hash is the header's digest. Nothing else in the suite would
    * catch a change to a digest over the block's own encoding, because such a
    * value is stable and round-trips perfectly — it is simply not the number
    * the network uses.
    */
  "a block's hash" should "be the header's and not a digest of the block" in {
    val block = Block(BlockSpec.header, body)
    assert(
      block.hash == block.header.hash,
      "every commitment a block makes already lives in its header"
    )
  }

object BlockSpec:

  /** A header of the fifteen mandatory fields, built rather than parsed
    * because these cases are about arity and nesting rather than about any
    * particular header's contents.
    */
  private val header: BlockHeader =
    import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
    BlockHeader(
      parentHash = Hash.fromBytesTruncating(IArray.fill(32)(1)),
      ommersHash = Hash.fromBytesTruncating(IArray.fill(32)(2)),
      beneficiary = Address.fromBytesTruncating(IArray.fill(20)(3)),
      stateRoot = Hash.fromBytesTruncating(IArray.fill(32)(4)),
      transactionsRoot = Hash.fromBytesTruncating(IArray.fill(32)(5)),
      receiptsRoot = Hash.fromBytesTruncating(IArray.fill(32)(6)),
      logsBloom = Bloom.Empty,
      difficulty = UInt256.fromLong(7).toOption.get,
      number = UInt64.fromLong(8).toOption.get,
      gasLimit = UInt64.fromLong(9).toOption.get,
      gasUsed = UInt64.fromLong(10).toOption.get,
      timestamp = UInt64.fromLong(11).toOption.get,
      extraData = Bytes.fromIArray(IArray.empty[Byte]),
      seal = Seal.Ethash(
        Hash.fromBytesTruncating(IArray.fill(32)(12)),
        BlockNonce.fromBytes(IArray.fill(8)(13)).toOption.get
      )
    )
