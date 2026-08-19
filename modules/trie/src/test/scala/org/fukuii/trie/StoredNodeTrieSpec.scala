package org.fukuii.trie

import org.fukuii.bytes.Bytes
import org.fukuii.storage.{KeyValueStore, Namespace}
import org.scalatest.flatspec.AnyFlatSpec

/** Behavior that belongs to the node-storing implementation rather than to the
  * [[Trie]] contract, so it is not in the conformance suites: `DerivedNodeTrie`
  * keeps no nodes and there is nothing there for these to be true of.
  */
class StoredNodeTrieSpec extends AnyFlatSpec:

  private def freshStore(): (KeyValueStore, Namespace.Standalone) =
    (TrieFixtures.store(), TrieFixtures.namespace("nodes"))

  private def storedUnder(store: KeyValueStore, nodes: Namespace.Standalone, trie: Trie): Option[Bytes] =
    store.get(nodes, Bytes.fromIArray(trie.root.toBytes))

  "a trie whose root node is short enough to inline" should "still write that root to the node store" in {
    val (store, nodes) = freshStore()
    val trie = new StoredNodeTrie(Securing.Unsecured, store, nodes)
    trie.put(TrieFixtures.ascii("a"), TrieFixtures.ascii("b"))
    assert(
      storedUnder(store, nodes, trie).isDefined,
      "a root digest the trie publishes has to resolve in the store it published from, or a reader gets an exception on a digest this trie handed it"
    )
  }

  "a trie whose root node is too long to inline" should "write that root to the node store" in {
    val (store, nodes) = freshStore()
    val trie = new StoredNodeTrie(Securing.Unsecured, store, nodes)
    trie.put(TrieFixtures.ascii("a"), TrieFixtures.ascii("b" * 64))
    assert(
      storedUnder(store, nodes, trie).isDefined,
      "the long-root case was already stored, and pinning it is what keeps the short-root assertion from passing vacuously"
    )
  }
