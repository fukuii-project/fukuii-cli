package org.fukuii.trie

import org.fukuii.bytes.{Bytes, Hash}
import org.fukuii.crypto.Keccak256
import org.fukuii.storage.LeafIterator

/** Whether a trie hashes each key before inserting it.
  *
  * State and storage tries are secured; transaction, receipt and withdrawal
  * tries are not. Hashing distributes keys uniformly, so an adversary choosing
  * addresses cannot deepen one branch of the state trie at will — which is why
  * the property belongs to the trie rather than to a caller who might forget it.
  *
  * It also decides what the ordered leaf view is ordered BY, and the two answers
  * are different orders over the same entries. See [[Trie.leaves]].
  */
enum Securing:
  case Secured, Unsecured

/** A mapping from byte-string keys to byte-string values, together with the
  * 32-byte commitment that identifies its contents.
  *
  * ==This is the node-access seam, and it deliberately does not mention nodes==
  *
  * Whether a trie's internal nodes are addressable objects at all is an
  * implementation choice rather than a property of Merkle-Patricia state. Two
  * implementations of this trait take opposite answers —
  * [[StoredNodeTrie]] keeps every node its parents reference, and
  * [[DerivedNodeTrie]] keeps none and derives the tree transiently whenever the
  * commitment is asked for. A seam shaped "give me the node at this digest"
  * would admit only the first.
  *
  * ==Deletion is expressed by storing nothing, and that is not a convenience==
  *
  * A trie represents an absent key by leaving it out, so there is no value that
  * means "present and empty": storing empty bytes at a key removes it, and
  * [[delete]] is the same operation named for what it does. Both implementations
  * agree here, which is what lets their commitments be compared at all. Whether
  * a *typed* layer above treats a zeroed entry as absent is a fork-dependent
  * question and is not this layer's.
  *
  * ==A commitment, singular==
  *
  * [[root]] is not "a" digest over the contents. It is the Merkle-Patricia root
  * as the protocol defines it, and every implementation of this trait must
  * produce the identical 32 bytes for identical contents — a divergence here is
  * a chain split rather than a defect.
  */
trait Trie:

  def securing: Securing

  def get(key: Bytes): Option[Bytes]

  /** Stores `value` at `key`. Storing empty bytes removes the key — see the
    * deletion note on this trait.
    */
  def put(key: Bytes, value: Bytes): Unit

  def delete(key: Bytes): Unit

  def root: Hash

  /** Every entry, in ascending byte order of the key the trie is built over.
    *
    * For a [[Securing.Secured]] trie that key is the digest, so the order is
    * digest order and the key each entry carries is the digest rather than the
    * pre-image — which is the order and the identifier the account and storage
    * range queries of the snap wire protocol are defined in. Recovering a
    * pre-image needs a separate record that this layer does not keep.
    *
    * The returned iterator holds a resource and MUST be closed on every path,
    * including one that fails partway through — see
    * [[org.fukuii.storage.LeafIterator]].
    */
  def leaves: LeafIterator

object Trie:

  /** The commitment of a trie with no entries.
    *
    * Derived rather than written down, because it is a consequence of the root
    * rule applied to an absent root node rather than an independent constant.
    */
  val EmptyRoot: Hash = TrieNode.rootHash(NodeRef.Empty)

  /** The key a trie is actually built over: the digest of `key` when secured,
    * and `key` itself when not.
    */
  def trieKey(securing: Securing, key: Bytes): Bytes = securing match
    case Securing.Secured   => Bytes.fromIArray(Keccak256.hash(key.toIArray).toBytes)
    case Securing.Unsecured => key
