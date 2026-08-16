package org.fukuii.types

import org.fukuii.bytes.Hex
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Transactions the specification expects to be refused — and the ones it
  * expects to be refused SOMEWHERE ELSE.
  *
  * ==Why the table has two verdicts rather than one==
  *
  * The published invalid set spans two layers. An over-wide nonce and a
  * malformed authorization tuple are decidable from the bytes, so they are
  * this layer's to refuse. An invalid authority signature, an `s` above the
  * curve's half order, and an empty authorization list are not: each needs a
  * recovered authority or a fork rule, and a decoder that refused them would
  * be answering a question it does not own.
  *
  * **Both directions are defects.** Refusing a `decode` row would make this
  * client reject transactions the network accepts — a split, not a bug — and
  * accepting a `reject` row would let malformed bytes through. So the table
  * asserts each row's own expected verdict rather than a blanket one.
  *
  * ==This table exists because mutation testing said it had to==
  *
  * Two guards — the authorization tuple's arity and the single-byte bound on
  * an authorization's `y_parity` — survived a mutation battery run against the
  * valid corpus alone, because every row that shipped in a block is by
  * definition well-formed. The rejecting direction had nothing certifying it.
  */
class TransactionRejectionPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(
      label: String,
      verdict: String,
      exception: String,
      bound: String,
      txbytes: String
  ):
    def bytes: IArray[Byte] = Hex.decode(txbytes).toOption.get

  private def parse(line: String): Vector =
    val c = line.split(" ").toIndexedSeq
    Vector(label = c(0), verdict = c(1), exception = c(2), bound = c(3), txbytes = c(4))

  private val vectors =
    val stream = Option(getClass.getResourceAsStream("/transaction-invalid-vectors.txt"))
      .getOrElse(
        throw new IllegalStateException("transaction-invalid-vectors.txt is not on the test classpath")
      )
    val source = scala.io.Source.fromInputStream(stream)
    try source.getLines().filterNot(l => l.isEmpty || l.startsWith("#")).map(parse).toVector
    finally source.close()

  private val mustReject = Table(("vector"), vectors.filter(_.verdict == "reject")*)
  private val mustDecode = Table(("vector"), vectors.filter(_.verdict == "decode")*)

  property("the table carries both verdicts, or it is testing one direction only") {
    val counts = vectors.groupBy(_.verdict).view.mapValues(_.size).toMap
    val spread = counts.toSeq.sortBy(_._1).map((k, n) => s"$k=$n").mkString(", ")
    assert(
      counts.getOrElse("reject", 0) > 0 && counts.getOrElse("decode", 0) > 0,
      s"verdict spread $spread — both directions must be represented"
    )
  }

  property("the table spans every structural rejection class the corpus names") {
    val classes = vectors.filter(_.verdict == "reject").map(_.exception).toSet
    val named = classes.toSeq.sorted.mkString(", ")
    assert(
      Set("NONCE_OVERFLOW", "TYPE_4_INVALID_AUTHORIZATION_FORMAT").subsetOf(classes),
      s"structural classes $named — selection has narrowed"
    )
  }

  property("bytes the specification calls structurally invalid do not decode") {
    forAll(mustReject) { (v: Vector) =>
      assert(
        Transaction.fromCanonicalBytes(v.bytes).isLeft,
        s"${v.label}: ${v.exception} (${v.bound}) must be refused when the bytes are read"
      )
    }
  }

  /** The direction that is easy to get wrong in the safe-looking way. A
    * decoder that refused these would look stricter and be incorrect: the
    * bytes are well-formed, and what is wrong with them is not decidable here.
    */
  property("bytes whose fault lies above this layer still decode") {
    forAll(mustDecode) { (v: Vector) =>
      assert(
        Transaction.fromCanonicalBytes(v.bytes).isRight,
        s"${v.label}: ${v.exception} is not a decoding question — these bytes must decode"
      )
    }
  }
