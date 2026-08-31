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

    /** The same read: this state holds no pending writes, so a caller wanting
      * an uncommitted view wraps it in a `JournaledWorldState`.
      */
    def committedStorageAt(address: Address, slot: Word): Word = storageAt(address, slot)

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

  /** An ordinary call: the account whose code runs is the one it runs as, and it
    * may change state.
    *
    * A test wanting the borrowing form, or a creation, names its own code
    * address -- those are the two shapes where the two addresses differ, and
    * making the common one a default rather than all three keeps the difference
    * visible at the site that needs it.
    *
    * **`isStatic` carries a default where [[Message]] refuses one**, on the same
    * doctrine and because the direction of the failure is the opposite here. In
    * production a forgotten answer would silently admit a write the network
    * refuses; in a spec, a case meaning to be static and not saying so asserts a
    * refusal that does not happen, and fails.
    */
  def message(
      caller: Address = address(0x11),
      currentTarget: Address = address(0x22),
      value: Word = Word.Zero,
      data: Bytes = Bytes.Empty,
      transfersValue: Boolean,
      isStatic: Boolean = false
  ): Message = Message(
    caller = caller,
    currentTarget = currentTarget,
    codeAddress = Some(currentTarget),
    value = value,
    data = data,
    transfersValue = transfersValue,
    isStatic = isStatic
  )

  /** A schedule for testing the machine, whose prices are deliberately NOT any
    * network's.
    *
    * ==What a machine spec is actually for==
    *
    * These specs certify that the interpreter charges what the schedule it was
    * handed says. They are not certifying the numbers -- no network's prices
    * live in this module any more, and a suite that certified some would be
    * asserting one network's configuration from inside the machine.
    *
    * **Written against a network's real prices the distinction is untestable.**
    * `gasLeft == 100 - 3 - 3 - 3` passes for an interpreter that reads
    * `veryLow` from the schedule AND for one that has 3 compiled into it, so
    * the assertion cannot tell the two apart -- and the second is the defect.
    * Every field below therefore differs from the value the networks this
    * project targets launched with.
    *
    * ==Every field holds a DISTINCT value, and that is load-bearing==
    *
    * A spec naming the wrong field would still pass if the two fields happened
    * to agree, which under a real schedule they often do -- `veryLow`,
    * `copyPerWord` and the memory word all cost three. Distinct values make a
    * mis-named field a failure instead of a coincidence.
    *
    * `GasCost.MemoryPerWord` is deliberately not here: memory expansion is
    * parameterized where the function that uses it lives, and repeating it
    * would give one price two homes.
    */
  val schedule: GasSchedule = GasSchedule(
    base = BigInt(4),
    veryLow = BigInt(5),
    low = BigInt(7),
    mid = BigInt(9),
    high = BigInt(11),
    zero = BigInt(1),
    jumpDest = BigInt(2),
    blockHash = BigInt(22),
    balance = BigInt(24),
    externalBase = BigInt(26),
    extCodeHash = BigInt(28),
    storageLoad = BigInt(52),
    storageSet = BigInt(20002),
    storageReset = BigInt(5002),
    refundStorageClear = BigInt(15002),
    netStorageNoop = BigInt(203),
    netStorageInit = BigInt(20003),
    netStorageClean = BigInt(5003),
    netStorageDirty = BigInt(205),
    refundNetStorageClear = BigInt(15003),
    refundNetStorageResetFromZero = BigInt(19803),
    refundNetStorageReset = BigInt(4803),
    refundSelfDestruct = BigInt(24002),
    callBase = BigInt(42),
    callValue = BigInt(9002),
    callStipend = BigInt(2302),
    newAccount = BigInt(25002),
    createBase = BigInt(32002),
    codeDepositPerByte = BigInt(202),
    expBase = BigInt(12),
    expPerByte = BigInt(13),
    keccak256Base = BigInt(32),
    keccak256PerWord = BigInt(8),
    copyPerWord = BigInt(6),
    logBase = BigInt(377),
    logDataPerByte = BigInt(10),
    logTopic = BigInt(378),
    precompileEcRecover = BigInt(3002),
    precompileSha256Base = BigInt(62),
    precompileSha256PerWord = BigInt(14),
    precompileRipemd160Base = BigInt(602),
    precompileRipemd160PerWord = BigInt(122),
    precompileIdentityBase = BigInt(17),
    precompileIdentityPerWord = BigInt(15),
    precompileModExpDivisor = BigInt(27),
    precompileAltBn128Add = BigInt(502),
    precompileAltBn128Mul = BigInt(40002),
    precompileAltBn128PairingBase = BigInt(100002),
    precompileAltBn128PairingPerPoint = BigInt(80002),
    precompileBlake2fPerRound = BigInt(3),
    transactionBase = BigInt(21002),
    transactionDataPerZeroByte = BigInt(19),
    transactionDataPerNonZeroByte = BigInt(70),
    transactionCreate = BigInt(21),
    selfDestruct = BigInt(23),
    selfDestructNewAccount = BigInt(25)
  )

  /** The natives [[schedule]] prices, placed where the ecosystem places them.
    *
    * Declared above [[rules]], which reads it: a `val` referring to one below it
    * is read before it is assigned.
    */
  val precompiles: PrecompileSet =
    PrecompileSet.Empty
      .adding(PrecompileSet.EcRecover, Precompile.EcRecover(schedule.precompileEcRecover))
      .adding(PrecompileSet.Sha256, Precompile.Sha256(schedule.precompileSha256Base, schedule.precompileSha256PerWord))
      .adding(
        PrecompileSet.Ripemd160,
        Precompile.Ripemd160(schedule.precompileRipemd160Base, schedule.precompileRipemd160PerWord)
      )
      .adding(
        PrecompileSet.Identity,
        Precompile.Identity(schedule.precompileIdentityBase, schedule.precompileIdentityPerWord)
      )

  /** The rules a machine spec runs under: the original instruction set at
    * [[schedule]]'s prices, the four natives, and no proposal adopted.
    *
    * Assembled here rather than taken from a chain configuration, which this
    * module cannot see and should not: a spec about the machine names a
    * configuration it made up, and that is the whole point.
    */
  val rules: EvmRules = EvmRules(
    table = OpcodeTable.original(schedule),
    schedule = schedule,
    precompiles = precompiles,
    gasForwarded = GasForwarding.Whole,
    codeDepositMustSucceed = false,
    maxCodeSize = None,
    createdAccountNonce = UInt64.Zero,
    newAccountCharge = NewAccountCharge.WhenTheDestinationIsAbsent,
    storageMetering = StorageMetering.Legacy,
    touchSurvivesFailure = Set.empty
  )

  val block: BlockContext = BlockContext(
    coinbase = address(0xcc),
    number = BigInt(1000),
    timestamp = BigInt(1234567890),
    difficulty = BigInt(0x0100),
    gasLimit = BigInt(3141592)
  )

  val transaction: TransactionContext = TransactionContext(origin = address(0x99), gasPrice = BigInt(7))

  /** Which network the machine is running as, for the one operation that asks.
    *
    * **Neither identifier this build serves**, which are 1 and 61 and are
    * stated in the two networks' own `Mainnet` objects. A fixture carrying one
    * of those would let a spec assert the machine returns that network's value
    * and read as though it had checked a chain configuration, when what it
    * checked was this constant. What the number is beyond that is not this
    * fixture's claim to make.
    */
  val chainId: UInt64 = UInt64.fromBits(0x5eedL)

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
      withTable: OpcodeTable = OpcodeTable.original(schedule),
      withSchedule: GasSchedule = schedule,
      withPrecompiles: PrecompileSet = precompiles
  ): Environment =
    // The three stay separate parameters here rather than one set of rules,
    // because a spec overriding a table wants to say so and not to assemble a
    // configuration around it. The assembly is this helper's job.
    new Environment(
      new JournaledWorldState(world),
      blockHashAt,
      inBlock,
      ofTransaction,
      chainId,
      rules.copy(table = withTable, schedule = withSchedule, precompiles = withPrecompiles)
    )

  /** An environment running `rules` whole.
    *
    * A sibling of the helper above rather than a parameter on it: a default
    * cannot be derived from an earlier parameter in the same list, so a `rules`
    * parameter beside the three overrides would have to default them
    * independently -- and a caller passing rules would then have their table and
    * schedule silently replaced by this object's. Two helpers say which one the
    * caller means.
    */
  def environmentUnder(rules: EvmRules, world: WorldState = new MapWorldState): Environment =
    new Environment(new JournaledWorldState(world), blockHashAt, block, transaction, chainId, rules)
