package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.flatspec.AnyFlatSpec

/** The operation that answers the balance of the account an invocation runs as.
  *
  * ==The subject is WHOSE balance, and one account cannot establish it==
  *
  * The operation takes no operand, so an implementation that read one instead
  * would answer a different account and agree with this one at every case where
  * the two accounts are the same. Every case below therefore holds two accounts
  * at two balances, and the assertions name which of them the answer is.
  *
  * Expected behavior is `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-1884.md`
  * (Final): *"A new opcode, `SELFBALANCE` is introduced at `0x47`"*, which
  * *"pops `0` arguments off the stack"*, *"pushes the `balance` of the current
  * address to the stack"* and *"is priced as `GasFastStep`"*. The same document
  * reprices `BALANCE` separately and upward, which is why the two tiers here are
  * distinct rather than shared. Read against `ethereum/execution-specs` @
  * `20f7f6271a`, `src/ethereum/forks/istanbul/vm/instructions/environment.py`,
  * whose `self_balance` reads `evm.message.current_target` and pops nothing.
  */
class SelfBalanceSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Held above every test registration: Scala 3's initialization checker reads
  // a val declared below one as read-before-init, and reports it against the
  // first test in the class rather than against the val.
  private val table: OpcodeTable =
    OpcodeTable.original(schedule).adding(Operation(Opcode.SelfBalance, Cost.Fixed(schedule.low)))

  /** The account an invocation runs as by default, which is what the operation
    * must answer for.
    */
  private val runner: Address = EvmFixtures.address(0x22)

  /** A second account, named on the stack by the cases that also run `BALANCE`.
    */
  private val other: Address = EvmFixtures.address(0x33)

  /** An account this world has never held, for the case with nothing to answer.
    */
  private val absent: Address = EvmFixtures.address(0x77)

  private val runnerBalance: Word = EvmFixtures.word(500)

  /** Distinct from [[runnerBalance]], so that an answer naming the wrong account
    * is a failure rather than a coincidence.
    */
  private val otherBalance: Word = EvmFixtures.word(900)

  private def push20(address: Address): Seq[Int] =
    0x73 +: (0 until Address.Width).map(index => address.toBytes(index) & 0xff)

  /** A world holding both accounts at their two balances. */
  private def bothFunded(): EvmFixtures.MapWorldState =
    val world = new EvmFixtures.MapWorldState
    world.setBalance(runner, runnerBalance)
    world.setBalance(other, otherBalance)
    world

  private def runAs(
      target: Address,
      world: EvmFixtures.MapWorldState,
      gas: Int,
      program: Seq[Int]
  ): (Frame, Either[Unsupported, Outcome]) =
    val environment = EvmFixtures.environment(world, withTable = table)
    val message = EvmFixtures.message(currentTarget = target, transfersValue = true)
    val frame = new Frame(message, Code(Bytes.fromArray(program.map(_.toByte).toArray)), BigInt(gas))
    (frame, Interpreter.run(frame, environment))

  private def runHere(gas: Int, program: Seq[Int]): (Frame, Either[Unsupported, Outcome]) =
    runAs(runner, bothFunded(), gas, program)

  private val selfBalance: Seq[Int] = Seq(Opcode.SelfBalance.code)

  private val balanceOfOther: Seq[Int] = push20(other) :+ Opcode.Balance.code

  "SELFBALANCE" should "push the balance of the account the invocation runs as" in {
    val (frame, _) = runHere(1000, selfBalance)
    assert(
      frame.stack.peek(0) == Right(runnerBalance),
      "the answer is the executing account's balance and not the caller's or an operand's"
    )
  }

  it should "answer the executing account even where another address sits on the stack" in {
    val (frame, _) = runHere(1000, push20(other) ++ selfBalance)
    assert(
      frame.stack.peek(0) == Right(runnerBalance),
      "an implementation reading the word beneath it would answer the other account's balance instead"
    )
  }

  it should "consume nothing from the stack" in {
    val (frame, _) = runHere(1000, Seq(0x60, 0x2a) ++ selfBalance)
    assert(
      frame.stack.peek(1) == Right(EvmFixtures.word(0x2a)),
      "it pops no argument, so the word that was already there is still under the answer"
    )
  }

  it should "disagree with BALANCE where the two accounts differ" in {
    val (frame, _) = runHere(1000, balanceOfOther ++ selfBalance)
    assert(
      (frame.stack.peek(0), frame.stack.peek(1)) == (Right(runnerBalance), Right(otherBalance)),
      "the two operations read two accounts, so a shared implementation would answer alike"
    )
  }

  it should "answer zero for an account this state has never held" in {
    val (frame, _) = runAs(absent, new EvmFixtures.MapWorldState, 1000, selfBalance)
    assert(
      frame.stack.peek(0) == Right(Word.Zero),
      "an absent account has no balance to report rather than no answer"
    )
  }

  it should "cost the low tier" in {
    val (frame, _) = runHere(1000, selfBalance)
    assert(
      frame.gasLeft == BigInt(1000) - schedule.low,
      "the proposal prices it three tiers below the operation that resolves an address"
    )
  }

  /** Both programs push the same address and then run one operation, so what
    * separates what they leave is the two operations' own charges and nothing
    * else. Measuring `SELFBALANCE` alone against the pair would fold the push
    * into the comparison, and it would then hold even where the two operations
    * are priced from one tier.
    */
  it should "cost less than BALANCE" in {
    val (reading, _) = runHere(1000, push20(other) ++ selfBalance)
    val (resolving, _) = runHere(1000, balanceOfOther)
    assert(
      reading.gasLeft > resolving.gasLeft,
      "the same document reprices BALANCE upward, so a machine charging both from one tier is wrong"
    )
  }

  it should "not run under a table that does not carry it" in {
    val environment = EvmFixtures.environment(bothFunded())
    val message = EvmFixtures.message(currentTarget = runner, transfersValue = true)
    val frame = new Frame(message, Code(Bytes.fromArray(Array(Opcode.SelfBalance.code.toByte))), BigInt(1000))
    assert(
      Interpreter.run(frame, environment) == Right(Outcome.Halted(Halt.InvalidOpcode(Opcode.SelfBalance.code))),
      "a network that has not adopted the proposal meets a byte naming no operation"
    )
  }
