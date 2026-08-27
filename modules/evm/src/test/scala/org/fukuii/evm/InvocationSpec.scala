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

  /** Runs one invocation over a program this spec hands it, rather than over
    * code world state holds.
    *
    * ==So the account it runs as holds no code, and a chain never produces
    * that==
    *
    * A frame built here names the account it runs as and takes its program as an
    * argument, so `world.codeOf` of that account stays empty and the account is
    * DEAD by EIP-161's test -- zero count, no code, zero balance -- while its own
    * code is running. On a chain the two cannot come apart, and
    * `Interpreter.newAccountSurcharge` states why.
    *
    * **Two cases below stand on that difference.** Both run under rules levying
    * the surcharge for bringing a destination into being on an ABSENT one, where
    * the account being run as exists and so is not charged. Each name is on its
    * own line here so that a reader who reaches one of them by searching for it
    * reaches this too:
    *
    * "cost nothing for an account never held, since it runs as itself"
    *
    * "be capped like the rest of the call family"
    *
    * Run either under rules levying that surcharge on a DEAD destination and it
    * would be charged one no chain levies, because here that account is dead and
    * on a chain it would not be. Giving the account its code in state first is
    * what would make such a case a measurement rather than an artifact of this
    * helper.
    */
  private def runIn(
      environment: Environment,
      gas: BigInt,
      program: Seq[Int],
      message: Message = EvmFixtures.message(transfersValue = true)
  ): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(message, Code(Bytes.fromArray(program.map(_.toByte).toArray)), gas)
    (frame, Interpreter.run(frame, environment))

  private val schedule = EvmFixtures.schedule

  /** What the deployment inside [[deploying]] costs to run: four pushes and a
    * store at the very-low tier, plus the one word of memory it touches.
    */
  private val deploymentCost: BigInt = schedule.veryLow * 5 + GasCost.MemoryPerWord + schedule.zero

  /** What reaching the creating operation costs: five pushes and a store at the
    * very-low tier, plus one word of memory.
    */
  private val creationPreamble: BigInt = schedule.veryLow * 6 + GasCost.MemoryPerWord

  /** Gas enough to run the deployment and fall exactly one unit short of storing
    * the single byte it returns.
    *
    * Derived rather than written down, so that it stays one short whatever the
    * schedule prices are. A literal would be a shortfall under one schedule and
    * an affordable deposit under another, and the test would then pass while
    * exercising the other branch.
    */
  private val oneShortOfDeposit: BigInt =
    creationPreamble + schedule.createBase + deploymentCost + schedule.codeDepositPerByte - 1

  /** The rules with the deposit rule settled strict.
    *
    * Written as a flag rather than by naming the proposal that introduced it:
    * this spec is about what the MACHINE does with the flag, and which document
    * supplied it is a chain configuration's business.
    */
  private def strictDeposit: EvmRules = EvmFixtures.rules.copy(codeDepositMustSucceed = true)

  /** A bound to stand a deployment on either side of.
    *
    * Any figure would do -- what the machine owes is the comparison, and which
    * number a network bounds at is that network's -- so this is deliberately not
    * the one any network uses. [[EvmFixtures.schedule]] states the same doctrine
    * for prices, and it is the same reason: a machine spec asserting a network's
    * figure passes for an interpreter that reads the rules and for one with the
    * figure compiled into it.
    */
  private val Bound: Int = 900

  /** Deployment code handing back `size` bytes of zeros: a size, an offset, and
    * a return over memory nothing wrote, which expands zero-filled.
    */
  private def returningBytes(size: Int): String = hex(push2(size) ++ push1(0x00) :+ 0xf3)

  /** The rules with a bound on deployed code and nothing else moved.
    *
    * The deposit rule stays permissive, which is the combination worth running:
    * a bound folded into the deposit charge, or checked and then routed through
    * that flag, would leave the account behind holding nothing and hand the
    * unspent gas back rather than halting.
    */
  private def bounding(limit: Int): EvmRules = EvmFixtures.rules.copy(maxCodeSize = Some(limit))

  /** Gas enough to run either deployment the bound is tested with and pay to
    * store it, with room on every side.
    *
    * Derived from the dominant term rather than written down, since the deposit
    * is what makes the figure large and it moves with the schedule. **That the
    * slack is enough is established by the unbounded case rather than by this
    * expression** -- a budget falling short would fail that one, which is what
    * makes the refusals beside it attributable to the bound and not to the gas.
    */
  private val roomForTheBound: BigInt = schedule.codeDepositPerByte * (Bound + 1) + BigInt(100000)

  /** Gas enough to reach the deposit for an over-long deployment and not enough
    * to pay it.
    *
    * The window between those two is wide and this is not derived to either
    * edge: the case running the same figure against rules that bound nothing
    * establishes that the deposit is genuinely unaffordable, so a figure that
    * drifted out of the window would fail that one rather than let this one pass
    * for the wrong reason.
    */
  private val shortOfTheBoundedDeposit: BigInt = roomForTheBound - schedule.codeDepositPerByte * Bound

  /** The table with the delegating byte in it, which is the only way it runs. */
  private def admitting: OpcodeTable =
    EvmFixtures.rules.table.adding(Operation(Opcode.DelegateCall, Cost.Computed))

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
      EvmFixtures.message(
        caller = caller,
        currentTarget = runner,
        value = EvmFixtures.word(value),
        transfersValue = true
      )
    )
    environment.world.storageAt(runner, Word.Zero)

  /** How much of what remains is kept back from a nested invocation under the
    * capped rule below. The figure every network that caps has chosen.
    *
    * Stated here rather than read from the rule, which holds no such value: the
    * expectations below are computed from this, so a change to what the rule
    * actually divides by fails them instead of moving them along with it.
    */
  private val Fraction: Int = 64

  /** What a capped call has available to forward, chosen as a whole multiple of
    * [[Fraction]] so the share kept back is exact rather than rounded.
    *
    * The callee stops without spending any of it, so everything forwarded comes
    * back and the frame ends holding exactly this.
    */
  private val Capped: Int = 6400

  /** Gas enough to reach a capped call with [[Capped]] left to forward.
    *
    * Derived from the schedule rather than written down: the seven operands and
    * the operation's own settled price are what stand between the frame's gas
    * and the amount under test, and both move with a reprice.
    */
  private val cappedRun: BigInt = schedule.veryLow * 7 + schedule.callBase + Capped

  /** The rules with a forwarding cap and nothing else moved, so only what is
    * forwarded differs from the uncapped run beside it.
    */
  private def capping: EvmRules =
    EvmFixtures.rules.copy(gasForwarded = GasForwarding.AllButOneSixtyFourth)

  /** What ending an invocation costs under [[charging]], and the surcharge for a
    * beneficiary this state has never held.
    *
    * Named here rather than taken from a network, because what is under test is
    * that the machine charges the two fields -- not what any chain sets them to.
    * Both differ from every other price in the schedule so a mis-read field
    * fails rather than coinciding.
    */
  private val destructionCharge: BigInt = BigInt(5051)

  private val destructionSurcharge: BigInt = BigInt(25053)

  /** The rules with ending an invocation charged for. */
  private def charging: EvmRules =
    EvmFixtures.rules.copy(
      schedule = schedule.copy(selfDestruct = destructionCharge, selfDestructNewAccount = destructionSurcharge)
    )

  /** The same cap, over a table that makes the delegating byte run at all. */
  private def cappingDelegation: EvmRules = capping.copy(table = admitting)

  /** The rules with a created account starting at a count of one.
    *
    * The figure is the only one the field uses, and unlike a price it is not a
    * quantity a network is free to pick -- the proposal raises the starting
    * value by one and no document since has moved it -- so this names one rather
    * than a distinguishable made-up number.
    */
  private def countingCreations: EvmRules = EvmFixtures.rules.copy(createdAccountNonce = UInt64.fromBits(1L))

  /** Runs a deployment directly, which is the entry point a transaction whose
    * recipient is absent reaches and the one that admits initialization code too
    * long to write through a single word of memory.
    */
  private def deployIn(
      environment: Environment,
      gas: BigInt,
      initCode: Seq[Int],
      target: Address
  ): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(
      Message(runner, target, None, Word.Zero, Bytes.Empty, transfersValue = true),
      Code(Bytes.fromArray(initCode.map(_.toByte).toArray)),
      gas
    )
    (frame, Interpreter.deploy(frame, environment))

  /** The address a creation started by `target` reaches while `target` holds
    * `count`, which is what an initialization code's own nested creation is
    * observed through.
    */
  private def grandchildOf(target: Address, count: Long): Address =
    ContractAddress.of(target, UInt64.fromBits(count))

  /** The rules levying the surcharge on a dead destination that is sent
    * something, rather than on one this state has never held.
    */
  private def chargingTheDead: EvmRules =
    EvmFixtures.rules.copy(newAccountCharge = NewAccountCharge.WhenValueReachesADeadDestination)

  /** The same reading, over the schedule that prices ending an invocation. */
  private def chargingDeadBeneficiaries: EvmRules =
    charging.copy(newAccountCharge = NewAccountCharge.WhenValueReachesADeadDestination)

  /** A world holding an account at `address` with nothing in it.
    *
    * The state the two conditions disagree about: it EXISTS, so the earlier one
    * levies nothing, and it is DEAD, so the later one levies the surcharge.
    * Every fixture reading either condition needs this shape available, because
    * an absent account and a funded one are the two both conditions agree on.
    */
  private def holdingNothingAt(address: Address): EvmFixtures.MapWorldState =
    val state = world()
    state.touch(address)
    state

  /** What a call costs before the two surcharges, with the gas it forwards
    * charged and handed back unspent by a callee that has no code to run.
    */
  private val bareCall: BigInt = schedule.veryLow * 7 + schedule.callBase

  /** What a call sending something costs beyond that, the stipend coming back
    * with the rest of what the callee did not spend.
    */
  private val sendingCall: BigInt = schedule.callValue - schedule.callStipend

  /** What ending an invocation costs before the surcharge, under [[charging]]. */
  private val bareDestruction: BigInt = schedule.veryLow + destructionCharge

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
    val _ = runIn(environment, 100, stopping, EvmFixtures.message(value = EvmFixtures.word(40), transfersValue = true))
    assert(environment.world.balanceOf(caller) == EvmFixtures.word(960), "the caller pays before any code runs")
  }

  it should "move it to the account it runs as" in {
    val funded = world()
    funded.balances(caller) = EvmFixtures.word(1000)
    val environment = EvmFixtures.environment(funded)
    val _ = runIn(environment, 100, stopping, EvmFixtures.message(value = EvmFixtures.word(40), transfersValue = true))
    assert(environment.world.balanceOf(runner) == EvmFixtures.word(40), "the value arrives before the code sees it")
  }

  it should "give it back where the invocation halts" in {
    val funded = world()
    funded.balances(caller) = EvmFixtures.word(1000)
    val environment = EvmFixtures.environment(funded)
    val _ = runIn(environment, 100, halting, EvmFixtures.message(value = EvmFixtures.word(40), transfersValue = true))
    assert(environment.world.balanceOf(caller) == EvmFixtures.word(1000), "a transfer is undone with everything else")
  }

  "an invocation nested past the limit" should "halt rather than run" in {
    val nested = EvmFixtures.message(transfersValue = true).copy(depth = Stack.Limit + 1)
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
      frame.gasLeft == BigInt(100000) - schedule.veryLow * 7 - schedule.callBase - schedule.zero,
      "seven pushes and the operation's settled price, with the forwarded gas charged and all but the callee's own spend handed back"
    )
  }

  it should "cost more where the account named has never existed" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 100000, calling(0xf1, other, 1000))
    assert(
      frame.gasLeft == BigInt(100000) - schedule.veryLow * 7 - schedule.callBase - schedule.newAccount - 1000 + 1000,
      "an account this state has never held is brought into being by the call, and that is charged for"
    )
  }

  it should "cost the value surcharge where anything is sent" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    holder.balances(runner) = EvmFixtures.word(50)
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000, value = 40))
    assert(
      frame.gasLeft ==
        BigInt(100000) - schedule.veryLow * 7 - schedule.callBase - schedule.callValue - schedule.zero +
        schedule.callStipend,
      "sending anything costs the value surcharge, of which the stipend goes to the callee and comes back unspent"
    )
  }

  it should "hand the forwarded gas back where the caller cannot cover the value" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 1000, value = 40))
    assert(
      frame.gasLeft ==
        BigInt(100000) - schedule.veryLow * 7 - schedule.callBase - schedule.callValue - 1000 + 1000 +
        schedule.callStipend,
      "nothing ran, so the forwarded gas comes back with the stipend and only the rest of the surcharge is spent"
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
      frame.gasLeft == BigInt(100000) - schedule.veryLow * 7 - schedule.callBase - 1000,
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
      frame.gasLeft == BigInt(100000) - schedule.veryLow * 7 - schedule.callBase - schedule.zero,
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
      frame.gasLeft == BigInt(200000) - creationPreamble - schedule.createBase,
      "nothing ran, so only the pushes, the word of memory and the creating operation's settled price were spent"
    )
  }

  it should "deploy nothing where the deposit is unaffordable" in {
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, oneShortOfDeposit, creating(deploying))
    assert(
      environment.world.codeOf(ContractAddress.of(runner, UInt64.Zero)) == Bytes.Empty,
      "at this fork a deployment that cannot pay to store its code keeps the gas and stores nothing"
    )
  }

  it should "still answer with the address where the deposit is unaffordable" in {
    val (frame, _) = runIn(EvmFixtures.environment(), oneShortOfDeposit, creating(deploying))
    assert(
      frame.stack.peek(0) == Right(Word.fromBytes(Bytes.fromIArray(ContractAddress.of(runner, UInt64.Zero).toBytes))),
      "the specification records no error, so the creating operation is told the address as though code were stored"
    )
  }

  it should "keep the gas where the deposit is unaffordable" in {
    val (frame, _) = runIn(EvmFixtures.environment(), oneShortOfDeposit, creating(deploying))
    assert(
      frame.gasLeft == oneShortOfDeposit - creationPreamble - schedule.createBase - deploymentCost,
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

  // ── The count an account is created with ─────────────────────────────────

  "a created account" should "start with the count the rules name" in {
    val environment = EvmFixtures.environmentUnder(countingCreations)
    val _ = runIn(environment, 200000, creating(deploying))
    assert(
      environment.world.nonceOf(ContractAddress.of(runner, UInt64.Zero)) == UInt64.fromBits(1L),
      "a count of one is what stops a created account ever again presenting the count a collision is recognized by"
    )
  }

  it should "start with no count where the rules name none" in {
    // The control. Without it the case above passes for an interpreter with the
    // figure compiled into it, which is the defect rather than the rule.
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, 200000, creating(deploying))
    assert(
      environment.world.nonceOf(ContractAddress.of(runner, UInt64.Zero)) == UInt64.Zero,
      "a network that has not raised the count creates accounts holding the count existence alone confers"
    )
  }

  it should "hold that count before its initialization code runs" in {
    // The ordering, observed through the one thing an initialization code can
    // read it with: a creation of its own resolves against the count the account
    // it runs as holds now, so the address that creation reaches is where the
    // count was written relative to this frame starting.
    val target = EvmFixtures.address(0x44)
    val environment = EvmFixtures.environmentUnder(countingCreations)
    val _ = deployIn(environment, 400000, creating(deploying) :+ 0x00, target)
    assert(
      environment.world.codeOf(grandchildOf(target, 1L)) == codeOf("2a"),
      "the count was written after the initialization code ran, so its own creation resolved against the wrong address"
    )
  }

  it should "hold no count before its initialization code runs where the rules name none" in {
    // The other half of the ordering case, and the one that makes it a
    // measurement: both addresses are reachable, so the case above has to name
    // the right one rather than the only one.
    val target = EvmFixtures.address(0x44)
    val environment = EvmFixtures.environment()
    val _ = deployIn(environment, 400000, creating(deploying) :+ 0x00, target)
    assert(
      environment.world.codeOf(grandchildOf(target, 0L)) == codeOf("2a"),
      "a creation from an account holding no count reaches that account's first address"
    )
  }

  it should "leave no account behind where its initialization code halted" in {
    // The count is written outside the snapshot the deployment's own execution
    // takes, so nothing inside that execution can undo it. A deployment that
    // halted must therefore be undone from the creating side.
    val environment = EvmFixtures.environmentUnder(countingCreations)
    val _ = runIn(environment, 200000, creating(hex(halting)))
    assert(
      !environment.world.accountExists(ContractAddress.of(runner, UInt64.Zero)),
      "a failed deployment left the account it was given holding the count it was created with"
    )
  }

  it should "leave no count behind where its initialization code halted" in {
    // The same undo read through the count rather than through existence, since
    // an implementation could drop the account's existence and keep its nonce.
    val environment = EvmFixtures.environmentUnder(countingCreations)
    val _ = runIn(environment, 200000, creating(hex(halting)))
    assert(
      environment.world.nonceOf(ContractAddress.of(runner, UInt64.Zero)) == UInt64.Zero,
      "a failed deployment is undone whole, and a surviving count is a wrong state root"
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
    assert(
      frame.refundCounter == schedule.refundSelfDestruct,
      "the refund is counted on the frame and never added to its gas"
    )
  }

  it should "end the invocation" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 100, destroying(other) ++ Seq(0x60, 0x01))
    assert(frame.stack.isEmpty, "the PUSH after it never ran")
  }

  it should "cost nothing" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 100, destroying(other))
    assert(
      frame.gasLeft == BigInt(100) - schedule.veryLow - schedule.selfDestruct - schedule.selfDestructNewAccount,
      "the push is charged at its tier and the operation at the two fields the schedule names for it"
    )
  }

  // A borrowed run destroys the account it was borrowed by, and then that
  // account destroys itself again -- the one shape at this fork in which one
  // account is registered twice.
  it should "earn its refund once where the account is already registered" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(destroying(other)))
    val program = calling(0xf2, other, 40000) ++ destroying(other)
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, program)
    assert(frame.refundCounter == schedule.refundSelfDestruct, "the refund is paid once per account per transaction")
  }

  it should "earn a refund for each different account registered" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(destroying(other)))
    val program = calling(0xf1, other, 40000) ++ destroying(other)
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, program)
    assert(
      frame.refundCounter == schedule.refundSelfDestruct * 2,
      "two accounts registered is two refunds, which is the control"
    )
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
      EvmFixtures.message(caller = caller, currentTarget = runner, value = EvmFixtures.word(7), transfersValue = true)
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
      "these rules name no operation at this byte, so it must halt as any undefined byte does: " + outcome
    )
  }

  // ── The same unaffordable deposit, under the fork that calls it a failure ─

  it should "undo the deployment where the deposit is unaffordable and the rule is strict" in {
    // The exact scenario the three cases above pin under the permissive rule, run under
    // the fork that reverses it -- so the pair states a delta rather than two
    // unrelated behaviors.
    val environment = EvmFixtures.environmentUnder(strictDeposit)
    val _ = runIn(environment, oneShortOfDeposit, creating(deploying))
    assert(
      !environment.world.accountExists(ContractAddress.of(runner, UInt64.Zero)),
      "the creation is undone, so not even the empty account it left behind survives"
    )
  }

  it should "answer zero where the deposit is unaffordable and the rule is strict" in {
    val (frame, _) = runIn(EvmFixtures.environmentUnder(strictDeposit), oneShortOfDeposit, creating(deploying))
    assert(
      frame.stack.peek(0) == Right(Word.Zero),
      "the creating operation is told it failed, where under the permissive rule it is told the address"
    )
  }

  it should "take every remaining unit of gas where the deposit is unaffordable and the rule is strict" in {
    val (frame, _) = runIn(EvmFixtures.environmentUnder(strictDeposit), oneShortOfDeposit, creating(deploying))
    assert(
      frame.gasLeft == BigInt(0),
      "a halted deployment returns nothing, where under the permissive rule what it did not spend comes back"
    )
  }

  // ── A bound on how long the deployed code may be ─────────────────────────

  "a deployment under a bound on its code" should "store code standing exactly on the bound" in {
    // The comparison is strictly greater -- EIP-170 sets "more than" in bold --
    // so the bound itself is deployable. Written as a pair with the case below
    // because only a case standing on the bound catches a comparison off by one,
    // and an off-by-one here is a different state root on every network that
    // bounds anything.
    val environment = EvmFixtures.environmentUnder(bounding(Bound))
    val _ = runIn(environment, roomForTheBound, creating(returningBytes(Bound)))
    assert(
      environment.world.codeOf(ContractAddress.of(runner, UInt64.Zero)).length == Bound,
      "code exactly as long as the bound was refused, so the comparison is not strictly greater"
    )
  }

  it should "leave nothing behind where the code is one byte past the bound" in {
    val environment = EvmFixtures.environmentUnder(bounding(Bound))
    val _ = runIn(environment, roomForTheBound, creating(returningBytes(Bound + 1)))
    assert(
      !environment.world.accountExists(ContractAddress.of(runner, UInt64.Zero)),
      "the creation is undone, so not even the empty account it left behind survives"
    )
  }

  it should "answer zero where the code is one byte past the bound" in {
    val (frame, _) =
      runIn(EvmFixtures.environmentUnder(bounding(Bound)), roomForTheBound, creating(returningBytes(Bound + 1)))
    assert(
      frame.stack.peek(0) == Right(Word.Zero),
      "the creating operation was told an address for a deployment that stored no code"
    )
  }

  it should "take every remaining unit of gas where the code is one byte past the bound" in {
    // The half that separates a bound from the deposit rule beside it. Under the
    // permissive deposit rule an unaffordable deposit hands back what it did not
    // spend; a bound is not a price, and no fork softens it.
    val (frame, _) =
      runIn(EvmFixtures.environmentUnder(bounding(Bound)), roomForTheBound, creating(returningBytes(Bound + 1)))
    assert(
      frame.gasLeft == BigInt(0),
      "gas came back from a deployment the bound refused, which is what an unaffordable deposit does instead"
    )
  }

  it should "store that same code where the rules bound nothing" in {
    // The control, and the three cases above are worth nothing without it: the
    // same code, the same budget and the same program, refused there and stored
    // here. So neither the gas nor the deployment code can be what the refusal
    // was about.
    val environment = EvmFixtures.environment()
    val _ = runIn(environment, roomForTheBound, creating(returningBytes(Bound + 1)))
    assert(
      environment.world.codeOf(ContractAddress.of(runner, UInt64.Zero)).length == Bound + 1,
      "rules naming no bound refused a deployment anyway, so the cases above prove nothing about the bound"
    )
  }

  it should "refuse an over-long deployment whose deposit is unaffordable, rather than keep the gas" in {
    // The one case the two rules answer differently, and therefore the only one
    // that pins the bound as checked BEFORE the charge rather than after it.
    // With the deposit rule permissive, an unaffordable charge deploys nothing
    // and hands back what it did not spend; a bound refuses outright. Checking
    // after the charge would reach the first of those and this case would keep
    // its gas.
    //
    // The field splits here and the choice is not unanimous: go-ethereum's two
    // lines exclude the bound's error from that leniency by name, besu runs its
    // rule outside the branch that reads the flag, and revm returns before
    // reaching it -- while nethermind prices an over-long deployment at its
    // maximum and so hands the bound to the flag after all.
    val (frame, _) =
      runIn(
        EvmFixtures.environmentUnder(bounding(Bound)),
        shortOfTheBoundedDeposit,
        creating(returningBytes(Bound + 1))
      )
    assert(
      frame.gasLeft == BigInt(0),
      "a bound was softened by the rule about an unaffordable deposit, which is not a rule about bounds"
    )
  }

  it should "keep the gas at that same figure where the rules bound nothing" in {
    // What makes the case above a finding rather than a coincidence: the figure
    // really is short of the deposit, so the refusal there is the bound and not
    // the budget being too small to run the deployment at all.
    val (frame, _) =
      runIn(EvmFixtures.environment(), shortOfTheBoundedDeposit, creating(returningBytes(Bound + 1)))
    assert(
      frame.gasLeft > BigInt(0),
      "the figure is not short of the deposit, so the case above says nothing about the bound"
    )
  }

  // ── What a nested invocation may be given ────────────────────────────────

  "CALL under the forwarded-gas cap" should "hand over all but one sixty-fourth where the request is larger" in {
    // 6440 left when the operation runs, less its own settled 40 leaves 6400 to
    // cap: a sixty-fourth of that is 100, so 6300 goes and 100 stays. The callee
    // stops without spending any of it, so all 6300 comes back on top.
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (frame, _) =
      runIn(EvmFixtures.environmentUnder(capping, holder), cappedRun, calling(0xf1, other, 60000))
    assert(
      frame.gasLeft == BigInt(Capped) - schedule.zero,
      "the caller was charged for the capped amount rather than for what it asked"
    )
  }

  it should "run out of gas uncapped, where the same request is charged in full" in {
    // The other half of the delta, and the reason the cap was proposed: before
    // it, asking for more than you hold is a frame that dies rather than one
    // that gets less.
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (_, outcome) = runIn(EvmFixtures.environment(holder), cappedRun, calling(0xf1, other, 60000))
    assert(
      outcome == Right(Outcome.Halted(Halt.OutOfGas)),
      "the uncapped rule charges the whole request and cannot cover it"
    )
  }

  it should "run out of gas where the caller cannot cover the operation's own price" in {
    // 25000 left against a settled 40 plus the 25000 for an account this state
    // has never held. What remains to cap is nothing rather than a shortfall of
    // forty, and the difference is not cosmetic: a shortfall would make the cap
    // negative, and a negative amount forwarded would reduce the charge below
    // what the caller holds and let an unaffordable call through.
    val (_, outcome) =
      runIn(
        EvmFixtures.environmentUnder(capping),
        schedule.veryLow * 7 + schedule.newAccount,
        calling(0xf1, other, 60000)
      )
    assert(outcome == Right(Outcome.Halted(Halt.OutOfGas)), "a caller that cannot pay the price was let through")
  }

  "DELEGATECALL under the forwarded-gas cap" should "be capped like the rest of the call family" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (frame, _) = runIn(
      EvmFixtures.environmentUnder(cappingDelegation, holder),
      schedule.veryLow * 6 + schedule.callBase + Capped,
      delegating(other, 60000)
    )
    assert(
      frame.gasLeft == BigInt(Capped) - schedule.zero,
      "the form that keeps its caller's identity is capped on the same terms"
    )
  }

  "CREATE under the forwarded-gas cap" should "leave the creator a sixty-fourth of what it held" in {
    // A creation names no request, so the cap applies to everything the creator
    // still holds once the settled price is paid. The deployment halts, so
    // nothing comes back and what is left is exactly what was kept back.
    val (frame, _) = runIn(EvmFixtures.environmentUnder(capping), 100000, creating("0c"))
    assert(
      frame.gasLeft == (BigInt(100000) - creationPreamble - schedule.createBase) / Fraction,
      "a creation forwarded everything, or kept back the wrong share"
    )
  }

  it should "leave the creator nothing uncapped, which is what the cap changes" in {
    val (frame, _) = runIn(EvmFixtures.environment(), 100000, creating("0c"))
    assert(frame.gasLeft == BigInt(0), "the uncapped rule forwards the whole of what a creator holds")
  }

  "SELFDESTRUCT under EIP-150's charge" should "cost the base where the beneficiary already exists" in {
    val holder = world()
    holder.balances(other) = EvmFixtures.word(1)
    val (frame, _) = runIn(EvmFixtures.environmentUnder(charging, holder), 100000, destroying(other))
    assert(
      frame.gasLeft == BigInt(100000) - schedule.veryLow - destructionCharge,
      "the push is charged at its tier and the operation at the charge these rules name"
    )
  }

  it should "cost the base and a surcharge where the beneficiary has never existed" in {
    // The account paid out to is brought into being by the payment, and that is
    // charged for -- which is what makes this operation's price conditional on
    // state rather than settled before it runs.
    val (frame, _) = runIn(EvmFixtures.environmentUnder(charging), 100000, destroying(other))
    assert(
      frame.gasLeft == BigInt(100000) - schedule.veryLow - destructionCharge - destructionSurcharge,
      "an account this state has never held was not charged for"
    )
  }

  it should "run out of gas where the frame cannot cover the base and the surcharge" in {
    // Both cases above fund the frame far above the maximum charge, so the
    // boundary this fork creates is never crossed by either. Before it, the
    // operation was free and could not run out of gas at all.
    val (_, outcome) = runIn(
      EvmFixtures.environmentUnder(charging),
      schedule.veryLow + destructionCharge + destructionSurcharge - 1,
      destroying(other)
    )
    assert(
      outcome == Right(Outcome.Halted(Halt.OutOfGas)),
      "the whole charge was affordable to a frame holding one unit less than it"
    )
  }

  // ── When bringing the destination into being is charged for ──────────────
  //
  // Two conditions, two operations, and the pair of them disagree about exactly
  // two states: a destination sent nothing, and a destination that exists while
  // holding nothing. Every case below stands on one of those two, with the
  // states both conditions agree on kept beside them as controls.

  "a call under the earlier surcharge" should "pay it for a destination this state has never held, whatever it sends" in {
    // The standing reading, restated here as the control the case below is
    // measured against rather than left implicit in another section.
    val (frame, _) = runIn(EvmFixtures.environment(), 100000, calling(0xf1, other, 1000))
    assert(frame.gasLeft == BigInt(100000) - bareCall - schedule.newAccount, "an absent destination is charged for")
  }

  it should "pay nothing for a destination that exists while holding nothing" in {
    // The other half of what the earlier reading asks: existence alone, with no
    // regard for what the account holds.
    val (frame, _) = runIn(
      EvmFixtures.environmentUnder(EvmFixtures.rules, holdingNothingAt(other)),
      100000,
      calling(0xf1, other, 1000, value = 40)
    )
    assert(
      frame.gasLeft == BigInt(100000) - bareCall - sendingCall,
      "the earlier condition asks whether the account exists and nothing else"
    )
  }

  "a call under the later surcharge" should "pay nothing for a destination it sends nothing to" in {
    // The first of the two states the readings disagree about. Under the earlier
    // one this same call pays the surcharge, which the control above measures.
    val (frame, _) = runIn(EvmFixtures.environmentUnder(chargingTheDead), 100000, calling(0xf1, other, 1000))
    assert(
      frame.gasLeft == BigInt(100000) - bareCall,
      "the charge is levied only where the operation transfers more than zero value"
    )
  }

  it should "pay it for a destination this state has never held and does send to" in {
    // The control on the case above: without it, a surcharge deleted outright
    // would pass.
    val funded = world()
    funded.balances(runner) = EvmFixtures.word(50)
    val (frame, _) = runIn(
      EvmFixtures.environmentUnder(chargingTheDead, funded),
      100000,
      calling(0xf1, other, 1000, value = 40)
    )
    assert(
      frame.gasLeft == BigInt(100000) - bareCall - sendingCall - schedule.newAccount,
      "an absent destination is still dead, so a call sending to it still pays"
    )
  }

  it should "pay it for a destination that exists while holding nothing" in {
    // The second state the readings disagree about, and the reason the condition
    // is not simply existence: dead is non-existent OR empty.
    val holder = holdingNothingAt(other)
    holder.balances(runner) = EvmFixtures.word(50)
    val (frame, _) = runIn(
      EvmFixtures.environmentUnder(chargingTheDead, holder),
      100000,
      calling(0xf1, other, 1000, value = 40)
    )
    assert(
      frame.gasLeft == BigInt(100000) - bareCall - sendingCall - schedule.newAccount,
      "an account holding nothing is dead however long this state has held it"
    )
  }

  it should "pay nothing for a destination holding something" in {
    // The other control: an account with a balance is alive under either
    // reading, so a condition that charged unconditionally would fail here.
    val holder = world()
    holder.balances(other) = EvmFixtures.word(1)
    holder.balances(runner) = EvmFixtures.word(50)
    val (frame, _) = runIn(
      EvmFixtures.environmentUnder(chargingTheDead, holder),
      100000,
      calling(0xf1, other, 1000, value = 40)
    )
    assert(
      frame.gasLeft == BigInt(100000) - bareCall - sendingCall,
      "a balance is one of the three terms, so an account holding one is alive"
    )
  }

  it should "pay nothing for a delegated call, whatever value that call carries" in {
    // The delegating form moves nothing -- the move was made by the invocation
    // whose identity it borrows -- so it cannot reach the condition however dead
    // the account it runs as is. Both authorities express that by giving the
    // form no surcharge term at all: the specification's `delegatecall` adds
    // only a base and a transfer term it never earns, and go-ethereum's
    // `gasDelegateCall` adds neither.
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val environment = EvmFixtures.environmentUnder(chargingTheDead.copy(table = admitting), holder)
    val (frame, _) = runIn(
      environment,
      100000,
      delegating(other, 1000),
      // The account it runs as holds nothing and is therefore dead, and the
      // value is carried rather than transferred, which is the state a form
      // reading the value without reading the form would charge for.
      Message(caller, runner, Some(runner), EvmFixtures.word(40), Bytes.Empty, transfersValue = false)
    )
    assert(
      frame.gasLeft == BigInt(100000) - schedule.veryLow * 6 - schedule.callBase - schedule.zero,
      "a form that moves nothing was charged for bringing an account into being"
    )
  }

  "an invocation ending under the earlier surcharge" should "pay nothing where the beneficiary exists while holding nothing" in {
    // The control that makes the two cases below measurements: under this
    // reading an account's emptiness is not asked about at all.
    val (frame, _) = runIn(EvmFixtures.environmentUnder(charging, holdingNothingAt(other)), 100000, destroying(other))
    assert(frame.gasLeft == BigInt(100000) - bareDestruction, "the earlier condition asks only whether it exists")
  }

  "an invocation ending under the later surcharge" should "pay nothing where it has nothing to give" in {
    // The first disagreement, on this operation. What it moves is the whole
    // balance of the account ending, so an account ending with nothing transfers
    // nothing and cannot bring the beneficiary into being. The standing case for
    // the earlier reading charges the surcharge over this same empty world.
    val (frame, _) = runIn(EvmFixtures.environmentUnder(chargingDeadBeneficiaries), 100000, destroying(other))
    assert(
      frame.gasLeft == BigInt(100000) - bareDestruction,
      "the balance of the account ending is what decides whether this operation transfers anything"
    )
  }

  it should "pay it where it has something to give and the beneficiary has never existed" in {
    val funded = world()
    funded.balances(runner) = EvmFixtures.word(500)
    val (frame, _) = runIn(EvmFixtures.environmentUnder(chargingDeadBeneficiaries, funded), 100000, destroying(other))
    assert(
      frame.gasLeft == BigInt(100000) - bareDestruction - destructionSurcharge,
      "an absent beneficiary paid something is still brought into being, and that is still charged for"
    )
  }

  it should "pay it where the beneficiary exists while holding nothing" in {
    val holder = holdingNothingAt(other)
    holder.balances(runner) = EvmFixtures.word(500)
    val (frame, _) = runIn(EvmFixtures.environmentUnder(chargingDeadBeneficiaries, holder), 100000, destroying(other))
    assert(
      frame.gasLeft == BigInt(100000) - bareDestruction - destructionSurcharge,
      "a beneficiary holding nothing is dead, which is the state the earlier condition charges nothing for"
    )
  }

  it should "pay nothing where the beneficiary holds something" in {
    val holder = world()
    holder.balances(other) = EvmFixtures.word(1)
    holder.balances(runner) = EvmFixtures.word(500)
    val (frame, _) = runIn(EvmFixtures.environmentUnder(chargingDeadBeneficiaries, holder), 100000, destroying(other))
    assert(frame.gasLeft == BigInt(100000) - bareDestruction, "a beneficiary holding a balance is alive")
  }

  // ── Which invocations move the value they carry ──────────────────────────

  "DELEGATECALL" should "leave both balances where the invocation it borrows from left them" in {
    // The borrowing form carries the value for the code to read and moves
    // nothing, because the move was already made by the invocation whose
    // identity it borrows. Moving it again takes the same value out of the
    // original caller twice -- and only announces itself where that caller has
    // since spent below it. Here it has not, so a second move would be silent
    // and the balances are the only thing that shows it.
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    holder.balances(caller) = EvmFixtures.word(1000)
    val environment = EvmFixtures.environment(holder, withTable = admitting)
    val _ = runIn(
      environment,
      100000,
      delegating(other, 40000),
      EvmFixtures.message(caller = caller, currentTarget = runner, value = EvmFixtures.word(40), transfersValue = true)
    )
    assert(
      environment.world.balanceOf(caller) == EvmFixtures.word(960) &&
        environment.world.balanceOf(runner) == EvmFixtures.word(40),
      "the value the borrowed invocation carries was moved a second time"
    )
  }

  "CALLCODE" should "leave the balance it moves to itself exactly where it was" in {
    // This form DOES move its value, and moves it to the account already
    // holding it -- so the decrement and the credit cancel. They cancel because
    // the credit re-reads the balance the decrement just wrote rather than a
    // figure read before it, which is correct by the order the two statements
    // are in and would double the balance if that order were ever reversed.
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    holder.balances(runner) = EvmFixtures.word(500)
    val environment = EvmFixtures.environment(holder)
    val _ = runIn(
      environment,
      100000,
      calling(0xf2, other, 40000, value = 40),
      EvmFixtures.message(caller = caller, currentTarget = runner, transfersValue = true)
    )
    assert(
      environment.world.balanceOf(runner) == EvmFixtures.word(500),
      "an account sending value to itself did not end with what it started with"
    )
  }

  // ── What an invocation records having reached ────────────────────────────
  //
  // The record and the state-side touch beside it are two acts, and only the
  // second is reversed by a snapshot. Each case here reads the frame's own
  // record rather than world state, because the difference between them is
  // exactly what a settlement later acts on.

  "an invocation" should "record the account it runs as" in {
    val (frame, _) = runIn(EvmFixtures.environment(world()), 100000, stopping)
    assert(
      frame.touchedAccounts == Set(runner),
      "an account an invocation ran as is reached by that invocation, whatever the invocation then did"
    )
  }

  "a nested invocation that stopped" should "give its caller what it reached" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(stopping))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 40000))
    assert(
      frame.touchedAccounts == Set(runner, other),
      "a reach a nested invocation made is its caller's once that invocation ends normally"
    )
  }

  "a nested invocation that halted" should "give its caller nothing it reached" in {
    val holder = world()
    holder.codes(other) = codeOf(hex(halting))
    val (frame, _) = runIn(EvmFixtures.environment(holder), 100000, calling(0xf1, other, 40000))
    assert(
      frame.touchedAccounts == Set(runner),
      "a reach made inside an invocation that halted outlived it"
    )
  }

  it should "give its caller an address the rules exempt from that" in {
    // The exception the proposal's Addendum preserves. The four implementations
    // read for EvmRules.touchSurvivesFailure each narrow it to one address; the
    // rules carry a set so a network can name none.
    val holder = world()
    holder.codes(other) = codeOf(hex(halting))
    val exempting = EvmFixtures.rules.copy(touchSurvivesFailure = Set(other))
    val (frame, _) =
      runIn(EvmFixtures.environmentUnder(exempting, holder), 100000, calling(0xf1, other, 40000))
    assert(
      frame.touchedAccounts == Set(runner, other),
      "an address the rules exempt lost its reach with the invocation that made it"
    )
  }

  it should "give its caller nothing else, exempting an address it also reached" in {
    // The intersection, from the side the case above cannot see: a failed
    // invocation with a non-empty exemption must still drop everything the
    // exemption does not name. Written as a chain so the dropped address is one
    // a nested invocation reached and STOPPED at, which is the reach that would
    // otherwise have survived on its own merits.
    val third = EvmFixtures.address(0x44)
    val holder = world()
    holder.codes(other) = codeOf(hex(calling(0xf1, third, 20000) ++ halting))
    holder.codes(third) = codeOf(hex(stopping))
    val exempting = EvmFixtures.rules.copy(touchSurvivesFailure = Set(other))
    val (frame, _) =
      runIn(EvmFixtures.environmentUnder(exempting, holder), 100000, calling(0xf1, other, 60000))
    assert(
      frame.touchedAccounts == Set(runner, other),
      "an exemption naming one address carried a second one up with it"
    )
  }

  "an invocation that ends itself" should "record the account it pays out to" in {
    // The proposal's own second context: an empty account has zero value
    // transferred to it through SELFDESTRUCT.
    val (frame, _) = runIn(EvmFixtures.environment(world()), 100000, destroying(other))
    assert(
      frame.touchedAccounts == Set(runner, other),
      "the account a destruction pays out to was not recorded as reached"
    )
  }

  "a creation that stopped" should "give its creator the account it made" in {
    val environment = EvmFixtures.environment(world())
    val (frame, _) = runIn(environment, 100000, creating(deploying))
    assert(
      frame.touchedAccounts == Set(runner, grandchildOf(runner, 0)),
      "an account a creation brought into being was not recorded as reached by its creator"
    )
  }

  "a creation that halted at an exempt address" should "still give its creator that address" in {
    // The creating operation reaches the same failure path the calling one does,
    // and a counterpart applied at one and not the other is a divergence nothing
    // else here would name.
    val created = grandchildOf(runner, 0)
    val exempting = EvmFixtures.rules.copy(touchSurvivesFailure = Set(created))
    val (frame, _) =
      runIn(EvmFixtures.environmentUnder(exempting, world()), 100000, creating(hex(halting)))
    assert(
      frame.touchedAccounts == Set(runner, created),
      "a creation that halted dropped a reach the rules exempt"
    )
  }

  it should "give its creator nothing where the rules exempt nothing" in
    // The control for the case above, and for the one two above it: without it
    // both hold for a machine that never discards what a failed creation
    // reached.
    assert(
      runIn(EvmFixtures.environment(world()), 100000, creating(hex(halting)))._1.touchedAccounts == Set(runner),
      "a creation that halted left its creator holding a reach at an address that no longer exists"
    )
