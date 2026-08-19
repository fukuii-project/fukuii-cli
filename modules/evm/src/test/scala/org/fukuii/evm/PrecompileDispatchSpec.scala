package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.flatspec.AnyFlatSpec

/** How an invocation reaches a precompile, which is where a wrong answer is a
  * chain split rather than a wrong output.
  *
  * [[PrecompileSpec]] and [[PrecompilePropSpec]] cover what each precompile
  * computes. Everything here is about the branch that decides one runs at all:
  * what it is keyed on, where it sits relative to the value transfer and the
  * snapshot, and which invocations can never reach it.
  *
  * Expected behavior is `ethereum/execution-specs` at `ccaaaba58` --
  * `frontier/vm/interpreter.py`'s `process_message`, which dispatches on
  * `message.code_address` after taking its snapshot and moving the value, and
  * `frontier/vm/instructions/system.py`, which is what sets that field for each
  * of the two calling operations -- read against `ethereum/go-ethereum` at
  * `6bb0588ad`, `core/vm/evm.go`, whose `CallCode` carries the same rule in a
  * comment of its own.
  */
class PrecompileDispatchSpec extends AnyFlatSpec:

  private val schedule = GasSchedule.Baseline
  private val table = OpcodeTable.baseline(schedule)
  private val precompiles = PrecompileSet.baseline(schedule)

  private val caller = EvmFixtures.address(0x11)
  private val runner = EvmFixtures.address(0x22)

  private val identity = PrecompileSet.Identity

  /** An account that exists and holds no code, so a call to it runs nothing and
    * hands every unit forwarded straight back.
    *
    * It is what a precompile's charge is measured against below: two runs of
    * the same program differing only in the twenty address bytes cost the same
    * to reach the calling operation and are charged the same for it, so the
    * difference between what each leaves is the precompile's price and nothing
    * else. Working the absolute figure out instead would mean counting the
    * operands' own cost, which is arithmetic a test should not be trusted to
    * get right.
    */
  private val inert = EvmFixtures.address(0x44)

  /** What `identity` costs for an input of no bytes, which is what most of the
    * calls below send it.
    */
  private val emptyCopy = schedule.precompileIdentityBase

  /** Meets a byte that names no operation, so it halts exceptionally. */
  private val halting = Bytes.fromArray(Array(0x0c.toByte))

  private def push1(value: Int): Seq[Int] = Seq(0x60, value & 0xff)

  private def push2(value: Int): Seq[Int] = Seq(0x61, (value >> 8) & 0xff, value & 0xff)

  private def push20(address: Address): Seq[Int] =
    0x73 +: (0 until Address.Width).map(index => address.toBytes(index) & 0xff)

  /** A `CALL` or `CALLCODE` sending nothing and asking for no answer back. */
  private def calling(operation: Int, target: Address, gas: Int): Seq[Int] =
    push1(0) ++ push1(0) ++ push1(0) ++ push1(0) ++ push1(0) ++ push20(target) ++ push2(gas) :+ operation

  /** A `CALL` or `CALLCODE` sending `size` bytes from the start of memory and
    * taking the answer back to `answerAt`.
    */
  private def callingWith(operation: Int, target: Address, gas: Int, size: Int, answerAt: Int): Seq[Int] =
    push1(size) ++ push1(answerAt) ++ push1(size) ++ push1(0) ++ push1(0) ++ push20(target) ++ push2(gas) :+ operation

  /** A `CALL` sending `value` and asking for `gas`, with no input and no room
    * for an answer.
    */
  private def sending(target: Address, value: Int, gas: Int): Seq[Int] =
    push1(0) ++ push1(0) ++ push1(0) ++ push1(0) ++ push1(value) ++ push20(target) ++ push2(gas) :+ 0xf1

  /** Puts one byte at the start of memory, so a call has something to send. */
  private def storing(value: Int): Seq[Int] = push1(value) ++ push1(0x00) :+ 0x53

  private def runIn(
      environment: Environment,
      gas: Int,
      program: Seq[Int],
      message: Message = EvmFixtures.message()
  ): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(message, Code(Bytes.fromArray(program.map(_.toByte).toArray)), BigInt(gas))
    (frame, Interpreter.run(frame, table, schedule, precompiles, environment))

  /** An environment whose named accounts already exist, so that a call is not
    * also charged for bringing one into being -- that charge is the calling
    * operation's and is [[InvocationSpec]]'s to cover.
    */
  private def environmentKnowing(known: Address*): Environment =
    val world = new EvmFixtures.MapWorldState
    known.foreach(world.touch)
    EvmFixtures.environment(world)

  private def worldHolding(balance: Int, existing: Address): Environment =
    val world = new EvmFixtures.MapWorldState
    world.touch(existing)
    world.setBalance(runner, EvmFixtures.word(balance))
    EvmFixtures.environment(world)

  private def gasLeftCalling(target: Address, forwarded: Int): BigInt =
    val (frame, _) = runIn(environmentKnowing(identity, inert), 100000, calling(0xf1, target, forwarded))
    frame.gasLeft

  private def gasLeftBorrowing(target: Address): BigInt =
    val (frame, _) = runIn(environmentKnowing(identity, inert), 100000, calling(0xf2, target, emptyCopy.toInt))
    frame.gasLeft

  private def answer(frame: Frame): Word = frame.stack.peek(0).toOption.get

  // ── What the dispatch is keyed on ────────────────────────────────────────

  "a call to a precompile address" should "run the precompile rather than the account's code" in {
    val environment = environmentKnowing(identity)
    val (frame, _) = runIn(environment, 100000, storing(0x2a) ++ callingWith(0xf1, identity, 50, 1, 1))
    assert(frame.memory.read(1, 1) == EvmFixtures.bytesOf("2a"), "the copy answered with what it was sent")
  }

  it should "succeed even where the account at that address holds code that would halt" in {
    val world = new EvmFixtures.MapWorldState
    world.touch(identity)
    world.setCode(identity, halting)
    val (frame, _) = runIn(EvmFixtures.environment(world), 100000, calling(0xf1, identity, 50))
    assert(
      answer(frame) == Word.One,
      "the precompile takes precedence over whatever code the account holds, so the halting code never ran"
    )
  }

  /** The answer has to be what came BACK, not that the call succeeded.
    *
    * An account at a precompile's address holds no code, so a dispatch keyed on
    * the wrong address runs an empty invocation -- which succeeds, and pushes
    * the same one this would. Only the output tells the two apart.
    */
  "a call borrowing a precompile's code" should "run the precompile" in {
    val environment = environmentKnowing(identity)
    val (frame, _) = runIn(environment, 100000, storing(0x2a) ++ callingWith(0xf2, identity, 50, 1, 1))
    assert(
      frame.memory.read(1, 1) == EvmFixtures.bytesOf("2a"),
      "the borrowing form names the precompile as its CODE address while running as the caller, and both sources reach one"
    )
  }

  it should "be charged for it" in
    assert(
      gasLeftBorrowing(inert) - gasLeftBorrowing(identity) == emptyCopy,
      "a borrowed precompile is priced exactly as a called one is"
    )

  "an invocation naming no code address" should "run its own code even at a precompile's address" in {
    val (_, outcome) = runIn(
      EvmFixtures.environment(),
      100000,
      Seq(0x0c),
      Message(caller, identity, None, Word.Zero, Bytes.Empty)
    )
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidOpcode(0x0c))),
      "a creation names no code address, which is what stops one ever running a precompile however its address falls out"
    )
  }

  "an outermost invocation" should "reach a precompile named as its code address" in {
    val (_, outcome) = runIn(
      EvmFixtures.environment(),
      100000,
      Seq(0x0c),
      Message(caller, identity, Some(identity), Word.Zero, EvmFixtures.bytesOf("2a2a"))
    )
    val spent = schedule.precompileIdentityBase + schedule.precompileIdentityPerWord
    assert(
      outcome == Right(Outcome.Stopped(BigInt(100000) - spent, EvmFixtures.bytesOf("2a2a"))),
      "a transaction sent straight to a precompile is the ordinary way one is used, and the frame's own code is not run"
    )
  }

  // ── The charge, measured against a call that runs nothing ────────────────

  "a precompile forwarded exactly what it costs" should "succeed" in {
    val environment = environmentKnowing(identity)
    val (frame, _) = runIn(environment, 100000, calling(0xf1, identity, emptyCopy.toInt))
    assert(answer(frame) == Word.One, "the charge is settled against what was forwarded")
  }

  it should "have cost the caller exactly its price" in
    assert(
      gasLeftCalling(inert, emptyCopy.toInt) - gasLeftCalling(identity, emptyCopy.toInt) == emptyCopy,
      "everything but the precompile's own charge is the same in both runs"
    )

  "a precompile forwarded one less than it costs" should "answer that the call failed" in {
    val environment = environmentKnowing(identity)
    val (frame, _) = runIn(environment, 100000, calling(0xf1, identity, emptyCopy.toInt - 1))
    assert(answer(frame) == Word.Zero, "a precompile that cannot be paid for halts, and the caller learns of it here")
  }

  it should "have consumed everything forwarded rather than its price" in {
    val short = emptyCopy.toInt - 1
    assert(
      gasLeftCalling(inert, short) - gasLeftCalling(identity, short) == BigInt(short),
      "an exceptional halt keeps nothing, so none of what was forwarded comes back"
    )
  }

  "a precompile forwarded more than it costs" should "hand the remainder back" in {
    val generous = emptyCopy.toInt + 7
    assert(
      gasLeftCalling(inert, generous) - gasLeftCalling(identity, generous) == emptyCopy,
      "forwarding more than a precompile needs costs the caller nothing extra"
    )
  }

  // ── Where the branch sits relative to everything around it ───────────────

  "a precompile sent value" should "receive it" in {
    val environment = worldHolding(500, identity)
    val (_, _) = runIn(environment, 100000, sending(identity, 9, emptyCopy.toInt))
    assert(
      environment.world.balanceOf(identity) == EvmFixtures.word(9),
      "the value moves before the branch, exactly as it does for an invocation that runs code"
    )
  }

  /** Recovery rather than the copy, and the reason is the stipend.
    *
    * A call carrying value hands the callee 2300 gas beyond what it asked for,
    * which is more than the copy costs however little is requested -- so the
    * copy cannot be made to fail this way. Recovery's flat 3000 is above the
    * stipend, so asking for nothing leaves it short.
    */
  "a precompile sent value that cannot be paid for" should "leave the value where it was" in {
    val environment = worldHolding(500, PrecompileSet.EcRecover)
    val (_, _) = runIn(environment, 100000, sending(PrecompileSet.EcRecover, 9, 0))
    assert(
      environment.world.balanceOf(PrecompileSet.EcRecover).isZero,
      "the snapshot is taken before the transfer, so the halt undoes it"
    )
  }

  it should "tell its caller that the call failed" in {
    val environment = worldHolding(500, PrecompileSet.EcRecover)
    val (frame, _) = runIn(environment, 100000, sending(PrecompileSet.EcRecover, 9, 0))
    assert(answer(frame) == Word.Zero, "the caller learns of it from the zero this pushes and from nothing else")
  }
