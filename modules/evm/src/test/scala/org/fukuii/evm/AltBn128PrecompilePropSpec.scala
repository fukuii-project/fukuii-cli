package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.fukuii.crypto.Keccak256
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Certification of the three natives that answer over `alt_bn128` against
  * three published corpora.
  *
  * The corpora are transcribed into `/altbn128-vectors.txt` by
  * `scripts/gen-altbn128-vectors.py`, whose own header records what each states
  * and what it does not. Nothing in that file is computed: a row carries the
  * answer its corpus states, the digest of the answer its corpus states, or the
  * fact that its corpus says the call fails.
  *
  * ==Why one corpus states digests rather than answers==
  *
  * The fixture release drives each native through a caller contract that stores
  * `keccak256(returndata)`, so the answer itself appears nowhere in it. Hashing
  * this build's own answer and comparing is the same assertion by a different
  * route -- and it is the ONLY one of the three that reaches EIP-197's rule
  * that a point of the second group must be of the group's order, which is why
  * it is read rather than skipped as the awkward one.
  *
  * ==Twenty-four inputs appear twice, stated two different ways==
  *
  * Where a literal row and a digest row carry the same input, two corpora that
  * do not derive from one another are asserting the same answer through
  * different statements of it. That agreement is a property of the corpus
  * rather than something asserted here, and it is recorded because it is what
  * makes the digest rows worth as much as the literal ones.
  */
class AltBn128PrecompilePropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  /** The prices this fork states, which is what the gas rows below are checked
    * against. `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-196.md` § Gas costs and
    * `EIPS/eip-197.md` § Gas costs.
    */
  private val add = Precompile.AltBn128Add(BigInt(500))

  private val mul = Precompile.AltBn128Mul(BigInt(40000))

  private val pairing = Precompile.AltBn128PairingCheck(BigInt(100000), BigInt(80000))

  private def native(op: String): Precompile = op match
    case "add"     => add
    case "mul"     => mul
    case "pairing" => pairing
    case other     => throw new IllegalArgumentException("the corpus names no native called " + other)

  /** What a corpus states about one input. */
  private enum Expectation:
    case Answers(answer: Bytes)
    case Digests(digest: String)
    case Refuses

  private case class Vector(op: String, input: Bytes, expectation: Expectation, name: String)

  private def bytesOf(hex: String): Bytes = EvmFixtures.bytesOf(hex)

  private val corpus: Seq[Vector] =
    val stream = Option(getClass.getResourceAsStream("/altbn128-vectors.txt"))
    val source = stream.map(scala.io.Source.fromInputStream(_))
    try
      source.toSeq.flatMap(_.getLines()).filterNot(line => line.isEmpty || line.startsWith("#")).map { line =>
        val parts = line.split(' ')
        val stated =
          if parts(2) == "halt" then Expectation.Refuses
          else if parts(2).startsWith("keccak:") then Expectation.Digests(parts(2).drop("keccak:".length))
          else Expectation.Answers(bytesOf(parts(2).drop(2)))
        Vector(parts(0), bytesOf(parts(1).drop(2)), stated, parts(3))
      }
    finally source.foreach(_.close())

  private def of(kind: Expectation => Boolean): Seq[Vector] = corpus.filter(vector => kind(vector.expectation))

  private val answering = of(_.isInstanceOf[Expectation.Answers])

  private val digesting = of(_.isInstanceOf[Expectation.Digests])

  private val refusing = of(_ == Expectation.Refuses)

  /** The four rules a refusal can reach, as the generator names them.
    *
    * Asserted as a set rather than counted: a corpus losing one of these
    * entirely would leave that rule with no case at all, which is the coverage
    * loss worth failing on. How MANY cases reach each is a property of the
    * corpus and moves whenever it is regenerated.
    */
  private val refusals = Set("field", "curve", "subgroup", "length")

  private def ruleOf(vector: Vector): String = vector.name.split('/').last.split('-').head

  property("the alt_bn128 corpus loaded, with all three kinds of row substantial") {
    // `forAll` over an empty table SUCCEEDS, so a resource that failed to load
    // would leave every property below checking nothing and reporting green.
    assert(
      answering.sizeIs > 50 && digesting.sizeIs > 50 && refusing.sizeIs > 50,
      "expected a substantial corpus of all three kinds; got answered=" + answering.size.toString +
        " digested=" + digesting.size.toString + " refused=" + refusing.size.toString
    )
  }

  property("the corpus reaches every rule a refusal can be for") {
    val reached = refusing.map(ruleOf).toSet
    assert(
      reached == refusals,
      "the corpus reaches " + reached.toSeq.sorted.mkString(", ") + " rather than " +
        refusals.toSeq.sorted.mkString(", ")
    )
  }

  property("each native answers what its corpus states outright") {
    forAll(Table("vector", answering*)) { (vector: Vector) =>
      val stated = vector.expectation match
        case Expectation.Answers(answer) => answer
        case _                           => Bytes.Empty
      assert(native(vector.op).run(vector.input) == Right(stated), vector.name)
    }
  }

  property("each native answers what its corpus states as a digest") {
    forAll(Table("vector", digesting*)) { (vector: Vector) =>
      val stated = vector.expectation match
        case Expectation.Digests(digest) => digest
        case _                           => ""
      assert(
        native(vector.op)
          .run(vector.input)
          .map(answer => Bytes.fromIArray(Keccak256.hash(answer.toIArray).toBytes).toHex) ==
          Right(stated),
        vector.name
      )
    }
  }

  property("each native refuses what its corpus states it refuses") {
    forAll(Table("vector", refusing*)) { (vector: Vector) =>
      assert(native(vector.op).run(vector.input) == Left(Halt.InvalidParameter), vector.name)
    }
  }
