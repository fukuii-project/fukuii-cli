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

  /** Short keys over a small alphabet, so that prefixes collide constantly and
    * one key is frequently a strict prefix of another.
    *
    * Real trie keys are 32 bytes and effectively never collide, which is exactly
    * the distribution that never exercises a branch collapse or a terminating
    * value slot. Drawing from a tiny alphabet inverts that: removals repeatedly
    * reduce a branch to one entry and force the merge back into the nibble above
    * it, which is where an incremental implementation and a derived one can
    * disagree.
    *
    * The alphabet is also chosen so its nibbles cover all sixteen child slots —
    * an alphabet tuned only for collisions can leave most of a branch's slots
    * unvisited, and a slot no property ever reaches is a slot no property
    * defends. Both halves are load-bearing: widening it far enough to stop the
    * collisions would retain the coverage and lose the collapse, which is the
    * failure this generator was rebuilt once to fix.
    */
  private val keyGen: Gen[Bytes] =
    Gen
      .choose(1, 3)
      .flatMap(length =>
        Gen.listOfN(length, Gen.oneOf(0x00, 0x11, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef).map(_.toByte))
      )
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

  /** Every unordered pair drawn from [[TrieFixtures.implementations]], each
    * member built for `securing` and driven through `operations`.
    *
    * A pairing over the fixture rather than a hardcoded two, because the defect
    * class this file exists to catch is the one a NEW implementation
    * introduces — and a hardcoded pair is silent in exactly that case. A third
    * implementation is covered by adding it to the fixture, not by hand-writing
    * pairings here.
    */
  private def pairs(
      securing: Securing,
      operations: List[(Bytes, Option[Bytes])]
  ): Vector[((String, Trie), (String, Trie))] =
    TrieFixtures.implementations
      .map((name, build) => (name, apply(build(securing), operations)))
      .combinations(2)
      .map(chosen => (chosen(0), chosen(1)))
      .toVector

  /** The first pair failing `agree`, named, or nothing. Returns the NAMES so a
    * failure says which two disagreed rather than only that some two did.
    */
  private def disagreement(
      candidates: Vector[((String, Trie), (String, Trie))]
  )(agree: (Trie, Trie) => Boolean): Option[String] =
    candidates.collectFirst {
      case ((leftName, left), (rightName, right)) if !agree(left, right) => s"$leftName and $rightName"
    }

  // Every property below sweeps `pairs`, and a sweep over an empty collection
  // passes without comparing anything -- so this file could report five green
  // properties while testing nothing at all if the fixture ever held fewer than
  // two implementations. That is the failure the generalization introduced and
  // this is what forecloses it.
  property("the fixture supplies at least one pair to compare, or every property below is vacuous") {
    assert(
      pairs(Securing.Unsecured, Nil).nonEmpty,
      "a differential suite with nothing to differentiate reports green over no comparison"
    )
  }

  property("every pair of implementations commits to the same root after an unsecured sequence") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val differing = disagreement(pairs(Securing.Unsecured, operations))(_.root == _.root)
      assert(
        differing.isEmpty,
        s"a commitment differing between implementations is a chain split: ${differing.getOrElse("")}"
      )
    }
  }

  property("every pair of implementations commits to the same root after a secured sequence") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val differing = disagreement(pairs(Securing.Secured, operations))(_.root == _.root)
      assert(differing.isEmpty, s"securing must not change which implementation is right: ${differing.getOrElse("")}")
    }
  }

  property("every pair of implementations answers every touched key identically") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val keys = operations.map(_._1).distinct
      val differing = disagreement(pairs(Securing.Unsecured, operations)) { (left, right) =>
        keys.forall(key => left.get(key) == right.get(key))
      }
      assert(differing.isEmpty, s"implementations must agree on presence and on value: ${differing.getOrElse("")}")
    }
  }

  property("every pair of implementations presents the same ordered leaf view") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val differing = disagreement(pairs(Securing.Secured, operations)) { (left, right) =>
        Using.resource(left.leaves)(_.toVector) == Using.resource(right.leaves)(_.toVector)
      }
      assert(
        differing.isEmpty,
        s"the ordered leaf view must not depend on which implementation produced it: ${differing.getOrElse("")}"
      )
    }
  }

  property("removing every key written returns every implementation to the empty root") {
    forAll(operationsGen) { (operations: List[(Bytes, Option[Bytes])]) =>
      val keys = operations.map(_._1).distinct
      val emptied = TrieFixtures.implementations.map { (name, build) =>
        val trie = apply(build(Securing.Unsecured), operations)
        keys.foreach(trie.delete)
        (name, trie)
      }
      val leftBehind = emptied.collectFirst { case (name, trie) if trie.root != Trie.EmptyRoot => name }
      assert(
        leftBehind.isEmpty,
        s"an emptied trie must leave no structure behind: ${leftBehind.getOrElse("")}"
      )
    }
  }
