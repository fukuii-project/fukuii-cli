package org.fukuii.trie

import org.fukuii.bytes.Bytes
import org.fukuii.crypto.Keccak256
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.util.Using

/** One conformance suite, run against every implementation of the seam.
  *
  * The value of two structurally-opposed implementations is that they are
  * behaviorally interchangeable through [[Trie]], not that both compile against
  * it. Every property here is therefore stated once and driven over the table
  * rather than written twice — a second copy is how one implementation quietly
  * acquires a behavior the other does not have.
  */
class TrieConformancePropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  import TrieFixtures.{ascii, implementations}

  private val builds = Table(("implementation", "build"), implementations*)

  property("a key never written is absent") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      assert(build(Securing.Unsecured).get(ascii("absent")).isEmpty, name + " must report an unwritten key absent")
    }
  }

  property("a written key reads back the value it was written with") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      trie.put(ascii("dog"), ascii("puppy"))
      assert(trie.get(ascii("dog")).contains(ascii("puppy")), name + " must read back what it stored")
    }
  }

  property("writing a key twice keeps the second value") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      trie.put(ascii("dog"), ascii("puppy"))
      trie.put(ascii("dog"), ascii("hound"))
      assert(trie.get(ascii("dog")).contains(ascii("hound")), name + " must replace rather than accumulate")
    }
  }

  property("delete removes a key") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      trie.put(ascii("dog"), ascii("puppy"))
      trie.delete(ascii("dog"))
      assert(trie.get(ascii("dog")).isEmpty, name + " must remove a deleted key")
    }
  }

  property("storing empty bytes removes a key, because a trie has no way to say present-and-empty") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      trie.put(ascii("dog"), ascii("puppy"))
      trie.put(ascii("dog"), Bytes.Empty)
      assert(trie.get(ascii("dog")).isEmpty, name + " must treat an empty value as absence")
    }
  }

  property("an empty trie commits to the empty-trie root") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      assert(
        build(Securing.Unsecured).root == Trie.EmptyRoot,
        name + " must commit to the empty root when it holds nothing"
      )
    }
  }

  property("deleting every entry returns the commitment to the empty-trie root") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      Seq("do", "dog", "doge", "horse").foreach(key => trie.put(ascii(key), ascii(key)))
      Seq("do", "dog", "doge", "horse").foreach(key => trie.delete(ascii(key)))
      assert(trie.root == Trie.EmptyRoot, name + " must leave no structure behind after the last removal")
    }
  }

  property("deleting a key that was never present leaves the commitment unchanged") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      trie.put(ascii("dog"), ascii("puppy"))
      val before = trie.root
      trie.delete(ascii("cat"))
      assert(trie.root == before, name + " must not disturb the structure for an absent key")
    }
  }

  property("the commitment does not depend on the order entries were written in") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val keys = Seq("do", "dog", "doge", "horse", "shaman", "ether")
      val forwards = build(Securing.Unsecured)
      keys.foreach(key => forwards.put(ascii(key), ascii(key)))
      val backwards = build(Securing.Unsecured)
      keys.reverse.foreach(key => backwards.put(ascii(key), ascii(key)))
      assert(forwards.root == backwards.root, name + " must commit to the entry set rather than to a write order")
    }
  }

  property("leaves returns every entry in ascending order of the key the trie is built over") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      Seq("horse", "do", "shaman", "dog").foreach(key => trie.put(ascii(key), ascii(key)))
      val observed = Using.resource(trie.leaves)(_.toVector.map(_._1.toHex))
      assert(observed == observed.sorted, name + " must present entries in ascending key order")
    }
  }

  property("a secured trie's leaves carry the digest of the key rather than the key") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Secured)
      trie.put(ascii("dog"), ascii("puppy"))
      val expected = Keccak256.hash(ascii("dog").toIArray).toHex
      val observed = Using.resource(trie.leaves)(_.toVector.map(_._1.toHex))
      assert(
        observed == Vector(expected),
        name + " must expose the secured key, which is what snap ranges are defined over"
      )
    }
  }

  property("securing changes the commitment for the same entries") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val secured = build(Securing.Secured)
      val unsecured = build(Securing.Unsecured)
      secured.put(ascii("dog"), ascii("puppy"))
      unsecured.put(ascii("dog"), ascii("puppy"))
      assert(secured.root != unsecured.root, name + " must commit to the key it was built over")
    }
  }

  property("an iterator reports itself closed once released") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      trie.put(ascii("dog"), ascii("puppy"))
      val iterator = trie.leaves
      iterator.close()
      assert(iterator.isClosed, name + " must honour the iterator release contract")
    }
  }

  property("a key that is a strict prefix of another is held alongside it") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      trie.put(ascii("do"), ascii("verb"))
      trie.put(ascii("dog"), ascii("puppy"))
      assert(
        trie.get(ascii("do")).contains(ascii("verb")) && trie.get(ascii("dog")).contains(ascii("puppy")),
        name + " must keep a terminating key beside the keys that extend it"
      )
    }
  }

  property("removing the shorter of two keys sharing a prefix leaves the longer readable") {
    forAll(builds) { (name: String, build: TrieFixtures.Build) =>
      val trie = build(Securing.Unsecured)
      trie.put(ascii("do"), ascii("verb"))
      trie.put(ascii("dog"), ascii("puppy"))
      trie.delete(ascii("do"))
      assert(
        trie.get(ascii("dog")).contains(ascii("puppy")),
        name + " must not lose the surviving key when a branch collapses"
      )
    }
  }
