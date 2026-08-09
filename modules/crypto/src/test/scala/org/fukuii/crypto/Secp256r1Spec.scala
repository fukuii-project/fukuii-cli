package org.fukuii.crypto

import java.math.BigInteger
import org.fukuii.bytes.Hex
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Certification against besu's P256VERIFY corpus,
  * `evm/src/test/resources/org/hyperledger/besu/evm/precompile/p256verify_test_vectors.json`
  * @ `fd8389c576f4065ec77fea1130885ee09ffdc4b1`, which besu in turn draws from
  * Google's Wycheproof suite.
  *
  * The corpus is carried as a text resource rather than generated Scala —
  * `scripts/gen-p256-vectors.py` rebuilds it — and its value is the negative
  * half. A verifier that returns `true` unconditionally passes every positive
  * vector ever written; what catches it is signature malleability, an
  * out-of-range scalar, and a point that is not on the curve, which is what
  * Wycheproof is built to supply and what a hand-written vector set never
  * remembers to include.
  */
class Secp256r1Spec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(input: IArray[Byte], shouldVerify: Boolean, name: String)

  private val corpus: Seq[Vector] =
    val stream = Option(getClass.getResourceAsStream("/p256verify-vectors.txt"))
    val source = stream.map(scala.io.Source.fromInputStream(_))
    try
      source.toSeq.flatMap(_.getLines()).filter(_.nonEmpty).map { line =>
        val parts = line.split(' ')
        Vector(Hex.decode(parts(0)).toOption.get, parts(1) == "1", parts(2))
      }
    finally source.foreach(_.close())

  /** Guards against a vacuous pass.
    *
    * `forAll` over an empty table SUCCEEDS, so a resource that failed to load
    * would turn both properties below into checks of nothing that still report
    * green. The counts are not asserted exactly — the corpus is regenerable and
    * an exact figure would be a maintained value — but both classes must be
    * present and substantial.
    */
  property("the corpus loaded, with both outcomes represented") {
    val valid   = corpus.count(_.shouldVerify)
    val invalid = corpus.count(!_.shouldVerify)
    assert(
      valid > 100 && invalid > 100,
      "expected a substantial corpus of both kinds; got valid=" + valid.toString + " invalid=" + invalid.toString
    )
  }

  property("every signature the corpus accepts verifies") {
    val accepted = Table("vector", corpus.filter(_.shouldVerify)*)
    forAll(accepted) { (v: Vector) =>
      assert(Secp256r1.verifyPacked(v.input), v.name)
    }
  }

  property("every signature the corpus rejects is rejected") {
    val rejected = Table("vector", corpus.filterNot(_.shouldVerify)*)
    forAll(rejected) { (v: Vector) =>
      assert(!Secp256r1.verifyPacked(v.input), v.name)
    }
  }

  property("a packed input of the wrong length is rejected") {
    val short = corpus.head.input.slice(0, 159)
    assert(!Secp256r1.verifyPacked(short), "159 bytes is not the precompile's calling convention")
  }

  private val oneByteHash = IArray.unsafeFromArray(BigInteger.ONE.toByteArray)

  property("a zero r is rejected outright") {
    assert(!Secp256r1.verify(oneByteHash, BigInt(0), BigInt(1), BigInt(1), BigInt(1)), "r = 0 must never verify")
  }

  property("a zero s is rejected outright") {
    assert(!Secp256r1.verify(oneByteHash, BigInt(1), BigInt(0), BigInt(1), BigInt(1)), "s = 0 must never verify")
  }
