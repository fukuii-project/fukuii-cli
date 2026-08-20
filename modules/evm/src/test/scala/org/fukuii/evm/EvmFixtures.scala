package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.storage.{
  InMemoryKeyValueStore,
  KeyValueStore,
  Layout,
  Namespace,
  NamespaceId,
  RepresentationId,
  Seam,
  WriteMode
}
import org.fukuii.trie.{Securing, StateTrie, StoredNodeTrie}

import scala.collection.mutable

/** Construction shared by the machine's specs.
  *
  * Two world states are offered and they answer different questions. The map
  * one is for specs about what an operation does, where a trie would only add
  * ways for a test to fail that have nothing to do with the operation. The trie
  * one is for specs about the seam itself, where the trie is the subject.
  */
object EvmFixtures:

  def address(byte: Int): Address = Address.fromBytesTruncating(IArray.fill(Address.Width)(byte.toByte))

  def hash(byte: Int): Hash = Hash.fromBytesTruncating(IArray.fill(Hash.Width)(byte.toByte))

  def word(value: Long): Word = Word(BigInt(value))

  def bytesOf(hex: String): Bytes = Bytes.fromHex(hex).toOption.get

  /** World state held in maps: no trie, no encoding, no store.
    *
    * `setStorage` records a zero as a zero, which is [[WorldState]]'s stated
    * contract and not a shortcut -- turning it into a removal is the trie
    * implementation's job, and a double that did it here would agree with the
    * real one for the wrong reason.
    *
    * Existence is tracked separately from the field maps, because a written
    * field and an account brought into being with no fields written are both
    * accounts and only the first would show in a map of values.
    */
  final class MapWorldState extends WorldState:

    val balances: mutable.Map[Address, Word] = mutable.Map.empty
    val nonces: mutable.Map[Address, UInt64] = mutable.Map.empty
    val codes: mutable.Map[Address, Bytes] = mutable.Map.empty
    val slots: mutable.Map[(Address, Word), Word] = mutable.Map.empty
    val present: mutable.Set[Address] = mutable.Set.empty

    def balanceOf(address: Address): Word = balances.getOrElse(address, Word.Zero)

    def nonceOf(address: Address): UInt64 = nonces.getOrElse(address, UInt64.Zero)

    def codeOf(address: Address): Bytes = codes.getOrElse(address, Bytes.Empty)

    def accountExists(address: Address): Boolean =
      present.contains(address) || balances.contains(address) || nonces.contains(address) ||
        codes.contains(address)

    def hasStorage(address: Address): Boolean = slots.keysIterator.exists(_._1 == address)

    def storageAt(address: Address, slot: Word): Word = slots.getOrElse((address, slot), Word.Zero)

    def setStorage(address: Address, slot: Word, value: Word): Unit = slots((address, slot)) = value

    def setBalance(address: Address, value: Word): Unit = balances(address) = value

    def setNonce(address: Address, value: UInt64): Unit = nonces(address) = value

    def setCode(address: Address, code: Bytes): Unit = codes(address) = code

    def touch(address: Address): Unit =
      val _ = present.add(address)

  def store(): KeyValueStore = new InMemoryKeyValueStore(Layout(RepresentationId("evm-spec"), Set.empty))

  private def namespace(id: String): Namespace.Standalone =
    Namespace.Standalone(NamespaceId(id), Seam.State, WriteMode.Mutable)

  def stateTrie(): StateTrie =
    val backing = store()
    new StateTrie(
      new StoredNodeTrie(Securing.Secured, backing, namespace("state-nodes")),
      owner => new StoredNodeTrie(Securing.Secured, backing, namespace("storage-" + owner.toHex)),
      backing,
      namespace("code")
    )

  /** An ordinary call: the account whose code runs is the one it runs as.
    *
    * A test wanting the borrowing form, or a creation, names its own code
    * address -- those are the two shapes where the two addresses differ, and
    * making the common one a default rather than all three keeps the difference
    * visible at the site that needs it.
    */
  def message(
      caller: Address = address(0x11),
      currentTarget: Address = address(0x22),
      value: Word = Word.Zero,
      data: Bytes = Bytes.Empty
  ): Message = Message(caller, currentTarget, Some(currentTarget), value, data)

  /** The precompiles the baseline schedule prices. */
  val precompiles: PrecompileSet = PrecompileSet.baseline(GasSchedule.Baseline)

  val block: BlockContext = BlockContext(
    coinbase = address(0xcc),
    number = BigInt(1000),
    timestamp = BigInt(1234567890),
    difficulty = BigInt(0x0100),
    gasLimit = BigInt(3141592)
  )

  val transaction: TransactionContext = TransactionContext(origin = address(0x99), gasPrice = BigInt(7))

  /** A block hash that is a function of the number it is asked for, so a test
    * can name the answer it expects without carrying a table of hashes.
    */
  def blockHashAt(number: BigInt): Hash = hash(number.toInt & 0xff)

  /** An environment over `world`, journaled, because that is what the machine
    * requires and what production supplies.
    *
    * A test reading a write back reads it through the environment's own world
    * state rather than out of the map beneath, since a write is held by the
    * journal until something commits it.
    */
  def environment(
      world: WorldState = new MapWorldState,
      inBlock: BlockContext = block,
      ofTransaction: TransactionContext = transaction,
      withTable: OpcodeTable = OpcodeTable.baseline(GasSchedule.Baseline),
      withSchedule: GasSchedule = GasSchedule.Baseline,
      withPrecompiles: PrecompileSet = precompiles
  ): Environment =
    new Environment(
      new JournaledWorldState(world),
      blockHashAt,
      inBlock,
      ofTransaction,
      withTable,
      withSchedule,
      withPrecompiles
    )
