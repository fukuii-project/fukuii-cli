package org.fukuii.trie

import org.fukuii.bytes.{Bytes, Hash, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.RlpCodec
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

  /** The commitment over `values`, each keyed by its position in the sequence.
    *
    * ==One derivation, because the block's three commitments are one rule==
    *
    * A block commits to its transactions, its receipts and its withdrawals the
    * same way, and EIP-4895 says so of the third in terms of the first: the
    * withdrawals commitment *"is constructed identically to the transactions
    * root in the existing execution payload header by inserting each withdrawal
    * into a Merkle-Patricia trie keyed by index in the list"*
    * (`ethereum/EIPs` @ `dbfa6bee8` (2026-08-26), `EIPS/eip-4895.md`, Final),
    * over a function it names `compute_trie_root_from_indexed_data`.
    *
    * `besu-eth/besu` @ `fdf1247c6d` (2026-08-26) reaches one function for all
    * three -- `Util.getRootFromListOfBytes`, documented as taking a *"list of
    * the entries strictly ordered by index, starting at 0"*, called from
    * `BodyValidation`'s `transactionsRoot`, `receiptsRoot` and
    * `withdrawalsRoot` alike. `ethereum/execution-specs` @ `20f7f6271a`
    * (2026-08-26) writes the same insertion at each site, keying
    * `block_output.transactions_trie`, `receipts_trie` and `withdrawals_trie`
    * on `rlp.encode(Uint(i))` (`forks/shanghai/fork.py:644` is the withdrawals
    * one).
    *
    * **So this is deliberately not a withdrawals function.** Two of its three
    * consumers do not exist here yet, and writing it beside the one that does
    * would guarantee a second derivation when they land -- which is a
    * disagreement between two commitments in one header, and a chain split
    * rather than a duplicate.
    *
    * ==The values arrive encoded, so this stays ignorant of what it commits
    * to==
    *
    * besu passes `List<Bytes>` for the same reason: the three consumers encode
    * differently -- a typed transaction is its envelope rather than its RLP
    * list -- and a trie that knew which was which would carry each of their
    * encoding rules. What is shared is the KEY, and that is what is here.
    *
    * ==The empty sequence needs no case==
    *
    * No entries patricialize to no node, which caps to the empty reference,
    * whose commitment is [[EmptyRoot]]. besu special-cases it to
    * `Hash.EMPTY_TRIE_HASH` and reaches the same 32 bytes; the case is absent
    * here because it is not a rule, and a published fixture pins the value
    * either way.
    *
    * ==Unsecured, and the key is the RLP of the index rather than the index==
    *
    * A position is not adversarially chosen, so nothing is gained by hashing it
    * -- see [[Securing]], which already names these three as the unsecured
    * tries. The key is `RLP(i)`, so entry zero is keyed by `0x80` rather than by
    * an empty string or a zero byte -- which puts it on a different branch of
    * the tree from every other entry, where a bare index byte would put it
    * alongside them.
    */
  def rootOfIndexed(values: Seq[Bytes]): Hash =
    val entries = values.zipWithIndex.map((value, index) => Nibbles.fromBytes(indexKey(index)) -> value).toMap
    TrieNode.rootHash(TrieNode.cap(TrieNode.patricialize(entries, 0)))

  /** The key an entry at `index` occupies, which is the RLP of that index.
    *
    * The width is read through [[org.fukuii.bytes.UInt64]] because that is where
    * this project's minimal-scalar rule lives, and a second implementation of
    * *"no leading zero byte, empty for zero"* is how one commitment comes to
    * disagree with another. A sequence index is a non-negative `Int`, so it is
    * inside that width by construction and the raw bits read as the same
    * unsigned value.
    */
  private def indexKey(index: Int): IArray[Byte] =
    RlpCodec.encodeTo(UInt64.fromBits(index.toLong))
