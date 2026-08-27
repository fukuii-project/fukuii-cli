package org.fukuii.trie

import org.fukuii.bytes.{Bytes, Hash}
import org.fukuii.storage.{KeyValueStore, LeafIterator, Namespace, Version}

import scala.util.Using

/** A [[Trie]] that keeps no nodes at all: entries live in a versioned keyspace,
  * and the node tree is built transiently whenever the commitment is asked for.
  *
  * ==This is the specification's own shape==
  *
  * The executable specification holds a trie as a plain mapping and derives the
  * node tree inside its root function, storing nothing between calls. A trie
  * that maintains a node store is therefore an optimization over the definition
  * rather than the definition, which is why this implementation exists beside
  * [[StoredNodeTrie]] and not underneath it.
  *
  * ==What it costs and what it buys==
  *
  * Every [[root]] call is proportional to the whole entry set, so this is the
  * wrong shape for a chain tip that commits once per block. It is the right
  * shape for the seam: it needs no node addressability whatsoever, which is what
  * makes it the counterexample proving [[Trie]] does not secretly require any.
  * Its [[leaves]] is the store's own ordered view rather than a walk, so the
  * ordered-leaf-view obligation costs it nothing.
  *
  * ==Do not hand this to `StateTrie`'s storage-trie parameter==
  *
  * That parameter is required to return an EMPTY trie each time it is called,
  * because account destruction is expressed by dropping the memoized storage
  * trie and letting the next call rebuild one -- the mechanism each of the four
  * implementations read for [[StateTrie.destroyAccount]] uses, and that scaladoc
  * names them at their refs. [[StoredNodeTrie]] satisfies it, its root reference
  * starting empty. This does not: it holds no state of its own and addresses
  * `(namespace, version)` directly, so a freshly constructed one sees every
  * entry already written under that pair, and a destruction through it would
  * commit to the storage that was supposed to be gone.
  *
  * The requirement is stated on the parameter; this is the note saying which
  * implementation fails it, which the parameter's own scaladoc cannot say
  * without naming a type it does not depend on.
  *
  * Nothing enforces this, and a `StateTrie` IS built over both implementations
  * today by the differential state-root property spec -- safely, because that
  * spec destroys nothing. The two ways out, neither taken yet: give this trie a
  * fresh version on rebuild, or move destruction into the pending-diff layer
  * above the trie, which is where the field keeps it.
  *
  * @param namespace
  *   the keyspace this trie's entries occupy. Keys within it are trie keys, so
  *   for a [[Securing.Secured]] trie the store's ascending byte order over them
  *   is digest order without the store knowing what a digest is.
  * @param version
  *   the snapshot the entries belong to. Which version a trie under construction
  *   belongs to is decided by whatever executes a block, not here.
  */
final class DerivedNodeTrie(
    val securing: Securing,
    store: KeyValueStore,
    namespace: Namespace.Standalone,
    version: Version
) extends Trie:

  def get(key: Bytes): Option[Bytes] =
    store.getAt(namespace, version, Trie.trieKey(securing, key))

  def put(key: Bytes, value: Bytes): Unit =
    if value.isEmpty then delete(key)
    else store.updateAt(namespace, version, Nil, Seq(Trie.trieKey(securing, key) -> value))

  def delete(key: Bytes): Unit =
    store.updateAt(namespace, version, Seq(Trie.trieKey(securing, key)), Nil)

  def root: Hash =
    val entries = Using.resource(store.leaves(namespace, version)) { open =>
      open.map((key, value) => Nibbles.fromBytes(key.toIArray) -> value).toMap
    }
    TrieNode.rootHash(TrieNode.cap(TrieNode.patricialize(entries, 0)))

  def leaves: LeafIterator = store.leaves(namespace, version)
