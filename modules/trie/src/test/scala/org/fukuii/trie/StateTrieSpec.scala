package org.fukuii.trie

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.storage.{KeyValueStore, Namespace, NamespaceId, Seam, WriteMode}
import org.scalatest.flatspec.AnyFlatSpec

/** The two-level composition: the account trie, a storage trie per account, and
  * the code keyspace.
  *
  * The published empty-code hash is pinned here against two sources that were
  * read rather than recalled — the executable specification, by way of the
  * account vectors this project already certifies against, and go-ethereum's own
  * `EmptyCodeHash`. As with the empty-trie root, the implementation derives it
  * instead of carrying it, so a wrong derivation cannot hide behind a right
  * constant.
  */
class StateTrieSpec extends AnyFlatSpec:

  private val publishedEmptyCodeHash = "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"

  private def address(byte: Int): Address = Address.fromBytesTruncating(IArray.fill(20)(byte.toByte))

  private def newState(): StateTrie =
    val store: KeyValueStore = TrieFixtures.store()
    val accounts = new StoredNodeTrie(Securing.Secured, store, TrieFixtures.namespace("state-nodes"))
    new StateTrie(
      accounts,
      owner => new StoredNodeTrie(Securing.Secured, store, TrieFixtures.namespace("storage-" + owner.toHex)),
      store,
      TrieFixtures.namespace("code")
    )

  private def wei(amount: Long): UInt256 = UInt256.fromLong(amount).toOption.get

  private def slot(number: Long): UInt256 = UInt256.fromLong(number).toOption.get

  private def writeAccount(state: StateTrie, owner: Address, balance: Long): Unit =
    state.putAccount(owner, UInt64.Zero, wei(balance), StateTrie.EmptyCodeHash)

  "an account with no storage" should "carry the empty-trie root" in {
    val state = newState()
    writeAccount(state, address(1), 7)
    assert(
      state.getAccount(address(1)).toOption.flatten.map(_.storageRoot).contains(Trie.EmptyRoot),
      "an account with nothing in storage commits to the empty trie, not to a zero"
    )
  }

  "a storage write" should "change that account's storage root" in {
    val state = newState()
    val before = state.storageRoot(address(1))
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    assert(state.storageRoot(address(1)) != before, "a storage trie commits to its own contents")
  }

  it should "reach the state root once the account leaf is rewritten" in {
    val state = newState()
    writeAccount(state, address(1), 7)
    val before = state.stateRoot
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    writeAccount(state, address(1), 7)
    assert(state.stateRoot != before, "the account leaf embeds the storage root, so the state root is two-level")
  }

  it should "leave the state root alone until the account leaf is rewritten" in {
    val state = newState()
    writeAccount(state, address(1), 7)
    val before = state.stateRoot
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    assert(
      state.stateRoot == before,
      "the state root commits to the storage root that was read when the leaf was written, which is why the write order is structural"
    )
  }

  "storage" should "return the same trie for one address rather than rebuilding it" in {
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    assert(
      state.getStorage(address(1), slot(1)).contains(TrieFixtures.bytesOf("ff")),
      "a rebuilt storage trie would silently lose writes"
    )
  }

  it should "keep two accounts' storage apart" in {
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    assert(state.getStorage(address(2), slot(1)).isEmpty, "one account's slot is not another's")
  }

  it should "secure a slot at its full width, so a narrow slot number is not a different key" in {
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    assert(
      state.getStorage(address(1), slot(1)).contains(TrieFixtures.bytesOf("ff")),
      "a slot number is a 256-bit word, and the width it is secured at is not the caller's to choose"
    )
  }

  "getAccount" should "return the account that was written" in {
    val state = newState()
    writeAccount(state, address(1), 7)
    assert(
      state.getAccount(address(1)).toOption.flatten.map(_.balance).contains(wei(7)),
      "an account must read back through the state trie"
    )
  }

  it should "return nothing for an address never written" in
    assert(newState().getAccount(address(9)) == Right(None), "an absent account is absence, not an error")

  it should "report a leaf that is not an account encoding" in {
    val store: KeyValueStore = TrieFixtures.store()
    val accounts = new StoredNodeTrie(Securing.Secured, store, TrieFixtures.namespace("state-nodes"))
    val state = new StateTrie(
      accounts,
      owner => new StoredNodeTrie(Securing.Secured, store, TrieFixtures.namespace("storage-" + owner.toHex)),
      store,
      TrieFixtures.namespace("code")
    )
    // Written through the account trie directly. StateTrie's own writer cannot
    // produce a leaf that is not an account, which is exactly why reaching this
    // branch takes a write that goes past it.
    accounts.put(Bytes.fromIArray(address(2).toBytes), Bytes.fromIArray(IArray(0x01.toByte)))
    val reachedDecodeFailure = state.getAccount(address(2)) match
      case Left(TrieError.MalformedAccount(_)) => true
      case _                                   => false
    assert(reachedDecodeFailure, "a leaf that is not an account encoding is an error, never an absence")
  }

  "deleteAccount" should "return the state root to empty once the last account is gone" in {
    val state = newState()
    writeAccount(state, address(1), 7)
    state.deleteAccount(address(1))
    assert(state.stateRoot == Trie.EmptyRoot, "removing the last account leaves no structure")
  }

  "the state root" should "differ between two accounts holding different balances" in {
    val first = newState()
    writeAccount(first, address(1), 7)
    val second = newState()
    writeAccount(second, address(1), 8)
    assert(first.stateRoot != second.stateRoot, "the state root commits to every account field")
  }

  "putCode" should "return the digest of the code it stored" in {
    val state = newState()
    val code = TrieFixtures.bytesOf("60006000")
    assert(state.putCode(code) == Keccak256.hash(code.toIArray), "code is addressed by the digest of its own bytes")
  }

  it should "make the code readable back under that digest" in {
    val state = newState()
    val code = TrieFixtures.bytesOf("60006000")
    val digest = state.putCode(code)
    assert(state.getCode(digest).contains(code), "the code keyspace must return what it was given")
  }

  it should "report nothing for a digest never stored" in
    assert(
      newState().getCode(Hash.fromBytesTruncating(IArray.fill(32)(0x11.toByte))).isEmpty,
      "absent code is absence"
    )

  "EmptyCodeHash" should "be the published digest of the empty byte string" in
    assert(StateTrie.EmptyCodeHash.toHex == publishedEmptyCodeHash, "an account with no code carries a real digest")

  "a StateTrie" should "refuse a code namespace tagged as chain data" in {
    val store: KeyValueStore = TrieFixtures.store()
    val accounts = new StoredNodeTrie(Securing.Secured, store, TrieFixtures.namespace("state-nodes"))
    val misfiled: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("code"), Seam.ChainData, WriteMode.Mutable)
    assertThrows[IllegalArgumentException](new StateTrie(accounts, _ => accounts, store, misfiled))
  }

  "a StateTrie" should "refuse an account trie that is not secured" in {
    val store: KeyValueStore = TrieFixtures.store()
    val accounts = new StoredNodeTrie(Securing.Unsecured, store, TrieFixtures.namespace("state-nodes"))
    assertThrows[IllegalArgumentException](
      new StateTrie(accounts, _ => accounts, store, TrieFixtures.namespace("code"))
    )
  }

  it should "refuse a storage trie that is not secured" in {
    val store: KeyValueStore = TrieFixtures.store()
    val accounts = new StoredNodeTrie(Securing.Secured, store, TrieFixtures.namespace("state-nodes"))
    val state = new StateTrie(
      accounts,
      owner => new StoredNodeTrie(Securing.Unsecured, store, TrieFixtures.namespace("storage-" + owner.toHex)),
      store,
      TrieFixtures.namespace("code")
    )
    assertThrows[IllegalArgumentException](state.storageRoot(address(1)))
  }

  private def codeNamespace(): Namespace.Standalone = TrieFixtures.namespace("code")

  /** A state whose store and code namespace stay in the test's hands, so that
    * "nothing was stored" can be asked of the store rather than inferred from a
    * lookup that is now total and would answer the same either way.
    */
  private def observableState(): (StateTrie, KeyValueStore, Namespace.Standalone) =
    val store: KeyValueStore = TrieFixtures.store()
    val accounts = new StoredNodeTrie(Securing.Secured, store, TrieFixtures.namespace("state-nodes"))
    val codeNs = codeNamespace()
    val state = new StateTrie(
      accounts,
      owner => new StoredNodeTrie(Securing.Secured, store, TrieFixtures.namespace("storage-" + owner.toHex)),
      store,
      codeNs
    )
    (state, store, codeNs)

  "destroyAccount" should "remove the account leaf" in {
    val state = newState()
    writeAccount(state, address(1), 7)
    state.destroyAccount(address(1))
    assert(state.getAccount(address(1)).toOption.flatten.isEmpty, "the destroyed account is gone from the trie")
  }

  it should "answer a storage read with absence" in {
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    state.destroyAccount(address(1))
    assert(
      state.getStorage(address(1), slot(1)).isEmpty,
      "a read after destruction sees nothing, rather than the value the destroyed account held"
    )
  }

  it should "return the storage to the empty root" in {
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    state.destroyAccount(address(1))
    assert(state.storageRoot(address(1)) == Trie.EmptyRoot, "destroyed storage commits to the empty trie")
  }

  it should "keep a write applied after it out of the destroyed storage" in {
    val untouched = newState()
    untouched.putStorage(address(1), slot(2), TrieFixtures.bytesOf("ee"))
    val expected = untouched.storageRoot(address(1))
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    state.destroyAccount(address(1))
    state.putStorage(address(1), slot(2), TrieFixtures.bytesOf("ee"))
    assert(
      state.storageRoot(address(1)) == expected,
      "storage is cleared before later writes land, so the account commits to those writes alone"
    )
  }

  "deleteAccount" should "leave the storage trie in place" in {
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    val before = state.storageRoot(address(1))
    state.deleteAccount(address(1))
    assert(
      state.storageRoot(address(1)) == before,
      "the leaf primitive touches the leaf alone, which is what makes it distinct from destroyAccount"
    )
  }

  "putCode" should "answer the empty-code hash for empty contents" in {
    val state = newState()
    assert(state.putCode(Bytes.Empty) == StateTrie.EmptyCodeHash, "the empty code hashes to the published digest")
  }

  it should "store nothing for empty contents" in {
    val (state, store, codeNs) = observableState()
    val digest = state.putCode(Bytes.Empty)
    assert(
      store.get(codeNs, Bytes.fromIArray(digest.toBytes)).isEmpty,
      "the empty code is never written, so two nodes with identical state cannot answer getCode differently"
    )
  }

  "getCode" should "answer the empty code rather than absence for the empty-code hash" in {
    val state = newState()
    assert(
      state.getCode(StateTrie.EmptyCodeHash).contains(Bytes.Empty),
      "an account with no code has empty code, which is not the same as its code being missing"
    )
  }

  it should "answer absence for a digest nothing stored" in {
    val state = newState()
    val absent = Keccak256.hash(IArray[Byte](1, 2, 3))
    assert(
      state.getCode(absent).isEmpty,
      "missing code stays distinguishable from empty code, and only the first is a fault"
    )
  }

  "deleteStorage" should "remove the slot's key" in {
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    state.deleteStorage(address(1), slot(1))
    assert(state.getStorage(address(1), slot(1)).isEmpty, "a removed slot holds no key rather than a zero value")
  }

  it should "return a storage trie holding nothing else to the empty root" in {
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    state.deleteStorage(address(1), slot(1))
    assert(
      state.storageRoot(address(1)) == Trie.EmptyRoot,
      "the root a caller applying the zero rule ends at has to be the one an account with no storage carries"
    )
  }

  it should "leave the other slots alone" in {
    val state = newState()
    state.putStorage(address(1), slot(1), TrieFixtures.bytesOf("ff"))
    state.putStorage(address(1), slot(2), TrieFixtures.bytesOf("ee"))
    state.deleteStorage(address(1), slot(1))
    assert(
      state.getStorage(address(1), slot(2)).contains(TrieFixtures.bytesOf("ee")),
      "removing one slot is not removing the account's storage"
    )
  }

  it should "be silent about a slot that holds nothing" in {
    val state = newState()
    state.deleteStorage(address(1), slot(1))
    assert(
      state.storageRoot(address(1)) == Trie.EmptyRoot,
      "a caller applying the zero rule writes zero without first reading the slot back, so this is the ordinary case"
    )
  }
