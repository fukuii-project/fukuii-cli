package org.fukuii.trie

import org.fukuii.bytes.Bytes
import org.scalacheck.Gen
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.util.Using

/** The two implementations agree, over sequences neither was written against.
  *
  * ==Why this is an invariant and not a convenience==
  *
  * Two implementations that are never compared prove nothing: both get built,
  * both pass their own examples, and they disagree at a commitment nobody
  * checked. The commitment is the Merkle-Patricia root, singular, so a
  * disagreement between two implementations in one binary is the same class of
  * defect as a disagreement between two clients on a network.
  *
  * ==What the generators are shaped for==
  *
  * Two-byte keys, deliberately. Real trie keys are 32 bytes and almost never
  * collide, which is exactly the case that never exercises a branch collapse; a
  * two-byte key space collides constantly, so removals repeatedly reduce a
  * branch to one entry and force the merge back into the nibble above it. Values
  * straddle the inline limit in both directions so that a node's reference
  * changes kind as the sequence runs, and the operation mix includes both ways
  * of removing a key.
  */
class TrieDifferentialPropSpec extends AnyPropSpec with ScalaCheckPropertyChecks:

  /** Raised well above the default, and the reason was measured rather than
    * assumed: at the default run count a deliberately broken branch collapse in
    * one implementation was caught by the published-root table and NOT by this
    * file, because no generated sequence happened to reduce a branch to a single
    * entry. A differential test that cannot reach the case it exists for reports
    * agreement it never checked.
    */
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 300)

  private val byteGen: Gen[Byte] = Gen.choose(0, 255).map(_.toByte)

  /** Short keys over a five-symbol alphabet, so that prefixes collide constantly
    * and one key is frequently a strict prefix of another.
    *
    * Real trie keys are 32 bytes and effectively never collide, which is exactly
    * the distribution that never exercises a branch collapse or a terminating
    * value slot. Drawing from a tiny alphabet inverts that: removals repeatedly
    * reduce a branch to one entry and force the merge back into the nibble above
    * it, which is where an incremental implementation and a derived one can
    * disagree.
    */
  private val keyGen: Gen[Bytes] =
    Gen
      .choose(1, 3)
      .flatMap(length => Gen.listOfN(length, Gen.oneOf(0x00, 0x01, 0x10, 0x11, 0xf0).map(_.toByte)))
      .map(bytes => Bytes.fromIArray(IArray.from(bytes)))

  private val valueGen: Gen[Bytes] = Gen
    .oneOf(1, 2, 31, 32, 40)
    .flatMap(width => Gen.listOfN(width, byteGen))
    .map(bytes => Bytes.fromIArray(IArray.from(bytes)))

  /** `None` is an explicit delete; `Some(empty)` removes by storing nothing. */
  private val operationGen: Gen[(Bytes, Option[Bytes])] =
    for
      key <- keyGen
      action <- Gen.frequency(
        5 -> valueGen.map(Some.apply),
        2 -> Gen.const(Some(Bytes.Empty)),
        3 -> Gen.const(None)
      )
    yield (key, action)

  private val operationsGen: Gen[List[(Bytes, Option[Bytes])]] =
    Gen.choose(0, 40).flatMap(count => Gen.listOfN(count, operationGen))

  private def apply(trie: Trie, operations: List[(Bytes, Option[Bytes])]): Trie =
    operations.foreach {
      case (key, Some(value)) => trie.put(key, value)
      case (key, None)        => trie.delete(key)
    }
    trie

  private def pair(
      securing: Securing,
      operations: List[(Bytes, Option[Bytes])]
  ): (Trie, Trie) =
    (
      apply(TrieFixtures.storedNode(securing), operations),
      apply(TrieFixtures.derivedNode(securing), operations)
    )

  property("both implementations commit to the same root after an unsecured sequence") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val (stored, derived) = pair(Securing.Unsecured, operations)
      assert(stored.root == derived.root, "a commitment that differs between two implementations is a chain split")
    }
  }

  property("both implementations commit to the same root after a secured sequence") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val (stored, derived) = pair(Securing.Secured, operations)
      assert(stored.root == derived.root, "securing must not change which implementation is right")
    }
  }

  property("both implementations answer every touched key identically") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val (stored, derived) = pair(Securing.Unsecured, operations)
      assert(
        operations.map(_._1).distinct.forall(key => stored.get(key) == derived.get(key)),
        "the two implementations must agree on presence and on value"
      )
    }
  }

  property("both implementations present the same ordered leaf view") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val (stored, derived) = pair(Securing.Secured, operations)
      val fromStored = Using.resource(stored.leaves)(_.toVector)
      val fromDerived = Using.resource(derived.leaves)(_.toVector)
      assert(fromStored == fromDerived, "the ordered leaf view must not depend on which implementation produced it")
    }
  }

  property("removing every key written returns both implementations to the empty root") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val (stored, derived) = pair(Securing.Unsecured, operations)
      operations.map(_._1).distinct.foreach { key =>
        stored.delete(key)
        derived.delete(key)
      }
      assert(
        stored.root == Trie.EmptyRoot && derived.root == Trie.EmptyRoot,
        "an emptied trie must leave no structure behind in either implementation"
      )
    }
  }
