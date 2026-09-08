package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The operation that places a zero without reading an operand.
  *
  * ==It is not in the original table, so every case here adds it==
  *
  * [[OpcodeTable.original]] does not carry the operation, which is what makes a
  * network that has not adopted the proposal refuse the byte. Each case below
  * runs a table that has been given it, priced at the tier the proposal names --
  * the same arrangement [[BaseFeeSpec]] and [[ChainIdSpec]] use.
  *
  * Expected behavior is `ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-3855.md`
  * (Final): *"The instruction `PUSH0` is introduced at `0x5f`. It has no
  * immediate data, pops no items from the stack, and places a single item with
  * the value 0 onto the stack. The cost of this instruction is 2 gas (aka
  * `base`)"*.
  *
  * ==The cases that matter most are the two about what it is NOT==
  *
  * The byte sits one below `PUSH1` and the name says push, so the failure this
  * spec is really for is the operation being folded into that family: it would
  * then read an operand out of the code and be priced a tier too high. Neither
  * shows up as an error -- the program runs, one gas too dear, with the byte
  * after it swallowed -- so both are asserted rather than left to reading.
  */
class PushZeroSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Held above every test registration: Scala 3's initialization checker reads
  // a val declared below one as read-before-init, and reports it against the
  // first test in the class rather than against the val.
  private val table: OpcodeTable =
    OpcodeTable.original(schedule).adding(Operation(Opcode.Push0, Cost.Fixed(schedule.base)))

  private val environment: Environment = EvmFixtures.environment(withTable = table)

  private def runIn(where: Environment, gas: Int, program: Int*): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(
      EvmFixtures.message(transfersValue = true),
      Code(Bytes.fromArray(program.map(_.toByte).toArray)),
      BigInt(gas)
    )
    (frame, Interpreter.run(frame, where))

  "PUSH0" should "place a zero on the stack" in {
    val (frame, _) = runIn(environment, 1000, Opcode.Push0.code)
    assert(frame.stack.peek(0) == Right(Word.Zero), "the document's whole operation: a single item with the value 0")
  }

  it should "leave the stack holding exactly one word" in {
    val (frame, _) = runIn(environment, 1000, Opcode.Push0.code)
    assert(frame.stack.peek(1).isLeft, "it pops no items, so nothing sits under what it pushed")
  }

  it should "cost the base tier and not the tier the push family is priced at" in {
    val (frame, _) = runIn(environment, 1000, Opcode.Push0.code)
    // The two tiers differ in this schedule by construction, so a machine that
    // reached the push family's price fails here rather than agreeing by
    // coincidence -- which is what the fixture's distinct-values rule buys.
    assert(
      frame.gasLeft == BigInt(1000) - schedule.base && schedule.base != schedule.veryLow,
      "the document prices it at base, where every PUSH1..PUSH32 is priced at very low"
    )
  }

  it should "read no byte of code beyond its own" in {
    // The trailing byte is JUMPDEST, chosen because it is both a harmless
    // instruction and a non-zero value: a machine giving this operation an
    // operand width would push 0x5b instead of zero, where a byte that did
    // nothing and was also zero could not tell the two apart.
    val (frame, _) = runIn(environment, 1000, Opcode.Push0.code, Opcode.JumpDest.code)
    assert(frame.stack.peek(0) == Right(Word.Zero), "the byte after it is the next instruction, not its operand")
  }

  it should "advance one byte, so the next instruction runs" in {
    // Program: PUSH0, PUSH0, ADD. If the first consumed two bytes the second
    // would never run and ADD would fail for want of a second operand.
    val (frame, outcome) = runIn(environment, 1000, Opcode.Push0.code, Opcode.Push0.code, Opcode.Add.code)
    assert(
      outcome.map(_.getClass) == Right(classOf[Outcome.Stopped]) && frame.stack.peek(0) == Right(Word.Zero),
      "three operations run in sequence, which fixes the program counter's step at one"
    )
  }

  it should "fill the stack rather than overflow it at the limit" in {
    // The document's own second test case: "`5F5F..5F` (1024 times) --
    // successful execution, stack consists of 1024 items, all set to zero".
    val (frame, outcome) = runIn(environment, 1000000, List.fill(Stack.Limit)(Opcode.Push0.code)*)
    assert(
      outcome.map(_.getClass) == Right(classOf[Outcome.Stopped]) && frame.stack.depth == Stack.Limit,
      "one below the limit is not the limit: the document names the boundary and it is reachable"
    )
  }

  it should "overflow the stack one past the limit" in {
    // And its third: "`5F5F..5F` (1025 times) -- execution aborts due to out of
    // stack". The pair is what fixes the boundary; either alone admits an
    // off-by-one.
    val (_, outcome) = runIn(environment, 1000000, List.fill(Stack.Limit + 1)(Opcode.Push0.code)*)
    assert(outcome == Right(Outcome.Halted(Halt.StackOverflow)), "the 1025th has nowhere to go")
  }

  it should "not run under a table that does not carry it" in {
    val (_, outcome) = runIn(EvmFixtures.environment(), 1000, Opcode.Push0.code)
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidOpcode(Opcode.Push0.code))),
      "a network that has not adopted the proposal meets a byte naming no operation"
    )
  }
