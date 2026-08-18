package org.fukuii.trie

import org.fukuii.bytes.Bytes
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** The published trie corpus, driven over both implementations of the seam.
  *
  * ==What this certifies that a hand-transcribed table cannot==
  *
  * Every row is generated from `ethereum/tests` TrieTests by
  * `scripts/gen-trie-vectors.py`, which gates each one against the executable
  * specification before writing it: the specification's own trie is driven over
  * the same decoded entries and required to reproduce the published root. So a
  * row reaching this file has already been agreed on by two sources that are
  * not fukuii, and a failure here is fukuii's.
  *
  * ==The authored rows are the half no corpus supplies==
  *
  * A node whose encoding is under 32 bytes is embedded in its parent; the root
  * has no parent, so the root of such a trie is the digest of its own encoding
  * whatever its width. The generator measures the published corpus and records
  * that its narrowest root node is 33 bytes — one over the limit — so no
  * published vector exercises the rule at all, and an implementation that gets
  * it wrong passes the entire corpus. Those rows carry two derivations instead
  * of a published root, and the generator's header states both.
  *
  * @see
  *   [[TrieVectorPropSpec]], which pins a small hand-checked subset and is
  *   deliberately independent of the generator that produces this table.
  */
class TrieCorpusPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  import TrieFixtures.{bytesOf, implementations}

  /** Whether the corpus gave a vector's entries as a JSON object or a list.
    *
    * The distinction is not presentational: an object cannot express a delete
    * and its root must not depend on insertion order, which is what the
    * `trieanyorder` files exist to pin. A list can, so its order is
    * load-bearing and reversing it is a different trie.
    */
  private enum Insertion:
    case AnyOrder, AsGiven

  final private case class TrieVector(
      label: String,
      securing: Securing,
      insertion: Insertion,
      entries: Seq[(Bytes, Option[Bytes])],
      root: String
  )

  /** Corpus rows carry a published root; authored rows carry two derivations.
    * Counted separately below so that a generator change dropping either kind
    * is a failure rather than a smaller table nothing remarks on.
    */
  private val PublishedVectorCount: Int = 25
  private val AuthoredVectorCount: Int = 7

  private val vectors: Seq[TrieVector] =
    val stream = Option(getClass.getResourceAsStream("/trie-vectors.txt"))
      .getOrElse(
        throw new IllegalStateException("trie-vectors.txt is not on the test classpath")
      )
    val source = scala.io.Source.fromInputStream(stream)
    try source.getLines().filterNot(l => l.isEmpty || l.startsWith("#")).map(parse).toVector
    finally source.close()

  private def parse(line: String): TrieVector =
    val fields = line.split(" ").toVector
    val entries = fields.drop(5).map(parseEntry)
    require(entries.length == fields(3).toInt, "a vector's entry count must match its entries")
    TrieVector(
      label = fields(0),
      securing = if fields(1) == "secured" then Securing.Secured else Securing.Unsecured,
      insertion = if fields(2) == "set" then Insertion.AnyOrder else Insertion.AsGiven,
      entries = entries,
      root = fields(4)
    )

  /** `-` is the corpus's JSON null, a delete; `@` is a genuinely empty byte
    * string, which this trie also treats as removal.
    */
  private def parseEntry(token: String): (Bytes, Option[Bytes]) =
    val at = token.indexOf(':')
    val value = token.substring(at + 1)
    (decode(token.substring(0, at)), if value == "-" then None else Some(decode(value)))

  private def decode(token: String): Bytes =
    if token == "@" then Bytes.Empty else bytesOf(token)

  private def build(trie: Trie, entries: Seq[(Bytes, Option[Bytes])]): Trie =
    entries.foreach {
      case (key, Some(value)) => trie.put(key, value)
      case (key, None)        => trie.delete(key)
    }
    trie

  private val builds = Table(("implementation", "build"), implementations*)
  private val rows = Table("vector", vectors*)
  private val anyOrder = Table("vector", vectors.filter(_.insertion == Insertion.AnyOrder)*)

  property("every implementation reproduces every vector's root") {
    forAll(builds) { (implementation: String, make: TrieFixtures.Build) =>
      forAll(rows) { (vector: TrieVector) =>
        assert(
          build(make(vector.securing), vector.entries).root.toHex == vector.root,
          implementation + " disagrees with the corpus on " + vector.label
        )
      }
    }
  }

  /** The structure is a function of the key set, so a vector the corpus gave as
    * an object must reach the same root however its entries are ordered. An
    * implementation that maintains the trie incrementally is where this can
    * fail, because there the shape is built by a sequence of edits rather than
    * derived in one pass.
    */
  property("an any-order vector's root does not depend on insertion order") {
    forAll(builds) { (implementation: String, make: TrieFixtures.Build) =>
      forAll(anyOrder) { (vector: TrieVector) =>
        assert(
          build(make(vector.securing), vector.entries.reverse).root.toHex == vector.root,
          implementation + " changed the root of " + vector.label + " under reversed insertion"
        )
      }
    }
  }

  property("the table carries every published TrieTests vector") {
    assert(
      vectors.count(_.label.startsWith("corpus-")) == PublishedVectorCount,
      "the published corpus supplies " + PublishedVectorCount +
        " rooted vectors; a different count means the generator dropped one or the corpus moved"
    )
  }

  /** Without this the authored rows could vanish from a regenerated table and
    * nothing would report it — and they are the only coverage of the one rule
    * the published corpus cannot reach.
    */
  property("the table carries the authored sub-32-byte root-node vectors") {
    assert(
      vectors.count(_.label.startsWith("authored-")) == AuthoredVectorCount,
      "the root-node inline exception is reached by no published vector, so losing these rows "
        + "silently removes its only coverage"
    )
  }
