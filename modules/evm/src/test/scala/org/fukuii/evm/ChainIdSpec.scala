package org.fukuii.evm

import org.fukuii.bytes.{Bytes, UInt64}
import org.scalatest.flatspec.AnyFlatSpec

/** The operation that answers which network the machine is running as.
  *
  * ==It is not in the original table, so every case here adds it==
  *
  * [[OpcodeTable.original]] does not carry the operation, which is what makes a
  * network that has not adopted the proposal refuse the byte. Each case below
  * runs a table that has been given it, priced at the tier the proposal names.
  *
  * Expected behavior is `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-1344.md`
  * (Final): *"Adds a new opcode `CHAINID` at 0x46, which uses 0 stack
  * arguments. It pushes the current chain ID onto the stack. Chain ID is a
  * 256-bit value. The operation costs `G_base` to execute."* Read against
  * `ethereum/execution-specs` @ `20f7f6271a`, whose `chain_id` sits in
  * `src/ethereum/forks/istanbul/vm/instructions/block.py` rather than beside
  * the operations that read an account -- the same split the interpreter's own
  * arms take.
  */
class ChainIdSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Held above every test registration: Scala 3's initialization checker reads
  // a val declared below one as read-before-init, and reports it against the
  // first test in the class rather than against the val.
  private val table: OpcodeTable =
    OpcodeTable.original(schedule).adding(Operation(Opcode.ChainId, Cost.Fixed(schedule.base)))

  /** An identifier that is not [[EvmFixtures.chainId]].
    *
    * A machine answering a compiled-in constant rather than reading what it was
    * handed passes one of the two cases below and fails the other, whichever
    * constant it holds -- which a single fixture value cannot establish.
    */
  private val otherIdentifier: UInt64 = UInt64.fromBits(0xfaceL)

  private def environmentIdentifying(identifier: UInt64): Environment =
    new Environment(
      new JournaledWorldState(new EvmFixtures.MapWorldState),
      EvmFixtures.blockHashAt,
      EvmFixtures.block,
      EvmFixtures.transaction,
      identifier,
      EvmFixtures.rules.copy(table = table)
    )

  private def runIn(environment: Environment, gas: Int, program: Int*): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(
      EvmFixtures.message(transfersValue = true),
      Code(Bytes.fromArray(program.map(_.toByte).toArray)),
      BigInt(gas)
    )
    (frame, Interpreter.run(frame, environment))

  private def answerUnder(identifier: UInt64): Either[Halt, Word] =
    val (frame, _) = runIn(environmentIdentifying(identifier), 1000, Opcode.ChainId.code)
    frame.stack.peek(0)

  "CHAINID" should "push the identifier the environment carries" in
    assert(
      answerUnder(EvmFixtures.chainId) == Right(Word(EvmFixtures.chainId.toBigInt)),
      "the operation answers the environment's identifier, taking no operand to do it"
    )

  it should "follow the environment rather than answer a constant" in
    assert(
      answerUnder(otherIdentifier) == Right(Word(otherIdentifier.toBigInt)),
      "a second environment gets its own identifier back, which no compiled-in value can do for both"
    )

  it should "cost the base tier" in {
    val (frame, _) = runIn(environmentIdentifying(EvmFixtures.chainId), 1000, Opcode.ChainId.code)
    assert(
      frame.gasLeft == BigInt(1000) - schedule.base,
      "the proposal prices it at G_base, and the tier is read from the schedule rather than fixed here"
    )
  }

  it should "leave the stack holding one word" in {
    val (frame, _) = runIn(environmentIdentifying(EvmFixtures.chainId), 1000, Opcode.ChainId.code)
    assert(
      frame.stack.peek(1).isLeft,
      "it uses no stack argument, so nothing sits under what it pushed"
    )
  }

  it should "not run under a table that does not carry it" in {
    val environment = EvmFixtures.environment()
    val (_, outcome) = runIn(environment, 1000, Opcode.ChainId.code)
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidOpcode(Opcode.ChainId.code))),
      "a network that has not adopted the proposal meets a byte naming no operation"
    )
  }
