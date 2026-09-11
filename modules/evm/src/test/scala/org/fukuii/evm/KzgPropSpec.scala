package org.fukuii.evm

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Certification of the KZG primitive, and through it of the ceremony output
  * this repository ships, against the published vector corpus.
  *
  * The corpus is transcribed into `/kzg-vectors.txt` by
  * `scripts/gen-kzg-vectors.py`, whose header records what it states and what
  * it does not. Nothing in that file is computed: every row carries the verdict
  * its corpus states.
  *
  * ==This is the only thing that checks the SETUP FILE, and no other tier can
  * be==
  *
  * `org.fukuii.evm.Kzg` loads a 788 KiB ceremony output from this module's own
  * resources. A file that is corrupt, truncated, substituted, or simply the
  * wrong one of the two formats in circulation produces a library that loads
  * without complaint and then answers `false` for proofs that should hold.
  * **Nothing about that is visible in a diff and nothing else in this build
  * would catch it** -- the gas is right, the widths are right, the refusals are
  * right, and only a proof that ought to verify comes back wrong.
  *
  * The `holds` rows are what close that. Each one is a proof generated against
  * the same ceremony, so it verifies under that setup and under no other.
  *
  * ==The corpus discriminates in three directions, which is what makes a pass
  * mean something==
  *
  * A build that answered `Some(true)` unconditionally would fail the `fails`
  * rows; one that answered `Some(false)` unconditionally would fail the `holds`
  * rows; one that answered `None` whenever the library raised would fail both.
  * The census property below asserts all three kinds are present, because
  * `forAll` over an empty table SUCCEEDS -- a resource that failed to load
  * would leave every property here checking nothing and reporting green.
  */
class KzgPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  /** What the corpus states about one set of arguments. */
  private enum Verdict:
    case Holds, Fails, Refuse

  private case class Vector(
      verdict: Verdict,
      commitment: IArray[Byte],
      z: IArray[Byte],
      y: IArray[Byte],
      proof: IArray[Byte],
      name: String
  )

  private def bytesOf(hex: String): IArray[Byte] = EvmFixtures.bytesOf(hex.drop(2)).toIArray

  private val corpus: Seq[Vector] =
    val stream = Option(getClass.getResourceAsStream("/kzg-vectors.txt"))
    val source = stream.map(scala.io.Source.fromInputStream(_))
    try
      source.toSeq.flatMap(_.getLines()).filterNot(line => line.isEmpty || line.startsWith("#")).map { line =>
        val parts = line.split(' ')
        val stated = parts(0) match
          case "holds"  => Verdict.Holds
          case "fails"  => Verdict.Fails
          case "refuse" => Verdict.Refuse
          case other    => throw new IllegalArgumentException("the corpus names no verdict called " + other)
        Vector(stated, bytesOf(parts(1)), bytesOf(parts(2)), bytesOf(parts(3)), bytesOf(parts(4)), parts(5))
      }
    finally source.foreach(_.close())

  private def of(verdict: Verdict): Seq[Vector] = corpus.filter(_.verdict == verdict)

  private val holding = of(Verdict.Holds)

  private val failing = of(Verdict.Fails)

  private val refusing = of(Verdict.Refuse)

  private def verify(vector: Vector): Option[Boolean] =
    Kzg.verifyProof(vector.commitment, vector.z, vector.y, vector.proof)

  property("the KZG corpus loaded, with all three verdicts substantial") {
    assert(
      holding.sizeIs > 20 && failing.sizeIs > 20 && refusing.sizeIs > 5,
      "expected a substantial corpus of all three verdicts; got holds=" + holding.size.toString +
        " fails=" + failing.size.toString + " refuse=" + refusing.size.toString
    )
  }

  property("every proof the corpus states holds is verified") {
    forAll(Table("vector", holding*)) { (vector: Vector) =>
      assert(verify(vector) == Some(true), vector.name)
    }
  }

  property("every proof the corpus states does not hold is rejected") {
    forAll(Table("vector", failing*)) { (vector: Vector) =>
      assert(verify(vector) == Some(false), vector.name)
    }
  }

  /** The third verdict, kept distinct from the second.
    *
    * These are arguments the library declines rather than proofs it disproves,
    * and `org.fukuii.evm.Kzg` reports the difference. `Precompile.PointEvaluation`
    * is where the two become one answer, and `PointEvaluationPrecompileSpec`
    * asserts that collapse separately -- so the two claims are made in the two
    * places they are true, rather than one being assumed from the other.
    */
  property("every argument the corpus states names no point is declined") {
    forAll(Table("vector", refusing*)) { (vector: Vector) =>
      assert(verify(vector) == None, vector.name)
    }
  }

  /** Reaching the primitive repeatedly is harmless, which is the property a
    * persistent build server makes worth asserting.
    *
    * The ceremony output lives in a process-global `static` the native layer
    * refuses to load twice, and these tests run in the same virtual machine as
    * the build rather than a forked one -- so "loaded once" has to hold across
    * everything in this run and everything in the next run this server serves.
    * Every property above has already reached it by the time this executes.
    *
    * **What this can and cannot see.** It establishes that repeated use within
    * a process does not re-load and does not degrade. It cannot, from inside
    * one run, establish what a SECOND run against the same server does; that is
    * a property of the server rather than of this code, and it is settled by
    * running the suite twice rather than by an assertion here.
    */
  property("the primitive answers the same after repeated use in one process") {
    val vector = holding.head
    val answers = (1 to 8).map(_ => verify(vector))
    assert(answers.forall(_ == Some(true)), "repeated verification diverged: " + answers.distinct.toString)
  }
