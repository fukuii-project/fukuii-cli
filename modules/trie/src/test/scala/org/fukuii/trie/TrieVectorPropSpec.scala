package org.fukuii.trie

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Known-answer roots, driven over both implementations of the seam.
  *
  * ==Where these six roots come from, and what they are not==
  *
  * Each appears in two places that were checked rather than assumed: the
  * cross-client fixture corpus `ethereum/tests`, in `TrieTests/trieanyorder.json`,
  * `TrieTests/trietest.json` and their secured counterparts; and, for the first
  * two, go-ethereum's own `trie/trie_test.go`. The refs read are named in this
  * phase's report.
  *
  * **This is not the certification.** That is a separate, later phase which runs
  * the whole published corpus, the two-level state root from a fixture's
  * pre-state, and a vector authored here for the case no published vector
  * reaches. What these rows buy is that a module computing a consensus
  * commitment does not land with its answers unchecked against anything
  * external — six cases chosen for structural spread rather than for coverage.
  *
  * Row by row: `dogs` needs an extension above a branch above leaves;
  * `singleItem` is one leaf whose encoding passes the inline limit, so the root
  * is hashed by the ordinary rule; `smallValues` and `testy` put a terminating
  * key in a branch's own value slot; `emptyValues` reaches its root through
  * deletions, so it pins the rule that storing nothing removes a key; and the
  * two secured rows pin that the digest, not the pre-image, is what the
  * structure is built over.
  */
class TrieVectorPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  import TrieFixtures.{ascii, implementations}

  private val builds = Table(("implementation", "build"), implementations*)

  /** `None` stores nothing, which the trie treats as removal. */
  private type Entries = Seq[(String, Option[String])]

  private def written(pairs: (String, String)*): Entries = pairs.map((key, value) => key -> Some(value))

  private val vectors = Table(
    ("name", "securing", "entries", "expectedRoot"),
    (
      "dogs",
      Securing.Unsecured,
      written("doe" -> "reindeer", "dog" -> "puppy", "dogglesworth" -> "cat"),
      "8aad789dff2f538bca5d8ea56e8abe10f4c7ba3a5dea95fea4cd6e7c3a1168d3"
    ),
    (
      "singleItem",
      Securing.Unsecured,
      written("A" -> "a" * 50),
      "d23786fb4a010da3ce639d66d5e904a11dbc02746d1ce25029e53290cabf28ab"
    ),
    (
      "smallValues",
      Securing.Unsecured,
      written("be" -> "e", "dog" -> "puppy", "bed" -> "d"),
      "3f67c7a47520f79faa29255d2d3c084a7a6df0453116ed7232ff10277a8be68b"
    ),
    (
      "testy",
      Securing.Unsecured,
      written("test" -> "test", "te" -> "testy"),
      "8452568af70d8d140f58d941338542f645fcca50094b20f3c3d8c3df49337928"
    ),
    (
      "emptyValues",
      Securing.Unsecured,
      Seq(
        "do" -> Some("verb"),
        "ether" -> Some("wookiedoo"),
        "horse" -> Some("stallion"),
        "shaman" -> Some("horse"),
        "doge" -> Some("coin"),
        "ether" -> None,
        "dog" -> Some("puppy"),
        "shaman" -> None
      ),
      "5991bb8c6514148a29db676a14ac506cd2cd5775ace63c30a4fe457715e9ac84"
    ),
    (
      "dogs, secured",
      Securing.Secured,
      written("doe" -> "reindeer", "dog" -> "puppy", "dogglesworth" -> "cat"),
      "d4cd937e4a4368d7931a9cf51686b7e10abb3dce38a39000fd7902a092b64585"
    ),
    (
      "singleItem, secured",
      Securing.Secured,
      written("A" -> "a" * 50),
      "e9e2935138352776cad724d31c9fa5266a5c593bb97726dd2a908fe6d53284df"
    ),
    (
      "emptyValues, secured",
      Securing.Secured,
      Seq(
        "do" -> Some("verb"),
        "ether" -> Some("wookiedoo"),
        "horse" -> Some("stallion"),
        "shaman" -> Some("horse"),
        "doge" -> Some("coin"),
        "ether" -> None,
        "dog" -> Some("puppy"),
        "shaman" -> None
      ),
      "29b235a58c3c25ab83010c327d5932bcf05324b7d6b1185e650798034783ca9d"
    )
  )

  private def build(trie: Trie, entries: Entries): Trie =
    entries.foreach {
      case (key, Some(value)) => trie.put(ascii(key), ascii(value))
      case (key, None)        => trie.delete(ascii(key))
    }
    trie

  property("every implementation reproduces every published root") {
    forAll(builds) { (implementation: String, make: TrieFixtures.Build) =>
      forAll(vectors) { (name: String, securing: Securing, entries: Entries, expectedRoot: String) =>
        assert(
          build(make(securing), entries).root.toHex == expectedRoot,
          implementation + " must reproduce the published root for " + name
        )
      }
    }
  }
