package org.fukuii.types

import org.fukuii.bytes.Hex
import org.fukuii.rlp.RlpCodec
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Blocks against octets that shipped, and one stratum they cannot supply.
  *
  * ==Where a corpus row comes from==
  *
  * A fixture publishes the whole block `rlp` beside its separately-decoded
  * header, transaction list, ommer list and withdrawals, so a block can be
  * certified against bytes a client actually produced. Every row was rebuilt
  * from those decoded fields by an implementation independent of this one and
  * required to equal the published octets; over the whole corpus that ran on
  * 88,906 blocks with zero mismatches.
  *
  * ==The stratum the corpus does not contain==
  *
  * All 88,906 of those blocks carry an EMPTY ommer list — not most of them,
  * every one. A suite built only from them would report a full green over a
  * field nothing had exercised, which is this section's recurring lesson
  * rather than a new one. The `built-` rows place a header that shipped into
  * the ommers list and are labelled in the vector file as certifying encoding
  * and round trip only, since such a block is invalid on a rule this layer
  * does not assert.
  */
class BlockPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(
      label: String,
      arity: Int,
      transactionCount: Int,
      ommerCount: Int,
      withdrawalCount: Int,
      hash: String,
      encoded: String
  ):
    def bytes: IArray[Byte] = Hex.decode(encoded).toOption.get
    def decoded: Block = RlpCodec.decodeFrom[Block](bytes).toOption.get
    def carriesWithdrawalsElement: Boolean = withdrawalCount >= 0

  private def parse(line: String): Vector =
    val c = line.split(" ").toIndexedSeq
    Vector(c(0), c(1).toInt, c(2).toInt, c(3).toInt, c(4).toInt, c(5), c(6))

  private val vectors =
    val stream = Option(getClass.getResourceAsStream("/block-vectors.txt"))
      .getOrElse(
        throw new IllegalStateException("block-vectors.txt is not on the test classpath")
      )
    val source = scala.io.Source.fromInputStream(stream)
    try source.getLines().filterNot(l => l.isEmpty || l.startsWith("#")).map(parse).toVector
    finally source.close()

  private val blocks = Table(("vector"), vectors*)

  property("the table spans a block with and without a withdrawals element") {
    val arities = vectors.map(_.arity).toSet
    assert(
      arities == Set(3, 4),
      s"arities ${arities.toSeq.sorted.mkString(",")} — one of the two body shapes is untested"
    )
  }

  /** Named because the corpus cannot supply it and a later change to the
    * generator could drop the constructed rows without anything else noticing.
    */
  property("the table spans a block carrying ommers") {
    assert(
      vectors.exists(_.ommerCount > 0),
      "every corpus block has an empty ommer list, so the constructed rows are the only coverage"
    )
  }

  /** An empty withdrawals list and no withdrawals element are different facts
    * about different forks, and they are one element apart in the encoding.
    */
  property("the table spans an empty withdrawals list and an absent one") {
    val empty = vectors.exists(v => v.carriesWithdrawalsElement && v.withdrawalCount == 0)
    val absent = vectors.exists(v => !v.carriesWithdrawalsElement)
    assert(empty && absent, "both the empty list and the absent element must appear")
  }

  /** These blocks carry real headers, so they reach a tail length the header's
    * own vector file does not: those rows come from an older corpus whose
    * headers run 15, 16, 20 and 21, and this one publishes 17-element headers
    * in quantity. The middle of the tail chain is where an off-by-one index
    * shifts every later field, so leaving it to a constructed case only was a
    * real gap rather than a tidy one.
    */
  property("the table spans a header from the middle of the tail chain") {
    val arities = vectors.map(_.decoded.header.fieldCount).toSet
    assert(
      arities.contains(BlockHeader.MandatoryFields + 2),
      s"header arities ${arities.toSeq.sorted.mkString(",")} — the middle tail lengths are untested"
    )
  }

  property("a shipped block decodes") {
    forAll(blocks) { (v: Vector) =>
      assert(
        RlpCodec.decodeFrom[Block](v.bytes).isRight,
        s"${v.label}: octets that shipped must decode"
      )
    }
  }

  property("a decoded block re-encodes to the octets it came from") {
    forAll(blocks) { (v: Vector) =>
      assert(
        Hex.encode(RlpCodec.encodeTo(v.decoded)) == v.encoded,
        s"${v.label}: the encoding must be byte-exact"
      )
    }
  }

  /** The block hash is the HEADER's hash. A digest over the block's own
    * encoding would be perfectly stable and agreed with by nobody, and the
    * published expectation is what makes the difference observable.
    */
  property("the block hash is the header's, and is the one the fixture published") {
    forAll(blocks) { (v: Vector) =>
      assert(
        Hex.encode(v.decoded.hash.toBytes) == v.hash,
        s"${v.label}: expected the published header hash"
      )
    }
  }

  property("the transaction count is the one the block declared") {
    forAll(blocks) { (v: Vector) =>
      assert(
        v.decoded.body.transactions.length == v.transactionCount,
        s"${v.label}: expected ${v.transactionCount} transactions"
      )
    }
  }

  property("the ommer count is the one the block declared") {
    forAll(blocks) { (v: Vector) =>
      assert(
        v.decoded.body.ommers.length == v.ommerCount,
        s"${v.label}: expected ${v.ommerCount} ommers"
      )
    }
  }

  property("a withdrawals element decodes as present and its absence as absent") {
    forAll(blocks) { (v: Vector) =>
      assert(
        v.decoded.body.withdrawals.map(_.length).getOrElse(-1) == v.withdrawalCount,
        s"${v.label}: expected withdrawal count ${v.withdrawalCount}"
      )
    }
  }

  property("a block round-trips through the codec") {
    forAll(blocks) { (v: Vector) =>
      assert(
        RlpCodec.decodeFrom[Block](RlpCodec.encodeTo(v.decoded)) == Right(v.decoded),
        s"${v.label}: round trip must be exact"
      )
    }
  }

  /** The body travels on its own in the wire protocol, so it has to encode
    * correctly detached from the header it arrived with.
    */
  property("the body alone round-trips through its own codec") {
    forAll(blocks) { (v: Vector) =>
      val body = v.decoded.body
      assert(
        RlpCodec.decodeFrom[BlockBody](RlpCodec.encodeTo(body)) == Right(body),
        s"${v.label}: the detached body must round-trip"
      )
    }
  }

  /** A block's elements are its header followed by the body's, flat. Nesting
    * the body as one element would produce a well-formed list that no client
    * accepts, so this pins the splice rather than trusting it.
    */
  property("a block is its header followed by the body's own elements") {
    forAll(blocks) { (v: Vector) =>
      val bodyItems = RlpCodec[BlockBody].encode(v.decoded.body)
      val blockItems = RlpCodec[Block].encode(v.decoded)
      val spliced = (blockItems, bodyItems) match
        case (org.fukuii.rlp.RlpItem.Sequence(b), org.fukuii.rlp.RlpItem.Sequence(d)) =>
          b.drop(1) == d
        case _ => false
      assert(spliced, s"${v.label}: the body must be spliced in, not nested")
    }
  }
