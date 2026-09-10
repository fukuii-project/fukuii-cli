package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.flatspec.AnyFlatSpec

/** The two operations over a keyspace one transaction writes and no block
  * keeps.
  *
  * ==Neither is in the original table, so every case here adds both==
  *
  * [[OpcodeTable.original]] carries neither, which is what makes a network that
  * has not adopted the proposal refuse both bytes. Each case below runs a table
  * that has been given them at the figure the document names -- the same
  * arrangement [[PushZeroSpec]], [[BaseFeeSpec]] and [[ChainIdSpec]] use.
  *
  * Expected behavior is `ethereum/EIPs` @ `d2a64c2d4` (2026-09-09),
  * `EIPS/eip-1153.md` (Final), read against
  * `ethereum/execution-specs` @ `0cc100eb1`
  * (`forks/cancun/vm/instructions/storage.py` and `state_tracker.py`).
  *
  * ==The cases that matter most are the four about the LIFETIME==
  *
  * A build that made these two read and write an ordinary map in the frame
  * would satisfy every single-frame case here. What separates the two is what a
  * nested invocation sees and what survives one failing, so those are asserted
  * across a `CALL` rather than inside one frame -- and the sibling
  * [[JournaledWorldStateSpec]] asserts the same rule against the type that
  * keeps it.
  */
class TransientStorageSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Held above every test registration: Scala 3's initialization checker reads
  // a val declared below one as read-before-init, and reports it against the
  // first test in the class rather than against the val.
  private val runner: Address = EvmFixtures.address(0x22)

  private val other: Address = EvmFixtures.address(0x33)

  private val slot: Word = EvmFixtures.word(1)

  private val table: OpcodeTable =
    OpcodeTable
      .original(schedule)
      .adding(Operation(Opcode.TLoad, Cost.Fixed(schedule.warmAccess)))
      .adding(Operation(Opcode.TStore, Cost.Fixed(schedule.warmAccess)))

  private def push1(value: Int): Seq[Int] = Seq(0x60, value & 0xff)

  private def push20(address: Address): Seq[Int] =
    0x73 +: (0 until Address.Width).map(index => address.toBytes(index) & 0xff)

  /** Writes `value` at slot 1 of transient storage. */
  private def transientlyStoring(value: Int): Seq[Int] = push1(value) ++ push1(0x01) :+ Opcode.TStore.code

  /** Reads slot 1 of transient storage onto the stack. */
  private val transientlyLoading: Seq[Int] = push1(0x01) :+ Opcode.TLoad.code

  /** Reads slot 1 of transient storage and writes it to slot 1 of persistent
    * storage, so that a nested invocation's reading is observable from outside.
    */
  private val recordingWhatItReads: Seq[Int] = transientlyLoading ++ push1(0x01) :+ 0x55

  private def hex(program: Seq[Int]): String = program.map(byte => f"$byte%02x").mkString

  /** A `CALL` to `target`, forwarding `gas`, with no value and no data. */
  private def calling(target: Address, gas: Int): Seq[Int] =
    push1(0x00) ++ push1(0x00) ++ push1(0x00) ++ push1(0x00) ++
      push1(0x00) ++ push20(target) ++ Seq(0x61, (gas >> 8) & 0xff, gas & 0xff) :+ 0xf1

  /** The table above with the borrowing form of a call added.
    *
    * `DELEGATECALL` is not in the original table either -- it entered through
    * EIP-7 -- so a case exercising it has to add it as well, or the byte halts
    * as undefined and the case reports transient storage unwritten for a reason
    * that has nothing to do with transient storage.
    */
  private val tableWithDelegation: OpcodeTable =
    table.adding(Operation(Opcode.DelegateCall, Cost.Computed))

  /** A `DELEGATECALL` to `target`: six operands, no value. */
  private def delegating(target: Address, gas: Int): Seq[Int] =
    push1(0x00) ++ push1(0x00) ++ push1(0x00) ++ push1(0x00) ++
      push20(target) ++ Seq(0x61, (gas >> 8) & 0xff, gas & 0xff) :+ 0xf4

  private def runIn(
      environment: Environment,
      gas: BigInt,
      program: Seq[Int],
      message: Message = EvmFixtures.message(transfersValue = true)
  ): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(message, Code(Bytes.fromArray(program.map(_.toByte).toArray)), gas)
    (frame, Interpreter.run(frame, environment))

  private def environmentHolding(codes: (Address, Seq[Int])*): Environment =
    val world = new EvmFixtures.MapWorldState
    codes.foreach((address, program) => world.codes(address) = EvmFixtures.bytesOf(hex(program)))
    EvmFixtures.environment(world, withTable = table)

  "TLOAD" should "answer zero for a slot nothing has written" in {
    val (frame, _) = runIn(EvmFixtures.environment(withTable = table), 10000, transientlyLoading)
    assert(frame.stack.peek(0) == Right(Word.Zero), "a keyspace with no committed values starts empty everywhere")
  }

  it should "answer what TSTORE wrote at the same slot" in {
    val (frame, _) =
      runIn(EvmFixtures.environment(withTable = table), 10000, transientlyStoring(0x2a) ++ transientlyLoading)
    assert(frame.stack.peek(0) == Right(EvmFixtures.word(0x2a)), "the pair addresses one keyspace")
  }

  it should "cost the figure the document names by reference" in {
    val environment = EvmFixtures.environment(withTable = table)
    val (frame, _) = runIn(environment, 10000, transientlyLoading)
    assert(
      frame.gasLeft == BigInt(10000) - schedule.veryLow - schedule.warmAccess,
      "one push at the very-low tier and the read at the warm-access figure"
    )
  }

  it should "not answer what persistent storage holds at the same slot" in {
    val world = new EvmFixtures.MapWorldState
    world.setStorage(runner, slot, EvmFixtures.word(0x2a))
    val (frame, _) = runIn(EvmFixtures.environment(world, withTable = table), 10000, transientlyLoading)
    assert(frame.stack.peek(0) == Right(Word.Zero), "two keyspaces sharing an address and a slot number are still two")
  }

  it should "be permitted where the invocation may not change state" in {
    val (frame, _) = runIn(
      EvmFixtures.environment(withTable = table),
      10000,
      transientlyLoading,
      EvmFixtures.message(transfersValue = false, isStatic = true)
    )
    assert(
      frame.stack.peek(0) == Right(Word.Zero),
      "\"TLOAD is allowed within the context of a STATICCALL\", so it pushes rather than halting"
    )
  }

  "TSTORE" should "cost the figure the document names by reference" in {
    val environment = EvmFixtures.environment(withTable = table)
    val (frame, _) = runIn(environment, 10000, transientlyStoring(0x2a))
    assert(
      frame.gasLeft == BigInt(10000) - schedule.veryLow * 2 - schedule.warmAccess,
      "two pushes at the very-low tier and the write at the warm-access figure"
    )
  }

  it should "reach no persistent storage at all" in {
    val environment = EvmFixtures.environment(withTable = table)
    val _ = runIn(environment, 10000, transientlyStoring(0x2a))
    assert(
      environment.world.storageAt(runner, slot) == Word.Zero,
      "a write that reached an account's storage would survive the transaction"
    )
  }

  it should "halt where the invocation may not change state" in {
    val (_, outcome) = runIn(
      EvmFixtures.environment(withTable = table),
      10000,
      transientlyStoring(0x2a),
      EvmFixtures.message(transfersValue = false, isStatic = true)
    )
    assert(
      outcome == Right(Outcome.Halted(Halt.WriteInStaticContext)),
      "\"it will result in an exception instead of performing the modification\""
    )
  }

  it should "run with less gas left than a store's stipend, which a store refuses" in {
    // "The behavior of the opcodes for transient storage differs from the
    // opcodes for storage in that TSTORE does not require gasleft, as defined
    // in EIP-2200, to be less than or equal to the gas stipend." A build that
    // reused the storage branch would inherit that sentry, and this is what
    // separates the two -- the control beneath it runs SSTORE at the same gas
    // under rules that carry the sentry, and that one must refuse.
    val environment = EvmFixtures.environment(withTable = table)
    val gas = schedule.veryLow * 2 + schedule.warmAccess + schedule.callStipend - 1
    val (frame, outcome) = runIn(environment, gas, transientlyStoring(0x2a))
    assert(
      outcome == Right(Outcome.Stopped(frame.gasLeft, Bytes.Empty)) &&
        environment.world.transientStorageAt(runner, slot) == EvmFixtures.word(0x2a),
      "no stipend sentry stands in front of this operation"
    )
  }

  it should "be refused at that same gas by a store under rules carrying the sentry" in {
    val sentried = EvmFixtures.rules.copy(table = table, storageMetering = StorageMetering.NetWithSentry)
    val environment = EvmFixtures.environmentUnder(sentried)
    val gas = schedule.veryLow * 2 + schedule.warmAccess + schedule.callStipend - 1
    val (_, outcome) = runIn(environment, gas, push1(0x2a) ++ push1(0x01) :+ 0x55)
    assert(
      outcome == Right(Outcome.Halted(Halt.OutOfGas)),
      "the control: without it, the case above would pass against a build whose sentry never fires"
    )
  }

  "two accounts" should "hold separate transient storage at the same slot" in {
    val environment = environmentHolding(other -> (transientlyStoring(0x2a) :+ 0x00))
    val _ = runIn(environment, 100000, calling(other, 50000) ++ transientlyLoading)
    assert(
      environment.world.transientStorageAt(runner, slot) == Word.Zero &&
        environment.world.transientStorageAt(other, slot) == EvmFixtures.word(0x2a),
      "\"transient storage is private to the contract that owns it, in the same way as persistent storage\""
    )
  }

  "a write made before a nested invocation" should "be visible inside it" in {
    // The callee writes what it read into its own persistent storage, so what
    // it saw is readable after the fact. A per-frame store would show zero.
    val environment = environmentHolding(other -> (recordingWhatItReads :+ 0x00))
    val _ = runIn(environment, 200000, transientlyStoring(0x2a) ++ push20(other) ++ Seq(0x50) ++ calling(other, 100000))
    assert(
      environment.world.transientStorageAt(runner, slot) == EvmFixtures.word(0x2a),
      "the caller's own write is still there once the callee has returned"
    )
  }

  "a write made by a nested invocation that stopped" should "be visible to its caller" in {
    val environment = environmentHolding(other -> (transientlyStoring(0x2a) :+ 0x00))
    val _ = runIn(environment, 200000, calling(other, 100000))
    assert(
      environment.world.transientStorageAt(other, slot) == EvmFixtures.word(0x2a),
      "\"all the frames access the same transient store\", so a callee's write outlives its frame"
    )
  }

  "a write made by a nested invocation that halted" should "be dropped" in {
    val environment = environmentHolding(other -> (transientlyStoring(0x2a) :+ 0x0c))
    val _ = runIn(environment, 200000, calling(other, 100000))
    assert(
      environment.world.transientStorageAt(other, slot) == Word.Zero,
      "\"if a frame reverts, all writes to transient storage that took place between entry to the frame and " +
        "the return are reverted\""
    )
  }

  "a write made by a nested invocation that reverted" should "be dropped" in {
    val reverting = transientlyStoring(0x2a) ++ push1(0x00) ++ push1(0x00) :+ 0xfd
    val environment = environmentHolding(other -> reverting)
    val _ = runIn(environment, 200000, calling(other, 100000))
    assert(
      environment.world.transientStorageAt(other, slot) == Word.Zero,
      "a revert undoes the same writes a halt does, which is the case the document states in terms"
    )
  }

  "a write made under DELEGATECALL" should "land under the account that issued it" in {
    val world = new EvmFixtures.MapWorldState
    world.codes(other) = EvmFixtures.bytesOf(hex(transientlyStoring(0x2a) :+ 0x00))
    val environment = EvmFixtures.environment(world, withTable = tableWithDelegation)
    val _ = runIn(environment, 200000, delegating(other, 60000))
    assert(
      environment.world.transientStorageAt(runner, slot) == EvmFixtures.word(0x2a) &&
        environment.world.transientStorageAt(other, slot) == Word.Zero,
      "\"the owning contract of the transient storage is the contract that issued DELEGATECALL\""
    )
  }

  "a chain that has not adopted the proposal" should "run no operation at either byte" in {
    val plain = EvmFixtures.environment()
    val loading = Interpreter.run(
      new Frame(EvmFixtures.message(transfersValue = false), Code(EvmFixtures.bytesOf("5c")), BigInt(1000)),
      plain
    )
    val storing = Interpreter.run(
      new Frame(EvmFixtures.message(transfersValue = false), Code(EvmFixtures.bytesOf("5d")), BigInt(1000)),
      plain
    )
    assert(
      loading == Right(Outcome.Halted(Halt.InvalidOpcode(0x5c))) &&
        storing == Right(Outcome.Halted(Halt.InvalidOpcode(0x5d))),
      "both bytes are undefined until a rule set adds them, and an undefined byte halts"
    )
  }
