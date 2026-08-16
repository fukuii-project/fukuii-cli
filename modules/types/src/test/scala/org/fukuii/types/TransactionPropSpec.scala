package org.fukuii.types

import org.fukuii.bytes.{Address, Hex}
import org.fukuii.rlp.{RlpCodec, RlpItem}
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Transactions against octets that shipped in a block.
  *
  * ==Where a row comes from, and why it is the strongest tier available==
  *
  * A block publishes its own `rlp` beside its decoded transaction list, so a
  * transaction can be certified against bytes a client actually produced
  * rather than against a specification run locally. Each row is the element
  * sliced out of that list: for a typed transaction the RLP string's payload,
  * which is the EIP-2718 canonical `type || rlp(payload)`; for a legacy one
  * the list item itself.
  *
  * Every row was cross-verified before being written — the fixture's
  * separately-decoded JSON fields re-encoded by an implementation independent
  * of this one and required to equal the slice. Over the whole corpus that
  * check ran on 92,806 transactions with zero mismatches, which is the reason
  * to trust the rows rather than the count of them.
  *
  * ==The published sender is what makes these more than encoding rows==
  *
  * The fixtures carry each transaction's recovered sender, so one table
  * certifies the encoding, the EIP-155 scheme detection, the signing
  * projection and the curve surface together. Nothing else available to this
  * layer tests the legacy `v` reading against an external expectation.
  */
class TransactionPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(
      label: String,
      typeNumber: Int,
      sender: Option[Address],
      hash: String,
      canonical: String
  ):
    def bytes: IArray[Byte] = Hex.decode(canonical).toOption.get
    def decoded: Transaction = Transaction.fromCanonicalBytes(bytes).toOption.get

  private def parse(line: String): Vector =
    val c = line.split(" ").toIndexedSeq
    Vector(
      label = c(0),
      typeNumber = c(1).toInt,
      sender = if c(2) == "-" then None else Address.fromHex(c(2)).toOption,
      hash = c(3),
      canonical = c(4)
    )

  private val vectors =
    val stream = Option(getClass.getResourceAsStream("/transaction-vectors.txt"))
      .getOrElse(throw new IllegalStateException("transaction-vectors.txt is not on the test classpath"))
    val source = scala.io.Source.fromInputStream(stream)
    try source.getLines().filterNot(l => l.isEmpty || l.startsWith("#")).map(parse).toVector
    finally source.close()

  private val transactions = Table(("vector"), vectors*)

  /** Selection that fills its budget from whichever type is most numerous
    * would drop the rarest ones without saying so — and type 4 is under two
    * percent of the corpus. This names the requirement so a later change to
    * the generator cannot quietly re-narrow it.
    */
  property("the table spans every transaction type the envelope models") {
    val covered = vectors.map(_.typeNumber).toSet
    assert(
      Set(0, 1, 2, 3, 4).subsetOf(covered),
      s"types ${covered.toSeq.sorted.mkString(",")} — selection has narrowed"
    )
  }

  property("the table spans both a contract creation and a call") {
    val creations = vectors.count(_.decoded.to.isEmpty)
    assert(
      creations > 0 && creations < vectors.length,
      s"$creations of ${vectors.length} rows create a contract — both cases must appear"
    )
  }

  property("a shipped transaction decodes") {
    forAll(transactions) { (v: Vector) =>
      assert(
        Transaction.fromCanonicalBytes(v.bytes).isRight,
        s"${v.label}: octets that shipped in a block must decode"
      )
    }
  }

  property("a decoded transaction re-encodes to the octets it came from") {
    forAll(transactions) { (v: Vector) =>
      assert(
        Hex.encode(Transaction.canonicalBytes(v.decoded)) == v.canonical,
        s"${v.label}: the canonical form must be byte-exact"
      )
    }
  }

  property("the type number is the one the block declared") {
    forAll(transactions) { (v: Vector) =>
      assert(
        v.decoded.typeNumber == v.typeNumber,
        s"${v.label}: expected type ${v.typeNumber}"
      )
    }
  }

  property("the transaction hash is the digest of the canonical form") {
    forAll(transactions) { (v: Vector) =>
      assert(
        Hex.encode(v.decoded.hash.toBytes) == v.hash,
        s"${v.label}: hash must match the independently computed digest"
      )
    }
  }

  /** The subtlest property in this file. Inside a block body a typed
    * transaction is an RLP STRING wrapping its canonical bytes, while a legacy
    * one is the list itself — so the codec's output and the canonical form are
    * deliberately different for four of the five types, and hashing the wrong
    * one is a wrong transaction hash on every typed transaction.
    */
  property("a typed transaction nests as a string and a legacy one as a list") {
    forAll(transactions) { (v: Vector) =>
      val nested = RlpCodec[Transaction].encode(v.decoded)
      val shape = nested match
        case RlpItem.Bytes(payload) => Hex.encode(payload) == v.canonical && v.typeNumber != 0
        case _: RlpItem.Sequence    => v.typeNumber == 0
      assert(shape, s"${v.label}: wrong nesting form for type ${v.typeNumber}")
    }
  }

  property("the block-body form round-trips through the codec") {
    forAll(transactions) { (v: Vector) =>
      val encoded = RlpCodec.encodeTo(v.decoded)
      assert(
        RlpCodec.decodeFrom[Transaction](encoded) == Right(v.decoded),
        s"${v.label}: round trip must be exact"
      )
    }
  }

  /** Certifies the whole signing path against an external expectation: the
    * preimage projection, EIP-155's scheme detection for the legacy rows, and
    * the curve surface P4 narrowed to take a `Hash`.
    */
  property("the sender recovers to the account the corpus published") {
    val withSender = Table(("vector"), vectors.filter(_.sender.isDefined)*)
    forAll(withSender) { (v: Vector) =>
      assert(
        Sender.recover(v.decoded) == Right(v.sender.get),
        s"${v.label}: recovered sender must match the published one"
      )
    }
  }

  /** A legacy transaction's `v` carries the chain identifier folded in, so
    * reading it as a bare parity recovers a wrong-but-plausible address. This
    * pins that the corpus rows actually exercise the replay-protected reading
    * rather than only the pre-EIP-155 one.
    */
  property("the legacy rows exercise the replay-protected signing scheme") {
    val schemes = vectors
      .filter(_.typeNumber == 0)
      .map(_.decoded)
      .collect { case t: Transaction.Legacy => SignatureScheme.of(t.v) }
      .collect { case Right(s) => s }
    assert(
      schemes.exists(_.isInstanceOf[SignatureScheme.Protected]),
      "no legacy row is replay-protected, so the EIP-155 preimage is untested"
    )
  }
