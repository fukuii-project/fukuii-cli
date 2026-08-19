package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, Hash}
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
    */
  final class MapWorldState extends WorldState:

    val balances: mutable.Map[Address, Word] = mutable.Map.empty
    val codes: mutable.Map[Address, Bytes] = mutable.Map.empty
    val slots: mutable.Map[(Address, Word), Word] = mutable.Map.empty

    def balanceOf(address: Address): Word = balances.getOrElse(address, Word.Zero)

    def codeOf(address: Address): Bytes = codes.getOrElse(address, Bytes.Empty)

    def storageAt(address: Address, slot: Word): Word = slots.getOrElse((address, slot), Word.Zero)

    def setStorage(address: Address, slot: Word, value: Word): Unit = slots((address, slot)) = value

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

  def message(
      caller: Address = address(0x11),
      currentTarget: Address = address(0x22),
      value: Word = Word.Zero,
      data: Bytes = Bytes.Empty
  ): Message = Message(caller, currentTarget, value, data)

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

  def environment(
      world: WorldState = new MapWorldState,
      inBlock: BlockContext = block,
      ofTransaction: TransactionContext = transaction
  ): Environment = new Environment(world, blockHashAt, inBlock, ofTransaction)
