package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The operation that answers what the block destroys per unit of gas.
  *
  * ==It is not in the original table, so every case here adds it==
  *
  * [[OpcodeTable.original]] does not carry the operation, which is what makes a
  * network that has not adopted the proposal refuse the byte. Each case below
  * runs a table that has been given it, priced at the tier the proposal names --
  * the same arrangement [[ChainIdSpec]] uses, and for the same reason.
  *
  * Expected behavior is `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-3198.md`
  * (Final): *"Add a `BASEFEE` opcode at `(0x48)`, with gas cost `G_base`"*, over
  * a table giving it zero inputs and one output. Read against
  * `ethereum/execution-specs` @ `20f7f6271a`, whose `base_fee` at
  * `forks/london/vm/instructions/environment.py:548` is
  * `push(evm.stack, U256(evm.message.block_env.base_fee_per_gas))` -- the block
  * environment, which is what puts the value on [[BlockContext]] here rather
  * than beside the chain id.
  *
  * ==The last case is the one with no counterpart in [[ChainIdSpec]]==
  *
  * A chain id is present at every height, so that spec never asks what happens
  * when the environment carries nothing. A base fee is absent below the fork
  * that introduced it, and the machine refuses rather than defaulting. Zero is a
  * legal base fee, so a default would answer plausibly for a block that never
  * had one -- which is why the refusal is asserted rather than left to reading.
  */
class BaseFeeSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Held above every test registration: Scala 3's initialization checker reads
  // a val declared below one as read-before-init, and reports it against the
  // first test in the class rather than against the val.
  private val table: OpcodeTable =
    OpcodeTable.original(schedule).adding(Operation(Opcode.BaseFee, Cost.Fixed(schedule.base)))

  /** A charge that is not the one below it.
    *
    * A machine answering a compiled-in constant rather than reading what it was
    * handed passes one of the two cases below and fails the other, whichever
    * constant it holds -- which a single fixture value cannot establish.
    */
  private val oneCharge: BigInt = BigInt(1000000000)

  private val anotherCharge: BigInt = BigInt(7)

  private def environmentCharging(charge: Option[BigInt]): Environment =
    new Environment(
      new JournaledWorldState(new EvmFixtures.MapWorldState),
      EvmFixtures.blockHashAt,
      EvmFixtures.block.copy(baseFee = charge),
      EvmFixtures.transaction,
      EvmFixtures.chainId,
      EvmFixtures.rules.copy(table = table)
    )

  private def runIn(environment: Environment, gas: Int, program: Int*): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(
      EvmFixtures.message(transfersValue = true),
      Code(Bytes.fromArray(program.map(_.toByte).toArray)),
      BigInt(gas)
    )
    (frame, Interpreter.run(frame, environment))

  private def answerUnder(charge: BigInt): Either[Halt, Word] =
    val (frame, _) = runIn(environmentCharging(Some(charge)), 1000, Opcode.BaseFee.code)
    frame.stack.peek(0)

  "BASEFEE" should "push the charge the block carries" in
    assert(
      answerUnder(oneCharge) == Right(Word(oneCharge)),
      "the operation answers the block's own charge, taking no operand to do it"
    )

  it should "follow the block rather than answer a constant" in
    assert(
      answerUnder(anotherCharge) == Right(Word(anotherCharge)),
      "a second block gets its own charge back, which no compiled-in value can do for both"
    )

  it should "push zero where the block genuinely charges zero" in
    // Distinct from the absent case below, and the distinction is the reason
    // the member is an option. A block may legally charge nothing; a block
    // below the fork carries no charge at all. Both would read as zero if the
    // absence were defaulted, and only one of them is a chain state.
    assert(
      answerUnder(BigInt(0)) == Right(Word(BigInt(0))),
      "a charge of zero is a charge and must be answered as one"
    )

  it should "cost the base tier" in {
    val (frame, _) = runIn(environmentCharging(Some(oneCharge)), 1000, Opcode.BaseFee.code)
    assert(
      frame.gasLeft == BigInt(1000) - schedule.base,
      "the proposal prices it at G_base, and the tier is read from the schedule rather than fixed here"
    )
  }

  it should "leave the stack holding one word" in {
    val (frame, _) = runIn(environmentCharging(Some(oneCharge)), 1000, Opcode.BaseFee.code)
    assert(
      frame.stack.peek(1).isLeft,
      "it uses no stack argument, so nothing sits under what it pushed"
    )
  }

  it should "not run under a table that does not carry it" in {
    val environment = EvmFixtures.environment()
    val (_, outcome) = runIn(environment, 1000, Opcode.BaseFee.code)
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidOpcode(Opcode.BaseFee.code))),
      "a network that has not adopted the proposal meets a byte naming no operation"
    )
  }

  it should "refuse a block carrying no charge rather than answer for it" in {
    // The configuration this cannot be reached from a chain by: the operation
    // in the table over a block below the fork that fills the field. Raised
    // rather than returned, because there is no caller who could act on it.
    val thrown = intercept[IllegalStateException] {
      runIn(environmentCharging(None), 1000, Opcode.BaseFee.code)
    }
    assert(
      thrown.getMessage.contains("carries no base fee"),
      "the refusal must name what is missing, so the rule set that omitted it can be found"
    )
  }
