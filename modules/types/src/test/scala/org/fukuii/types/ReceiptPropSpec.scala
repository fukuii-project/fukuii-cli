package org.fukuii.types

import org.fukuii.bytes.{Hash, Hex, UInt64}
import org.fukuii.rlp.{RlpCodec, RlpItem}
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Receipts against octets that shipped in a block.
  *
  * ==Where a row comes from==
  *
  * A block publishes each receipt's own `rlp` beside that receipt's decoded
  * status, cumulative gas, bloom and logs, so a receipt can be certified
  * against bytes a client actually produced rather than against a
  * specification run locally. The published `rlp` is the EIP-2718 canonical
  * form directly: `type || rlp(payload)` for a typed receipt, the bare list
  * for a legacy one.
  *
  * Every row was cross-verified before being written — the fixture's
  * separately-decoded fields re-encoded by an implementation independent of
  * this one and required to equal the published octets. Over the whole corpus
  * that ran on 92,806 receipts with zero mismatches.
  *
  * ==The bloom rows are what the wire protocol needs, not decoration==
  *
  * From eth/69 a receipt travels with no bloom at all and a reader must
  * recompute it before it can check the receipts root. The same corpus pass
  * recomputed all 92,806 published blooms from their published logs and
  * required agreement, so [[Receipt.withDerivedBloom]] rests on a measurement
  * rather than on the claim that `M(O)` is a function of the logs.
  */
class ReceiptPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(
      label: String,
      typeNumber: Int,
      outcome: String,
      cumulativeGasUsed: Long,
      bloom: String,
      canonical: String
  ):
    def bytes: IArray[Byte] = Hex.decode(canonical).toOption.get
    def decoded: Receipt = Receipt.fromCanonicalBytes(bytes).toOption.get

    def expectedOutcome: PostStateOrStatus = outcome match
      case "ok"   => PostStateOrStatus.Successful
      case "fail" => PostStateOrStatus.Failed
      case root   => PostStateOrStatus.PostState(Hash.fromHex(root).toOption.get)

  private def parse(line: String): Vector =
    val c = line.split(" ").toIndexedSeq
    Vector(
      label = c(0),
      typeNumber = c(1).toInt,
      outcome = c(2),
      cumulativeGasUsed = c(3).toLong,
      bloom = c(4),
      canonical = c(5)
    )

  private val vectors =
    val stream = Option(getClass.getResourceAsStream("/receipt-vectors.txt"))
      .getOrElse(
        throw new IllegalStateException("receipt-vectors.txt is not on the test classpath")
      )
    val source = scala.io.Source.fromInputStream(stream)
    try source.getLines().filterNot(l => l.isEmpty || l.startsWith("#")).map(parse).toVector
    finally source.close()

  private val receipts = Table(("vector"), vectors*)

  /** Named so a later change to the generator cannot quietly re-narrow the
    * selection. Types 1 through 4 are each a small share of the corpus, and a
    * budget filled from whichever is most numerous would drop them silently.
    */
  property("the table spans every transaction type a receipt can carry") {
    val covered = vectors.map(_.typeNumber).toSet
    assert(
      Set(0, 1, 2, 3, 4).subsetOf(covered),
      s"types ${covered.toSeq.sorted.mkString(",")} — selection has narrowed"
    )
  }

  /** The property this type's hardest design decision rests on. A corpus that
    * carried only the status form would certify a decoder that cannot read a
    * pre-Byzantium block, and would do so with a full green.
    */
  property("the table spans all three forms of the first field") {
    val forms = vectors
      .map(_.expectedOutcome)
      .map {
        case PostStateOrStatus.PostState(_) => "root"
        case PostStateOrStatus.Successful   => "ok"
        case PostStateOrStatus.Failed       => "fail"
      }
      .toSet
    assert(
      forms == Set("root", "ok", "fail"),
      s"only ${forms.toSeq.sorted.mkString(",")} present — a form is untested"
    )
  }

  property("a shipped receipt decodes") {
    forAll(receipts) { (v: Vector) =>
      assert(
        Receipt.fromCanonicalBytes(v.bytes).isRight,
        s"${v.label}: octets that shipped in a block must decode"
      )
    }
  }

  property("a decoded receipt re-encodes to the octets it came from") {
    forAll(receipts) { (v: Vector) =>
      assert(
        Hex.encode(Receipt.canonicalBytes(v.decoded)) == v.canonical,
        s"${v.label}: the canonical form must be byte-exact"
      )
    }
  }

  property("the type is the one the block declared") {
    forAll(receipts) { (v: Vector) =>
      assert(
        v.decoded.typeNumber == v.typeNumber,
        s"${v.label}: expected type ${v.typeNumber}"
      )
    }
  }

  property("the first field decodes to the form the block declared") {
    forAll(receipts) { (v: Vector) =>
      assert(
        v.decoded.postStateOrStatus == v.expectedOutcome,
        s"${v.label}: expected ${v.outcome}"
      )
    }
  }

  property("the cumulative gas is the one the block declared") {
    forAll(receipts) { (v: Vector) =>
      assert(
        v.decoded.cumulativeGasUsed == UInt64.fromLong(v.cumulativeGasUsed).toOption.get,
        s"${v.label}: expected ${v.cumulativeGasUsed}"
      )
    }
  }

  property("the bloom is the one the block declared") {
    forAll(receipts) { (v: Vector) =>
      assert(
        Hex.encode(v.decoded.logsBloom.toBytes) == v.bloom,
        s"${v.label}: the stored bloom must be byte-exact"
      )
    }
  }

  /** What a peer reading an eth/69 receipt has to do, on rows whose expected
    * answer came from outside this codebase. The wire form carries no bloom,
    * and the specification says such receipts "need to be re-encoded into the
    * format used by the Ethereum consensus protocol, and their bloom filters
    * have to be recomputed" — so a receipt rebuilt from type, outcome, gas and
    * logs alone must equal the one that shipped.
    */
  property("a receipt rebuilt without its bloom equals the one that shipped") {
    forAll(receipts) { (v: Vector) =>
      val rebuilt = Receipt.withDerivedBloom(
        v.decoded.transactionType,
        v.decoded.postStateOrStatus,
        v.decoded.cumulativeGasUsed,
        v.decoded.logs
      )
      assert(
        rebuilt == v.decoded,
        s"${v.label}: a recomputed bloom must reproduce the published one"
      )
    }
  }

  /** The same trap [[TransactionPropSpec]] pins for transactions. A typed
    * receipt inside a list is an RLP string wrapping its canonical bytes,
    * while the receipts trie stores those bytes unwrapped — so putting the
    * wrong one in either place is a wrong receipts root and a wrong block
    * hash.
    */
  property("a typed receipt nests as a string and a legacy one as a list") {
    forAll(receipts) { (v: Vector) =>
      val nested = RlpCodec[Receipt].encode(v.decoded)
      val shape = nested match
        case RlpItem.Bytes(payload) => Hex.encode(payload) == v.canonical && v.typeNumber != 0
        case _: RlpItem.Sequence    => v.typeNumber == 0
      assert(shape, s"${v.label}: wrong nesting form for type ${v.typeNumber}")
    }
  }

  property("the list-element form round-trips through the codec") {
    forAll(receipts) { (v: Vector) =>
      val encoded = RlpCodec.encodeTo(v.decoded)
      assert(
        RlpCodec.decodeFrom[Receipt](encoded) == Right(v.decoded),
        s"${v.label}: round trip must be exact"
      )
    }
  }
