package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.scalatest.flatspec.AnyFlatSpec

/** One invocation start to finish: what it is given, what it leaves behind, and
  * what a nested one hands back.
  *
  * The sibling [[InterpreterSpec]] covers the loop and the operations that stay
  * inside a single frame; everything here reaches world state, so a wrong answer
  * is a wrong state root rather than a wrong stack.
  *
  * Expected values are `ethereum/execution-specs` at `ccaaaba58` --
  * `frontier/vm/interpreter.py` for the snapshot, the touch and the transfer,
  * `frontier/vm/instructions/system.py` for the operations, and
  * `frontier/vm/gas.py` for `calculate_message_call_gas` -- cross-read against
  * `ethereum/go-ethereum` at `6bb0588ad` for every price.
  */
class InvocationSpec extends AnyFlatSpec:

  private val caller = EvmFixtures.address(0x11)
  private val runner = EvmFixtures.address(0x22)
  private val other = EvmFixtures.address(0x33)

  /** Stops at once, having done nothing. */
  private val stopping = Seq(0x00)

  /** Writes 42 to slot 1 and stops. */
  private val storing = Seq(0x60, 0x2a, 0x60, 0x01, 0x55, 0x00)

  /** Meets a byte that names no operation, so it halts exceptionally. */
  private val halting = Seq(0x0c)

  /** Writes 42 to slot 1 and then halts, so the write has to be dropped. */
  private val storingThenHalting = Seq(0x60, 0x2a, 0x60, 0x01, 0x55, 0x0c)

  /** Hands back one byte, 0x2a. */
  private val returning = Seq(0x60, 0x2a, 0x60, 0x00, 0x53, 0x60, 0x01, 0x60, 0x00, 0xf3)

  /** Emits one entry with no topics and no data. */
  private val emitting = Seq(0x60, 0x00, 0x60, 0x00, 0xa0)

  /** Emits one entry and then halts. */
  private val emittingThenHalting = Seq(0x60, 0x00, 0x60, 0x00, 0xa0, 0x0c)

  /** Hands back 0x2a as the code to deploy: ten bytes, so a creation naming a
    * size of ten from offset zero deploys exactly this.
    */
  private val deploying = "602a60005360016000f3"

  private def push1(value: Int): Seq[Int] = Seq(0x60, value & 0xff)

  private def push2(value: Int): Seq[Int] = Seq(0x61, (value >> 8) & 0xff, value & 0xff)

  private def push20(address: Address): Seq[Int] =
    0x73 +: (0 until Address.Width).map(index => address.toBytes(index) & 0xff)

  private def push32(hex: String): Seq[Int] =
    val padded = hex + "0" * (64 - hex.length)
    0x7f +: (0 until 32).map(index => Integer.parseInt(padded.substring(index * 2, index * 2 + 2), 16))

  /** A `CALL` or `CALLCODE`, with its seven operands pushed in the order the
    * operation takes them off the stack.
    */
  private def calling(
      operation: Int,
      target: Address,
      gas: Int,
      value: Int = 0,
      inputAt: Int = 0,
      inputSize: Int = 0,
      answerAt: Int = 0,
      answerRoom: Int = 0
  ): Seq[Int] =
    push1(answerRoom) ++ push1(answerAt) ++ push1(inputSize) ++ push1(inputAt) ++
      push1(value) ++ push20(target) ++ push2(gas) :+ operation

  /** Places `hex` at the start of memory and creates from it. */
  private def creating(hex: String, endowment: Int = 0): Seq[Int] =
    push32(hex) ++ push1(0x00) ++ Seq(0x52) ++
      push1(hex.length / 2) ++ push1(0x00) ++ push1(endowment) :+ 0xf0

  private def destroying(beneficiary: Address): Seq[Int] = push20(beneficiary) :+ 0xff

  private def world(): EvmFixtures.MapWorldState = new EvmFixtures.MapWorldState

  private def codeOf(hex: String): Bytes = EvmFixtures.bytesOf(hex)

  private def hex(program: Seq[Int]): String = program.map(byte => f"$byte%02x").mkString

  private def runIn(
      environment: Environment,
      gas: Int,
      program: Seq[Int],
      message: Message = EvmFixtures.message()
  ): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(message, Code(Bytes.fromArray(program.map(_.toByte).toArray)), BigInt(gas))
    (frame, Interpreter.run(frame, environment))

  /** The rules with EIP-7 applied, which is the only way this byte runs. */
  private def admitting: OpcodeTable = ChainRules.Baseline.applying(Proposals.delegateCall).table

  /** Six operands, not seven: this form takes no value off the stack. */
  private def delegating(target: Address, gas: Int): Seq[Int] =
    push1(0) ++ push1(0) ++ push1(0) ++ push1(0) ++ push20(target) ++ push2(gas) :+ 0xf4

  /** Writes whoever called it into slot zero. */
  private val recordingCaller: Seq[Int] = Seq(0x33) ++ push1(0) ++ Seq(0x55, 0x00)

  /** Writes the value it was invoked with into slot zero. */
  private val recordingValue: Seq[Int] = Seq(0x34) ++ push1(0) ++ Seq(0x55, 0x00)

  private def recorded(program: Seq[Int], borrowed: Seq[Int], value: Int = 0): Word =
    val state = world()
    state.setCode(other, Bytes.fromArray(borrowed.map(_.toByte).toArray))
    // The OUTER invocation moves its own value from its own caller before any of
    // this runs, so that account is the one that has to hold it. Funding the
    // account under test instead fails in the interpreter rather than in the
    // assertion, which reads as a defect and is a fixture.
    state.setBalance(caller, EvmFixtures.word(1000))
    val environment = EvmFixtures.environment(state, withTable = admitting)
    val _ = runIn(
      environment,
      200000,
      program,
      EvmFixtures.message(caller = caller, currentTarget = runner, value = EvmFixtures.word(value))
    )
    environment.world.storageAt(runner, Word.Zero)

  // ── What an invocation is given, and what it leaves behind ───────────────

  "an invocation" should "bring the account it runs as into being" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 100, stopping)
    assert(
      environment.world.accountExists(runner),
      "an ordinary transfer to an address nothing has used leaves an account behind at this fork"
    )
  }

  it should "leave no account behind where it halts" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 100, halting)
    assert(!environment.world.accountExists(runner), "the snapshot is taken before the account is brought into being")
  }

  it should "keep the storage it wrote where it stops normally" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 30000, storing)
    assert(environment.world.storageAt(runner, EvmFixtures.word(1)) == EvmFixtures.word(42), "nothing undoes a success")
  }

  it should "drop the storage it wrote where it halts" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 30000, storingThenHalting)
    assert(
      environment.world.storageAt(runner, EvmFixtures.word(1)) == Word.Zero,
      "an invocation that halts leaves no trace, and a surviving write is a wrong state root"
    )
  }

  "an invocation carrying value" should "move it from the caller" in {
    val funded = world()
    funded.balances(caller) = EvmFixtures.word(1000)
    val environment = EvmFixtures.environment(funded)
    val _ = runIn(environment, 100, stopping, EvmFixtures.message(value = EvmFixtures.word(40)))
    assert(environment.world.balanceOf(caller) == EvmFixtures.word(960), "the caller pays before any code runs")
  }

  it should "move it to the account it runs as" in {
    val funded = world()
    funded.balances(caller) = EvmFixtures.word(1000)
    val environment = EvmFixtures.environment(funded)
    val _ = runIn(environment, 100, stopping, EvmFixtures.message(value = EvmFixtures.word(40)))
    assert(environment.world.balanceOf(runner) == EvmFixtures.word(40), "the value arrives before the code sees it")
  }

  it should "give it back where the invocation halts" in {
    val funded = world()
    funded.balances(caller) = EvmFixtures.word(1000)
    val environment = EvmFixtures.environment(funded)
    val _ = runIn(environment, 100, halting, EvmFixtures.message(value = EvmFixtures.word(40)))
    assert(environment.world.balanceOf(caller) == EvmFixtures.word(1000), "a transfer is undone with everything else")
  }

  "an invocation nested past the limit" should "halt rather than run" in {
    val nested = EvmFixtures.message().copy(depth = Stack.Limit + 1)
    val (_, outcome) = runIn(EvmFixtures.environment(), 100, stopping, nested)
    assert(
      outcome == Right(Outcome.Halted(Halt.StackDepthLimit)),
      "the operations that nest refuse first, so this is the guard behind them rather than the one they use"
    )
  }

  // ── A nested invocation of another account's code ────────────────────────

  "CALL" should "run the code at the account named" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(storing))
    val environment = EvmFixtures.environment(holder)
    val _ = runIn(environment, 100000, calling(0xf1, other, 40000))
    assert(
      environment.world.storageAt(other, EvmFixtures.word(1)) == EvmFixtures.word(42),
      "the callee writes under itself, which is what separates this from CALLCODE"
    )
  }

  it should "answer one where the callee stopped normally" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000))
    assert(frame.stack.peek(0) == Right(Word.One), "a caller distinguishes success from failure and nothing else")
  }

  it should "answer zero where the callee halted" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(halting))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000))
    assert(frame.stack.peek(0) == Right(Word.Zero), "the zero is the only thing the caller learns of the failure")
  }

  it should "drop the storage a callee that halted wrote" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(storingThenHalting))
    val environment = EvmFixtures.environment(holder)
    val _ = runIn(environment, 100000, calling(0xf1, other, 40000))
    assert(environment.world.storageAt(other, EvmFixtures.word(1)) == Word.Zero, "a failed nesting leaves no trace")
  }

  it should "copy as much of the answer as the caller made room for" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(returning))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000, answerRoom = 1))
    assert(frame.memory.read(0, 1) == codeOf("2a"), "the answer lands where the caller said and it had already paid")
  }

  it should "copy nothing where the caller made no room" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(returning))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000, answerRoom = 0))
    assert(frame.memory.size == 0, "a caller that asked for nothing is given nothing, however long the answer")
  }

  it should "take the settled price and the whole of the gas it forwards" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000))
    assert(
      frame.gasLeft == BigInt(100000 - 7 * 3 - 40 - 1000 + 1000),
      "seven pushes at 3 and a settled 40, with the forwarded gas charged and the unspent part handed back"
    )
  }

  it should "cost more where the account named has never existed" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 100000, calling(0xf1, other, 1000))
    assert(
      frame.gasLeft == BigInt(100000 - 7 * 3 - 40 - 25000 - 1000 + 1000),
      "an account this state has never held is brought into being by the call, and that is charged for"
    )
  }

  it should "cost the value surcharge where anything is sent" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    holder.balances(runner) = EvmFixtures.word(50)
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000, value = 40))
    assert(
      frame.gasLeft == BigInt(100000 - 7 * 3 - 40 - 9000 - 1000 + 1000 + 2300),
      "sending anything costs 9000, of which 2300 is given to the callee and comes back unspent here"
    )
  }

  it should "hand the forwarded gas back where the caller cannot cover the value" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000, value = 40))
    assert(
      frame.gasLeft == BigInt(100000 - 7 * 3 - 40 - 9000 - 1000 + 1000 + 2300),
      "nothing ran, so the forwarded gas comes back with the stipend, leaving 6700 of the 9000 actually spent"
    )
  }

  it should "answer zero where the caller cannot cover the value" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000, value = 40))
    assert(frame.stack.peek(0) == Right(Word.Zero), "a call that could not be made reads as one that failed")
  }

  it should "return nothing from a callee that halted" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(halting))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000))
    assert(
      frame.gasLeft == BigInt(100000 - 7 * 3 - 40 - 1000),
      "there is no cheap failure at this fork, so a halted nesting keeps everything it was given"
    )
  }

  it should "take up the entries a callee that stopped emitted" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(emitting))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 4000))
    assert(frame.logs.map(_.address) == Vector(other), "an entry emitted by a nested invocation is the caller's too")
  }

  it should "discard the entries a callee that halted emitted" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(emittingThenHalting))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 4000))
    assert(frame.logs.isEmpty, "a failed invocation's entries go with the state it wrote")
  }

  "CALLCODE" should "write under the account that made the call" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(storing))
    val environment = EvmFixtures.environment(holder)
    val _ = runIn(environment, 100000, calling(0xf2, other, 40000))
    assert(
      environment.world.storageAt(runner, EvmFixtures.word(1)) == EvmFixtures.word(42),
      "the code is borrowed and the storage is not"
    )
  }

  it should "leave the named account's storage alone" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(storing))
    val environment = EvmFixtures.environment(holder)
    val _ = runIn(environment, 100000, calling(0xf2, other, 40000))
    assert(
      environment.world.storageAt(other, EvmFixtures.word(1)) == Word.Zero,
      "the lender's own storage is untouched"
    )
  }

  it should "cost nothing for an account never held, since it runs as itself" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf2, other, 1000))
    assert(
      frame.gasLeft == BigInt(100000 - 7 * 3 - 40 - 1000 + 1000),
      "the destination is the account already running, so it exists and no surcharge applies"
    )
  }

  // ── A nested invocation that deploys ─────────────────────────────────────

  "CREATE" should "deploy at the address named by its creator and count" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 200000, creating(deploying))
    assert(
      environment.world.codeOf(ContractAddress.of(runner, UInt64.Zero)) == codeOf("2a"),
      "what the deployment handed back is what the new account carries"
    )
  }

  it should "answer with the address it deployed at" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 200000, creating(deploying))
    assert(
      frame.stack.peek(0) == Right(Word.fromBytes(Bytes.fromIArray(ContractAddress.of(runner, UInt64.Zero).toBytes))),
      "the address is settled before the deployment runs, so it can be answered whatever the deployment did"
    )
  }

  it should "raise the creator's count" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 200000, creating(deploying))
    assert(environment.world.nonceOf(runner) == UInt64.fromBits(1L), "two creations by one account cannot collide")
  }

  it should "move the endowment to the account created" in {
    val funded = world()
    funded.balances(runner) = EvmFixtures.word(500)
    val environment = EvmFixtures.environment(funded)
    val _ = runIn(environment, 200000, creating(deploying, endowment = 40))
    assert(
      environment.world.balanceOf(ContractAddress.of(runner, UInt64.Zero)) == EvmFixtures.word(40),
      "the endowment arrives before the deployment runs"
    )
  }

  it should "answer zero where the deployment halted" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 200000, creating(hex(halting)))
    assert(frame.stack.peek(0) == Right(Word.Zero), "a deployment that halted deployed nothing and says so")
  }

  it should "leave no account behind where the deployment halted" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 200000, creating(hex(halting)))
    assert(
      !environment.world.accountExists(ContractAddress.of(runner, UInt64.Zero)),
      "the account the deployment was given goes with the rest of what it wrote"
    )
  }

  it should "keep the raised count where the deployment halted" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 200000, creating(hex(halting)))
    assert(
      environment.world.nonceOf(runner) == UInt64.fromBits(1L),
      "the count rises before the snapshot, so a failed deployment still consumes an address"
    )
  }

  it should "hand the gas back where the creator cannot cover the endowment" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 200000, creating(deploying, endowment = 40))
    assert(
      frame.gasLeft == BigInt(200000 - 3 - 3 - 3 - 3 - 3 - 3 - 3 - 32000),
      "nothing ran, so only the pushes, the word of memory and the settled 32000 were spent"
    )
  }

  it should "deploy nothing where the deposit is unaffordable" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 32200, creating(deploying))
    assert(
      environment.world.codeOf(ContractAddress.of(runner, UInt64.Zero)) == Bytes.Empty,
      "at this fork a deployment that cannot pay to store its code keeps the gas and stores nothing"
    )
  }

  it should "still answer with the address where the deposit is unaffordable" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 32200, creating(deploying))
    assert(
      frame.stack.peek(0) == Right(Word.fromBytes(Bytes.fromIArray(ContractAddress.of(runner, UInt64.Zero).toBytes))),
      "the specification records no error, so the creating operation is told the address as though code were stored"
    )
  }

  it should "keep the gas where the deposit is unaffordable" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 32200, creating(deploying))
    assert(
      frame.gasLeft == BigInt(32200 - 12 - 9 - 32000 - 18),
      "the charge that could not be made deducted nothing, so what the deployment did not spend comes back"
    )
  }

  it should "answer zero where the destination already carries code" in {
    val occupied = world()
    occupied.codes(ContractAddress.of(runner, UInt64.Zero)) = codeOf("6001")
    val (frame, _) = runIn(EvmFixtures.environment(occupied), 200000, creating(deploying))
    assert(frame.stack.peek(0) == Right(Word.Zero), "an address already in use is not free to deploy over")
  }

  it should "consume everything forwarded where the destination is not free" in {
    val occupied = world()
    occupied.codes(ContractAddress.of(runner, UInt64.Zero)) = codeOf("6001")
    val (frame, _) = runIn(EvmFixtures.environment(occupied), 200000, creating(deploying))
    assert(
      frame.gasLeft == BigInt(0),
      "unlike the refusals that precede it this one keeps the gas, which is the specification's own asymmetry"
    )
  }

  it should "still raise the creator's count where the destination is not free" in {
    val occupied = world()
    occupied.codes(ContractAddress.of(runner, UInt64.Zero)) = codeOf("6001")
    val environment = EvmFixtures.environment(occupied)
    val _ = runIn(environment, 200000, creating(deploying))
    assert(environment.world.nonceOf(runner) == UInt64.fromBits(1L), "the address is consumed either way")
  }

  it should "answer zero where the destination holds storage" in {
    val occupied = world()
    occupied.slots((ContractAddress.of(runner, UInt64.Zero), EvmFixtures.word(1))) = EvmFixtures.word(7)
    val (frame, _) = runIn(EvmFixtures.environment(occupied), 200000, creating(deploying))
    assert(
      frame.stack.peek(0) == Right(Word.Zero),
      "the condition the two authorities disagree on, followed as the specification states it"
    )
  }

  // ── Ending an invocation and giving its balance away ─────────────────────

  "SELFDESTRUCT" should "give the balance to the beneficiary" in {
    val funded = world()
    funded.balances(runner) = EvmFixtures.word(500)
    val environment = EvmFixtures.environment(funded)
    val _ = runIn(environment, 100, destroying(other))
    assert(environment.world.balanceOf(other) == EvmFixtures.word(500), "the whole balance moves, not a part of it")
  }

  it should "leave nothing at the account destroyed" in {
    val funded = world()
    funded.balances(runner) = EvmFixtures.word(500)
    val environment = EvmFixtures.environment(funded)
    val _ = runIn(environment, 100, destroying(other))
    assert(environment.world.balanceOf(runner) == Word.Zero, "the balance is zeroed even though the account remains")
  }

  it should "burn the balance where the account names itself" in {
    val funded = world()
    funded.balances(runner) = EvmFixtures.word(500)
    val environment = EvmFixtures.environment(funded)
    val _ = runIn(environment, 100, destroying(runner))
    assert(
      environment.world.balanceOf(runner) == Word.Zero,
      "both balances are read before either is written, so naming yourself is a burn"
    )
  }

  it should "register the account rather than remove it" in {
    val environment = EvmFixtures.environment()
    val (frame, _) = runIn(environment, 100, destroying(other))
    assert(
      frame.accountsToDelete == Set(runner) && environment.world.accountExists(runner),
      "a registered account goes on answering reads until the transaction ends"
    )
  }

  it should "earn its refund" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 100, destroying(other))
    assert(frame.refundCounter == BigInt(24000), "the refund is counted on the frame and never added to its gas")
  }

  it should "end the invocation" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 100, destroying(other) ++ Seq(0x60, 0x01))
    assert(frame.stack.isEmpty, "the PUSH after it never ran")
  }

  it should "cost nothing" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 100, destroying(other))
    assert(frame.gasLeft == BigInt(100 - 3), "the push costs 3 and the operation itself is free at this fork")
  }

  // A borrowed run destroys the account it was borrowed by, and then that
  // account destroys itself again -- the one shape at this fork in which one
  // account is registered twice.
  it should "earn its refund once where the account is already registered" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(destroying(other)))
    val program = calling(0xf2, other, 40000) ++ destroying(other)
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, program)
    assert(frame.refundCounter == BigInt(24000), "the refund is paid once per account per transaction")
  }

  it should "earn a refund for each different account registered" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(destroying(other)))
    val program = calling(0xf1, other, 40000) ++ destroying(other)
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, program)
    assert(frame.refundCounter == BigInt(48000), "two accounts registered is two refunds, which is the control")
  }

  // ── A nested invocation that borrows identity as well as code ────────────

  "a delegated call" should "leave its code unable to tell it was not called directly" in {
    // The property stated as the specification states it, rather than against a
    // literal address: the same code, reached the two ways, must record the same
    // caller. Comparing to a written-out address would test the same thing and
    // would also test this suite's ability to spell one.
    val directly = recorded(recordingCaller, Seq.empty)
    val delegated = recorded(delegating(other, 40000), recordingCaller)
    assert(directly == delegated && directly != Word.Zero, "direct=" + directly + " delegated=" + delegated)
  }

  it should "differ from a call that borrows code without borrowing identity" in {
    // The contrast that makes the case above mean something. CALLCODE runs the
    // same borrowed code in the same storage and names the borrower as caller,
    // so a form that confused the two would pass the case above and fail here.
    val delegated = recorded(delegating(other, 40000), recordingCaller)
    val borrowed = recorded(calling(0xf2, other, 40000), recordingCaller)
    assert(delegated != borrowed, "delegated=" + delegated + " callcode=" + borrowed)
  }

  it should "read the value its own caller was invoked with" in
    assert(recorded(delegating(other, 40000), recordingValue, value = 7) == EvmFixtures.word(7))

  it should "read zero where a borrowing call would have pushed its own" in
    // CALLCODE takes a value off the stack and this one is given none, so the
    // inherited seven above is the delta rather than an accident of the fixture.
    assert(recorded(calling(0xf2, other, 40000), recordingValue, value = 7) == Word.Zero)

  it should "write to the storage of the account that delegated" in
    assert(recorded(delegating(other, 40000), recordingCaller) != Word.Zero, "slot zero of the delegating account")

  it should "move no value, whatever value it carries" in {
    val state = world()
    state.setCode(other, Bytes.fromArray(recordingValue.map(_.toByte).toArray))
    state.setBalance(caller, EvmFixtures.word(1000))
    val environment = EvmFixtures.environment(state, withTable = admitting)
    val _ = runIn(
      environment,
      200000,
      delegating(other, 40000),
      EvmFixtures.message(caller = caller, currentTarget = runner, value = EvmFixtures.word(7))
    )
    // The account whose code ran is the one that must be untouched. The outer
    // invocation's own transfer of seven to the delegating account is a separate
    // and legitimate movement, so asserting on that account would confuse the
    // two and could not fail for the reason this test exists.
    assert(
      environment.world.balanceOf(other) == Word.Zero,
      "the account whose code was borrowed received value: " + environment.world.balanceOf(other)
    )
  }

  it should "run nothing where the proposal that adds it has not been applied" in {
    val state = world()
    state.setCode(other, Bytes.fromArray(recordingCaller.map(_.toByte).toArray))
    val (_, outcome) = runIn(EvmFixtures.environment(state), 200000, delegating(other, 40000))
    assert(
      outcome match
        case Right(Outcome.Halted(_)) => true
        case _                        => false,
      "the baseline names no operation at this byte, so it must halt as any undefined byte does: " + outcome
    )
  }
