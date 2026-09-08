package org.fukuii.trie

import org.fukuii.bytes.{Bytes, Hex, UInt64}
import org.fukuii.rlp.RlpCodec
import org.scalatest.flatspec.AnyFlatSpec

/** [[Trie.rootOfIndexed]], checked against a trie the same entries were put
  * into by hand.
  *
  * ==A differential, because the derivation has no store to be read back
  * from==
  *
  * The commitment is the only observable this function has, so an assertion
  * over it alone cannot say whether the entries went in under the right keys --
  * a wrong key gives a wrong root, and a wrong root is indistinguishable from a
  * wrong node rule. Putting `RLP(i) -> value` into an unsecured [[Trie]] and
  * comparing the two commitments states the key rule as a claim the test can
  * fail on, and it borrows the two implementations this module already
  * certifies against published vectors rather than a second node rule written
  * here.
  *
  * **What this file deliberately does NOT assert is a published root**, which
  * would need entries encoded the way one of the three consumers encodes them.
  * `org.fukuii.execution.WithdrawalsSpec` does that for the consumer that
  * exists, against three roots from the shipped fixtures.
  */
class IndexedRootSpec extends AnyFlatSpec:

  private def value(byte: Int): Bytes = Bytes.fromIArray(IArray.fill(40)(byte.toByte))

  private def keyedByHand(values: Seq[Bytes]): Trie =
    val trie = TrieFixtures.storedNode(Securing.Unsecured)
    values.zipWithIndex.foreach: (payload, index) =>
      trie.put(Bytes.fromIArray(RlpCodec.encodeTo(UInt64.fromBits(index.toLong))), payload)
    trie

  "rootOfIndexed" should "commit to nothing as the empty trie does" in
    assert(Trie.rootOfIndexed(Seq.empty) == Trie.EmptyRoot, "no entries is the absent root node")

  it should "agree with a trie keyed by the RLP of each index, for one entry" in {
    val values = Seq(value(0xaa))
    assert(Trie.rootOfIndexed(values) == keyedByHand(values).root, Hex.encode(Trie.rootOfIndexed(values).toBytes))
  }

  it should "agree with a trie keyed by the RLP of each index, across a branch" in {
    // Index zero is keyed 0x80 and the rest 0x01..0x0f, so entry zero sits on
    // its own branch of the tree and the other fifteen share one -- which is
    // what separates the RLP key from a bare index byte, where all sixteen
    // would sit together.
    val values = (0 until 16).map(i => value(0xb0 + i))
    assert(Trie.rootOfIndexed(values) == keyedByHand(values).root, Hex.encode(Trie.rootOfIndexed(values).toBytes))
  }

  it should "agree with a trie keyed by the RLP of each index, past one byte of index" in {
    val values = (0 until 300).map(i => value(i % 251))
    assert(Trie.rootOfIndexed(values) == keyedByHand(values).root, Hex.encode(Trie.rootOfIndexed(values).toBytes))
  }

  it should "key the first entry by the RLP of zero rather than by an empty string" in {
    val single = Seq(value(0xaa))
    val trie = TrieFixtures.storedNode(Securing.Unsecured)
    trie.put(Bytes.Empty, single.head)
    assert(Trie.rootOfIndexed(single) != trie.root, "an empty key and RLP(0) are different paths")
  }

  it should "distinguish two sequences that differ only in order" in {
    val forwards = Seq(value(0x11), value(0x22))
    assert(Trie.rootOfIndexed(forwards) != Trie.rootOfIndexed(forwards.reverse), "position is part of the commitment")
  }
