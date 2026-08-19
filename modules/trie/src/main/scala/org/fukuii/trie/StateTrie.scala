package org.fukuii.trie

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.RlpCodec
import org.fukuii.storage.{KeyValueStore, Namespace}
import org.fukuii.types.Account

import scala.collection.mutable

/** World state: one trie over accounts, one trie per account over that account's
  * storage, and a keyspace for code that is not a trie at all.
  *
  * ==The state root is two-level, and the ordering is structural here==
  *
  * An account's encoding embeds the root of its own storage trie, so every
  * storage root must exist before the account leaf that commits to it can be
  * built. That is why [[putAccount]] does not take an
  * [[org.fukuii.types.Account]]: an account value already carries a storage
  * root, so accepting one would let a caller supply a stale or unrelated root
  * and produce a state root that commits to storage the node does not have.
  * Instead the fields that are genuinely the caller's are passed, and the
  * storage root is read from this account's own storage trie at the moment the
  * leaf is written. A design that lets the account leaf be built first cannot be
  * repaired by calling things in a better order.
  *
  * ==Code is a keyspace, not a trie==
  *
  * Code is addressed by the digest of its bytes and never enumerated in key
  * order, so it commits to nothing and needs no trie. An account references it
  * by that digest.
  *
  * @param storageTrieFor
  *   builds the storage trie for an address. Where those tries' bytes live —
  *   one keyspace per account, or one shared keyspace under a prefix — is a
  *   representation choice this type admits rather than makes. It is called at
  *   most once per address; the result is retained, because a storage trie that
  *   was rebuilt between calls would silently lose writes.
  */
final class StateTrie(
    accounts: Trie,
    storageTrieFor: Address => Trie,
    store: KeyValueStore,
    code: Namespace.Standalone
):

  require(
    accounts.securing == Securing.Secured,
    "the account trie is secured, or the state root commits to a different structure"
  )

  private val storageTries: mutable.Map[Address, Trie] = mutable.Map.empty

  /** This account's storage trie, created on first use and retained after.
    *
    * Private because a storage key is a slot number and the trie is byte-keyed:
    * a slot handed over at its minimal width secures a different pre-image and
    * commits to a different storage root, and therefore a different state root.
    * The typed operations below are the surface, and they take [[UInt256]] so
    * that width is not a thing a caller can get wrong.
    */
  private def storage(address: Address): Trie =
    storageTries.getOrElseUpdate(
      address, {
        val built = storageTrieFor(address)
        require(
          built.securing == Securing.Secured,
          "a storage trie is secured, or the storage root commits to a different structure"
        )
        built
      }
    )

  /** Writes `value` to `slot` of this account's storage.
    *
    * The slot is left-padded to a whole word before it is secured, because a
    * slot number is a 256-bit word and the trie hashes exactly the bytes it is
    * handed.
    *
    * ==A zero-valued slot is omitted by the caller, never detected here==
    *
    * `value` is opaque bytes, so this seam cannot tell a zero word from any
    * other value: the zero word's RLP is `0x80`, which is non-empty and so
    * stores a leaf where the storage trie must hold no key at all. That rule
    * belongs to a layer writing storage from typed values, which does not exist
    * yet — the executable specification places it in the same position,
    * deleting on a zero word before any encoding happens. Typing `value` here
    * is what would move it, and until something writes storage from typed
    * words there is nothing at this seam to enforce.
    */
  def putStorage(address: Address, slot: UInt256, value: Bytes): Unit =
    storage(address).put(slotKey(slot), value)

  def getStorage(address: Address, slot: UInt256): Option[Bytes] =
    storage(address).get(slotKey(slot))

  /** The root this account's storage trie currently commits to. */
  def storageRoot(address: Address): Hash = storage(address).root

  /** Writes the account at `address`, taking its storage root from its own
    * storage trie rather than from the caller. See the two-level note on this
    * type for why the storage root is not a parameter.
    */
  def putAccount(address: Address, nonce: UInt64, balance: UInt256, codeHash: Hash): Unit =
    val account = Account(nonce, balance, storage(address).root, codeHash)
    accounts.put(addressKey(address), Bytes.fromIArray(RlpCodec.encodeTo(account)))

  def getAccount(address: Address): Either[TrieError, Option[Account]] =
    accounts.get(addressKey(address)) match
      case None          => Right(None)
      case Some(encoded) =>
        RlpCodec
          .decodeFrom[Account](encoded.toIArray)
          .left
          .map(TrieError.MalformedAccount.apply)
          .map(Some.apply)

  /** Removes the account leaf, and nothing else.
    *
    * The account's storage trie is left as it is, and [[storageTries]] goes on
    * holding it — so writing the same address again reads its storage root back
    * through that retained trie and commits to the destroyed account's storage
    * rather than to the empty root. Reclaiming the trie's bytes is a retention
    * decision, but evicting the memo is not: it is what a destroy-then-recreate
    * sequence requires, and this operation does not do it.
    */
  def deleteAccount(address: Address): Unit = accounts.delete(addressKey(address))

  def stateRoot: Hash = accounts.root

  /** Stores `contents` under its own digest and returns that digest, which is
    * what an account carries as its code hash.
    */
  def putCode(contents: Bytes): Hash =
    val digest = Keccak256.hash(contents.toIArray)
    store.update(code, Nil, Seq(Bytes.fromIArray(digest.toBytes) -> contents))
    digest

  def getCode(digest: Hash): Option[Bytes] = store.get(code, Bytes.fromIArray(digest.toBytes))

  private def addressKey(address: Address): Bytes = Bytes.fromIArray(address.toBytes)

  private def slotKey(slot: UInt256): Bytes = Bytes.fromIArray(slot.toBytes)

object StateTrie:

  /** The code hash an account with no code carries: the digest of the empty byte
    * string, which is a real 32-byte value rather than a zero. Derived rather
    * than written down, for the reason [[Trie.EmptyRoot]] is.
    */
  val EmptyCodeHash: Hash = Keccak256.hash(IArray.empty[Byte])
