package org.fukuii.evm

import org.fukuii.bytes.{Bytes, Hash}
import org.scalatest.flatspec.AnyFlatSpec

/** The loop, the charging, and the ends execution can reach.
  *
  * Expected values are `forks/frontier/vm/` at `ccaaaba58`: the prices from
  * `gas.py`, the order of operand, charge and program counter from each
  * operation in `instructions/`, and the two ends from `interpreter.py`'s loop
  * condition and its handling of an exceptional halt.
  */
class InterpreterSpec extends AnyFlatSpec:

  private val schedule = GasSchedule.Baseline
  private val table = OpcodeTable.baseline(schedule)

  private def frameOf(gas: Int, program: Int*): Frame =
    new Frame(EvmFixtures.message(), Code(Bytes.fromArray(program.map(_.toByte).toArray)), BigInt(gas))

  private def exec(gas: Int, program: Int*): (Frame, Either[Unsupported, Outcome]) =
    execFor(EvmFixtures.message(), EvmFixtures.environment(), gas, program*)

  private def execIn(
      environment: Environment,
      gas: Int,
      program: Int*
  ): (Frame, Either[Unsupported, Outcome]) =
    execFor(EvmFixtures.message(), environment, gas, program*)

  private def execFor(
      message: Message,
      environment: Environment,
      gas: Int,
      program: Int*
  ): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(message, Code(Bytes.fromArray(program.map(_.toByte).toArray)), BigInt(gas))
    (frame, Interpreter.run(frame, table, schedule, environment))

  private def wordOfAddress(byte: Int): Word =
    Word.fromBytes(Bytes.fromIArray(EvmFixtures.address(byte).toBytes))

  private def w(value: Int): Word = Word(BigInt(value))

  private def topicOf(value: Int): Hash = Hash.fromBytesTruncating(w(value).toBytes.toIArray)

  // Produced outside this project by two independent Keccak implementations
  // agreeing, rather than by the one under test.
  private val EmptyDigest = "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"

  private val WordOfFortyTwoDigest = "beced09521047d05b8960b7e7bcc1d1292cf3e4b2a6b63f48335cbde5f7545d2"

  // PUSH1 0x03, PUSH1 0x05, then the operation under test.
  private def afterBinary(gas: Int, operation: Int): (Frame, Either[Unsupported, Outcome]) =
    exec(gas, 0x60, 0x03, 0x60, 0x05, operation)

  // The operations this build does not run, named rather than counted. The set
  // is empty and stays a set: adding an operation to the table without an
  // implementation fails this, and so does an implementation that quietly stops
  // running.
  private val notYetBuilt: Set[Opcode] = Set.empty

  private def cannotRun(opcode: Opcode): Boolean =
    val (_, outcome) = exec(1000000, opcode.code)
    outcome.isLeft

  "running off the end of the code" should "end normally rather than exceptionally" in {
    val (_, outcome) = exec(100, 0x60, 0x01)
    assert(
      outcome == Right(Outcome.Stopped(BigInt(97), Bytes.Empty)),
      "the loop ends on the program counter, with no terminator required"
    )
  }

  "an empty program" should "stop immediately having spent nothing" in {
    val (_, outcome) = exec(100)
    assert(
      outcome == Right(Outcome.Stopped(BigInt(100), Bytes.Empty)),
      "there is nothing to run and nothing to charge for"
    )
  }

  "STOP" should "end execution before the rest of the code runs" in {
    val (frame, _) = exec(100, 0x00, 0x60, 0x01)
    assert(frame.stack.isEmpty, "the PUSH after STOP never ran")
  }

  it should "cost nothing" in {
    val (frame, _) = exec(100, 0x00)
    assert(frame.gasLeft == BigInt(100), "STOP makes no charge at all in the specification")
  }

  "a byte naming no operation" should "halt and report which byte it was" in {
    val (_, outcome) = exec(100, 0x0c)
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidOpcode(0x0c))),
      "a chain split is diagnosed from the byte, so it is carried"
    )
  }

  "an exceptional halt" should "leave no gas on the frame" in {
    val (frame, _) = exec(100, 0x0c)
    assert(frame.gasLeft == BigInt(0), "at this fork an exceptional halt consumes everything remaining")
  }

  "ADD" should "leave the sum on the stack" in {
    val (frame, _) = afterBinary(100, 0x01)
    assert(frame.stack.peek(0) == Right(w(8)), "three plus five")
  }

  it should "cost the very low tier" in {
    val (frame, _) = afterBinary(100, 0x01)
    assert(frame.gasLeft == BigInt(100 - 3 - 3 - 3), "two pushes at 3 and an ADD at 3")
  }

  "SUB" should "subtract the second operand from the top one" in {
    val (frame, _) = afterBinary(100, 0x03)
    assert(frame.stack.peek(0) == Right(w(2)), "the top is the left operand, so this is five minus three")
  }

  "MUL" should "cost the low tier rather than the very low one" in {
    val (frame, _) = afterBinary(100, 0x02)
    assert(frame.gasLeft == BigInt(100 - 3 - 3 - 5), "MUL is priced above ADD")
  }

  "LT" should "compare the top against the one below it" in {
    val (frame, _) = afterBinary(100, 0x10)
    assert(frame.stack.peek(0) == Right(Word.Zero), "five is not less than three")
  }

  "BYTE" should "take the index from the top and the word from below it" in {
    val (frame, _) = exec(100, 0x60, 0xff, 0x60, 0x1f, 0x1a)
    assert(frame.stack.peek(0) == Right(w(0xff)), "index 31 is the least significant byte")
  }

  "SIGNEXTEND" should "take the width from the top and the value from below it" in {
    val (frame, _) = exec(100, 0x60, 0xff, 0x60, 0x00, 0x0b)
    assert(frame.stack.peek(0) == Right(Word.MaxValue), "0xff extended from byte zero is negative one")
  }

  "EXP" should "charge for each byte of the exponent" in {
    val (frame, _) = exec(100, 0x61, 0x01, 0x00, 0x60, 0x02, 0x0a)
    assert(
      frame.gasLeft == BigInt(100 - 3 - 3 - (10 + 10 * 2)),
      "the exponent is the operand pushed first, and two bytes of it cost the base plus twice the per-byte rate"
    )
  }

  it should "charge only the base when the exponent is zero" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x02, 0x0a)
    assert(frame.gasLeft == BigInt(100 - 3 - 3 - 10), "a zero exponent occupies no bytes")
  }

  "an operation that cannot be paid for" should "halt out of gas" in {
    val (_, outcome) = exec(5, 0x60, 0x01, 0x60, 0x01, 0x01)
    assert(outcome == Right(Outcome.Halted(Halt.OutOfGas)), "two pushes cost six and only five were provided")
  }

  "an operation with nothing to take" should "halt under the stack" in {
    val (_, outcome) = exec(100, 0x01)
    assert(outcome == Right(Outcome.Halted(Halt.StackUnderflow)), "ADD takes two operands from an empty stack")
  }

  "PUSH1" should "step over its operand rather than running it" in {
    val (frame, _) = exec(100, 0x60, 0x00)
    assert(frame.stack.peek(0) == Right(Word.Zero) && frame.pc == 2, "the operand byte is data, not a STOP")
  }

  "PUSH32" should "zero-fill an operand the code runs out of" in {
    val (frame, _) = exec(100, 0x7f, 0x01)
    assert(frame.stack.peek(0) == Right(Word(BigInt(1) << 248)), "one byte read and thirty-one zeros after it")
  }

  "DUP1" should "copy the top of the stack" in {
    val (frame, _) = exec(100, 0x60, 0x07, 0x80)
    assert(frame.stack.depth == 2 && frame.stack.peek(1) == Right(w(7)), "the copy is above the original")
  }

  "DUP2" should "copy the element below the top" in {
    val (frame, _) = exec(100, 0x60, 0x01, 0x60, 0x02, 0x81)
    assert(frame.stack.peek(0) == Right(w(1)), "DUP2 names the second element, counting the top as the first")
  }

  "DUP1 against an empty stack" should "halt under the stack" in {
    val (_, outcome) = exec(100, 0x80)
    assert(outcome == Right(Outcome.Halted(Halt.StackUnderflow)), "there is nothing to duplicate")
  }

  "SWAP1" should "exchange the top with the element below it" in {
    val (frame, _) = exec(100, 0x60, 0x01, 0x60, 0x02, 0x90)
    assert(
      frame.stack.peek(0) == Right(w(1)) && frame.stack.peek(1) == Right(w(2)),
      "the two are exchanged, not rotated"
    )
  }

  "PC" should "report the position of the instruction that ran" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x58)
    assert(frame.stack.peek(0) == Right(w(2)), "the counter is read before it moves on")
  }

  "GAS" should "report what remains once the operation has been paid for" in {
    val (frame, _) = exec(100, 0x5a)
    assert(frame.stack.peek(0) == Right(w(98)), "the charge of two is made before the value is read")
  }

  "JUMP" should "continue at a marked destination" in {
    val (frame, _) = exec(100, 0x60, 0x03, 0x56, 0x5b)
    assert(frame.pc == 4, "the jump landed on the marker at position three and then ran it")
  }

  it should "halt when the destination carries no marker" in {
    val (_, outcome) = exec(100, 0x60, 0x00, 0x56)
    assert(outcome == Right(Outcome.Halted(Halt.InvalidJumpDestination)), "position zero holds a PUSH, not a JUMPDEST")
  }

  it should "halt when the destination is beyond anything addressable" in {
    val (_, outcome) = exec(100, (0x7f +: Seq.fill(32)(0xff)) :+ 0x56*)
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidJumpDestination)),
      "a destination no code position can equal is refused as a destination rather than overflowing an index"
    )
  }

  "JUMPI" should "fall through when the condition is zero" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x06, 0x57, 0x00, 0x5b)
    assert(frame.pc == 6, "the counter advanced by one rather than jumping to six")
  }

  it should "jump when the condition is not zero" in {
    val (frame, _) = exec(100, 0x60, 0x01, 0x60, 0x06, 0x57, 0x00, 0x5b)
    assert(frame.pc == 7, "the marker at six was reached and then run")
  }

  "MSTORE then MLOAD" should "return the word that was written" in {
    val (frame, _) = exec(100, 0x60, 0x2a, 0x60, 0x00, 0x52, 0x60, 0x00, 0x51)
    assert(frame.stack.peek(0) == Right(w(0x2a)), "memory round-trips a word at the offset it was written to")
  }

  "MSTORE" should "charge for the memory it makes the machine hold" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x00, 0x52)
    assert(frame.gasLeft == BigInt(100 - 3 - 3 - 3 - 3), "the base of three plus one word of memory at three")
  }

  "MSIZE" should "report the memory rounded up to a whole word" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x00, 0x53, 0x59)
    assert(frame.stack.peek(0) == Right(w(32)), "writing one byte at offset zero makes the machine hold a whole word")
  }

  "MSTORE8" should "write only the low byte of its operand" in {
    val (frame, _) = exec(200, 0x61, 0x01, 0xff, 0x60, 0x00, 0x53, 0x60, 0x00, 0x51)
    assert(frame.stack.peek(0) == Right(Word(BigInt(0xff) << 248)), "0x01ff stores as 0xff at the first byte of memory")
  }

  "an offset no memory can reach" should "halt out of gas rather than be allocated" in {
    val (_, outcome) = exec(1000000, (0x7f +: Seq.fill(32)(0xff)) :+ 0x51*)
    assert(
      outcome == Right(Outcome.Halted(Halt.OutOfGas)),
      "holding that much memory costs more than any frame can carry, so it is refused as unaffordable"
    )
  }

  "the operations this build cannot run" should "be none of them" in
    assert(
      Opcode.values.filter(cannotRun).toSet == notYetBuilt,
      "an operation that quietly stopped running would otherwise look like one that was never built"
    )

  // An entry saying its operation works out its own price, where the operation
  // is one this build prices from the table, is a table this build cannot run.
  // It is the condition that outlives every operation being implemented, and it
  // is reachable because a chain configuration produces the table.
  "a table entry priced against what this build expects" should "not be reported as a halt" in {
    val frame = frameOf(1000000, 0x60, 0x03, 0x60, 0x05, 0x01)
    val mismatched = table.adding(Operation(Opcode.Add, Cost.Computed))
    assert(
      Interpreter.run(frame, mismatched, schedule, EvmFixtures.environment()) == Left(Unsupported(Opcode.Add)),
      "a halt is a result a chain reaches, and this is not one"
    )
  }

  "a table that has had an operation removed" should "treat its byte as naming none" in {
    val frame = frameOf(100, 0xff)
    assert(
      Interpreter.run(frame, table.removing(Opcode.SelfDestruct), schedule, EvmFixtures.environment()) ==
        Right(Outcome.Halted(Halt.InvalidOpcode(0xff))),
      "a removed operation behaves exactly as an undefined byte, which is what scroll-tech/go-ethereum records at its own removal"
    )
  }

  "ADDRESS" should "report the account this invocation runs as" in {
    val (frame, _) = exec(100, 0x30)
    assert(frame.stack.peek(0) == Right(wordOfAddress(0x22)), "the target it runs as, not the one that called it")
  }

  it should "cost the base tier" in {
    val (frame, _) = exec(100, 0x30)
    assert(frame.gasLeft == BigInt(100 - 2), "every operation that only reads context is priced at the base tier")
  }

  "CALLER" should "report the account that called this invocation" in {
    val (frame, _) = exec(100, 0x33)
    assert(frame.stack.peek(0) == Right(wordOfAddress(0x11)), "the caller is the message's, not the transaction's")
  }

  "CALLVALUE" should "report the value sent with this invocation" in {
    val funded = new EvmFixtures.MapWorldState
    funded.balances(EvmFixtures.address(0x11)) = w(9)
    val (frame, _) = execFor(EvmFixtures.message(value = w(9)), EvmFixtures.environment(funded), 100, 0x34)
    assert(frame.stack.peek(0) == Right(w(9)), "the value rides on the message")
  }

  "ORIGIN" should "report the account that signed the transaction" in {
    val (frame, _) = exec(100, 0x32)
    assert(frame.stack.peek(0) == Right(wordOfAddress(0x99)), "the origin is the transaction's and outlives a frame")
  }

  "GASPRICE" should "report the price the transaction pays" in {
    val (frame, _) = exec(100, 0x3a)
    assert(frame.stack.peek(0) == Right(w(7)), "the price is fixed for the whole transaction")
  }

  "COINBASE" should "report the block's beneficiary" in {
    val (frame, _) = exec(100, 0x41)
    assert(frame.stack.peek(0) == Right(wordOfAddress(0xcc)), "the beneficiary of the block this runs in")
  }

  "TIMESTAMP" should "report the block's own time" in {
    val (frame, _) = exec(100, 0x42)
    assert(frame.stack.peek(0) == Right(Word(BigInt(1234567890))), "the block's time, not the current one")
  }

  "NUMBER" should "report the block's height" in {
    val (frame, _) = exec(100, 0x43)
    assert(frame.stack.peek(0) == Right(Word(BigInt(1000))), "the height of the block this runs in")
  }

  "DIFFICULTY" should "report the block's difficulty" in {
    val (frame, _) = exec(100, 0x44)
    assert(frame.stack.peek(0) == Right(Word(BigInt(0x0100))), "the difficulty of the block this runs in")
  }

  "GASLIMIT" should "report the block's gas limit" in {
    val (frame, _) = exec(100, 0x45)
    assert(frame.stack.peek(0) == Right(Word(BigInt(3141592))), "the block's limit, not the frame's remaining gas")
  }

  "BLOCKHASH" should "answer with the hash of a block inside the window" in {
    val (frame, _) = exec(100, 0x61, 0x03, 0xe7, 0x40)
    assert(
      frame.stack.peek(0) == Right(Word.fromBytes(Bytes.fromIArray(EvmFixtures.blockHashAt(BigInt(999)).toBytes))),
      "the block one back from the one being executed is inside the window"
    )
  }

  it should "answer with the hash of the oldest block the window reaches" in {
    val (frame, _) = exec(100, 0x61, 0x02, 0xe8, 0x40)
    assert(
      frame.stack.peek(0) == Right(Word.fromBytes(Bytes.fromIArray(EvmFixtures.blockHashAt(BigInt(744)).toBytes))),
      "256 blocks back is the last one the window admits, and the boundary is inclusive"
    )
  }

  it should "answer zero one block beyond the window" in {
    val (frame, _) = exec(100, 0x61, 0x02, 0xe7, 0x40)
    assert(frame.stack.peek(0) == Right(Word.Zero), "257 blocks back is outside the window and is not an error")
  }

  it should "answer zero for the block being executed" in {
    val (frame, _) = exec(100, 0x61, 0x03, 0xe8, 0x40)
    assert(frame.stack.peek(0) == Right(Word.Zero), "a block whose execution has not finished has no hash to give")
  }

  it should "answer zero for a number no chain could reach" in {
    val (frame, _) = exec(1000, (0x7f +: Seq.fill(32)(0xff)) :+ 0x40*)
    assert(
      frame.stack.peek(0) == Right(Word.Zero),
      "the window is compared at full precision, so an operand near the top of the range cannot wrap into it"
    )
  }

  it should "cost twenty" in {
    val (frame, _) = exec(100, 0x61, 0x03, 0xe7, 0x40)
    assert(frame.gasLeft == BigInt(100 - 3 - 20), "a push at 3 and a block-hash lookup at 20")
  }

  "CALLDATALOAD" should "read a whole word from the input" in {
    val data = EvmFixtures.bytesOf("00" * 31 + "2a")
    val (frame, _) = execFor(EvmFixtures.message(data = data), EvmFixtures.environment(), 100, 0x60, 0x00, 0x35)
    assert(frame.stack.peek(0) == Right(w(42)), "the word is read big-endian from the offset given")
  }

  it should "zero-fill where the input runs out" in {
    val data = EvmFixtures.bytesOf("ff")
    val (frame, _) = execFor(EvmFixtures.message(data = data), EvmFixtures.environment(), 100, 0x60, 0x00, 0x35)
    assert(
      frame.stack.peek(0) == Right(Word(BigInt(0xff) << 248)),
      "reading past the input pads rather than failing, so a one-byte input is a word with one byte in it"
    )
  }

  "CALLDATASIZE" should "report the length of the input" in {
    val data = EvmFixtures.bytesOf("aabbcc")
    val (frame, _) = execFor(EvmFixtures.message(data = data), EvmFixtures.environment(), 100, 0x36)
    assert(frame.stack.peek(0) == Right(w(3)), "the length in bytes, not in words")
  }

  "CALLDATACOPY" should "copy the input into memory" in {
    val data = EvmFixtures.bytesOf("aabbccdd")
    val (frame, _) =
      execFor(
        EvmFixtures.message(data = data),
        EvmFixtures.environment(),
        100,
        0x60,
        0x04,
        0x60,
        0x00,
        0x60,
        0x00,
        0x37
      )
    assert(frame.memory.read(0, 4) == data, "the operands are memory offset, input offset and size, in that order")
  }

  it should "zero-fill where the input runs out" in {
    val data = EvmFixtures.bytesOf("aabb")
    val (frame, _) =
      execFor(
        EvmFixtures.message(data = data),
        EvmFixtures.environment(),
        100,
        0x60,
        0x04,
        0x60,
        0x00,
        0x60,
        0x00,
        0x37
      )
    assert(
      frame.memory.read(0, 4) == EvmFixtures.bytesOf("aabb0000"),
      "a copy longer than the input is padded rather than refused"
    )
  }

  it should "charge the settled part, the whole words copied, and the memory taken" in {
    val data = EvmFixtures.bytesOf("aabbccdd")
    val (frame, _) =
      execFor(
        EvmFixtures.message(data = data),
        EvmFixtures.environment(),
        100,
        0x60,
        0x04,
        0x60,
        0x00,
        0x60,
        0x00,
        0x37
      )
    assert(
      frame.gasLeft == BigInt(100 - 9 - 3 - 3 - 3),
      "three pushes at 3, then a base of 3, one word at 3, and one word of memory at 3"
    )
  }

  it should "charge no memory at all for a copy of nothing" in {
    val (_, outcome) =
      exec(100, (0x60 +: Seq(0x00, 0x60, 0x00, 0x7f)) ++ Seq.fill(32)(0xff) :+ 0x37*)
    assert(
      outcome == Right(Outcome.Stopped(BigInt(100 - 3 - 3 - 3 - 3), Bytes.Empty)),
      "a zero-length copy skips the extension entirely, so an offset no memory could reach stays affordable"
    )
  }

  "CODESIZE" should "report the length of the code being run" in {
    val (frame, _) = exec(100, 0x38)
    assert(frame.stack.peek(0) == Right(w(1)), "the running code, which here is the single byte of this program")
  }

  "CODECOPY" should "copy the running code into memory" in {
    val (frame, _) = exec(100, 0x60, 0x04, 0x60, 0x00, 0x60, 0x00, 0x39)
    assert(
      frame.memory.read(0, 4) == EvmFixtures.bytesOf("60046000"),
      "the code copied is this program's own bytes, operands included"
    )
  }

  "BALANCE" should "report the balance of the account named" in {
    val world = new EvmFixtures.MapWorldState
    world.balances(EvmFixtures.address(0x05)) = w(1234)
    val (frame, _) = execIn(EvmFixtures.environment(world), 100, (0x73 +: Seq.fill(20)(0x05)) :+ 0x31*)
    assert(frame.stack.peek(0) == Right(w(1234)), "the balance is read for the account the operand names")
  }

  it should "report zero for an account that does not exist" in {
    val (frame, _) = execIn(EvmFixtures.environment(), 100, (0x73 +: Seq.fill(20)(0x07)) :+ 0x31*)
    assert(
      frame.stack.peek(0) == Right(Word.Zero),
      "an account that was never written has a zero balance, not an error"
    )
  }

  it should "read only the low twenty bytes of its operand" in {
    val world = new EvmFixtures.MapWorldState
    world.balances(EvmFixtures.address(0x05)) = w(1234)
    val operand = Seq.fill(12)(0xff) ++ Seq.fill(20)(0x05)
    val (frame, _) = execIn(EvmFixtures.environment(world), 100, (0x7f +: operand) :+ 0x31*)
    assert(frame.stack.peek(0) == Right(w(1234)), "an operand wider than an address is masked rather than refused")
  }

  "EXTCODESIZE" should "report the length of another account's code" in {
    val world = new EvmFixtures.MapWorldState
    world.codes(EvmFixtures.address(0x05)) = EvmFixtures.bytesOf("6001")
    val (frame, _) = execIn(EvmFixtures.environment(world), 100, (0x73 +: Seq.fill(20)(0x05)) :+ 0x3b*)
    assert(frame.stack.peek(0) == Right(w(2)), "the length of the code the account carries")
  }

  it should "report zero for an account with no code" in {
    val (frame, _) = execIn(EvmFixtures.environment(), 100, (0x73 +: Seq.fill(20)(0x05)) :+ 0x3b*)
    assert(frame.stack.peek(0) == Right(Word.Zero), "no code and no account answer alike, and neither is an error")
  }

  "EXTCODECOPY" should "copy another account's code into memory" in {
    val world = new EvmFixtures.MapWorldState
    world.codes(EvmFixtures.address(0x05)) = EvmFixtures.bytesOf("6001")
    val program = Seq(0x60, 0x02, 0x60, 0x00, 0x60, 0x00, 0x73) ++ Seq.fill(20)(0x05) :+ 0x3c
    val (frame, _) = execIn(EvmFixtures.environment(world), 100, program*)
    assert(
      frame.memory.read(0, 2) == EvmFixtures.bytesOf("6001"),
      "the account is taken first and the three copying operands after it"
    )
  }

  it should "charge the external base rather than the very low tier" in {
    val world = new EvmFixtures.MapWorldState
    world.codes(EvmFixtures.address(0x05)) = EvmFixtures.bytesOf("6001")
    val program = Seq(0x60, 0x02, 0x60, 0x00, 0x60, 0x00, 0x73) ++ Seq.fill(20)(0x05) :+ 0x3c
    val (frame, _) = execIn(EvmFixtures.environment(world), 100, program*)
    assert(
      frame.gasLeft == BigInt(100 - 9 - 3 - 20 - 3 - 3),
      "four pushes, then a base of 20 rather than 3, one word copied at 3, and one word of memory at 3"
    )
  }

  "SLOAD" should "answer zero for a slot never written" in {
    val (frame, _) = execIn(EvmFixtures.environment(), 100, 0x60, 0x01, 0x54)
    assert(frame.stack.peek(0) == Right(Word.Zero), "an unwritten slot holds zero rather than nothing")
  }

  it should "cost fifty" in {
    val (frame, _) = execIn(EvmFixtures.environment(), 100, 0x60, 0x01, 0x54)
    assert(frame.gasLeft == BigInt(100 - 3 - 50), "a push at 3 and a storage read at 50")
  }

  it should "answer with what SSTORE wrote" in {
    val (frame, _) = execIn(EvmFixtures.environment(), 30000, 0x60, 0x2a, 0x60, 0x01, 0x55, 0x60, 0x01, 0x54)
    assert(frame.stack.peek(0) == Right(w(42)), "a read after a write in the same invocation sees the write")
  }

  "SSTORE" should "write under the account this invocation runs as" in {
    val environment = EvmFixtures.environment()
    val _ = execIn(environment, 30000, 0x60, 0x2a, 0x60, 0x01, 0x55)
    assert(
      environment.world.storageAt(EvmFixtures.address(0x22), w(1)) == w(42),
      "storage belongs to the target it runs as, never to the caller"
    )
  }

  it should "cost the setting price when the slot held nothing" in {
    val (frame, _) = execIn(EvmFixtures.environment(), 30000, 0x60, 0x2a, 0x60, 0x01, 0x55)
    assert(frame.gasLeft == BigInt(30000 - 3 - 3 - 20000), "taking a slot from zero to a value is the expensive case")
  }

  it should "cost the resetting price when the slot already held a value" in {
    val (frame, _) =
      execIn(EvmFixtures.environment(), 30000, 0x60, 0x2a, 0x60, 0x01, 0x55, 0x60, 0x2b, 0x60, 0x01, 0x55)
    assert(
      frame.gasLeft == BigInt(30000 - 3 - 3 - 20000 - 3 - 3 - 5000),
      "only a slot that held zero costs the setting price"
    )
  }

  it should "cost the resetting price when the slot is cleared" in {
    val (frame, _) =
      execIn(EvmFixtures.environment(), 30000, 0x60, 0x2a, 0x60, 0x01, 0x55, 0x60, 0x00, 0x60, 0x01, 0x55)
    assert(
      frame.gasLeft == BigInt(30000 - 3 - 3 - 20000 - 3 - 3 - 5000),
      "clearing is charged the resetting price and earns its refund separately"
    )
  }

  it should "earn a refund for clearing a slot that held a value" in {
    val (frame, _) =
      execIn(EvmFixtures.environment(), 30000, 0x60, 0x2a, 0x60, 0x01, 0x55, 0x60, 0x00, 0x60, 0x01, 0x55)
    assert(frame.refundCounter == BigInt(15000), "the refund is counted on the frame and never returned to its gas")
  }

  it should "earn no refund for writing a value" in {
    val (frame, _) = execIn(EvmFixtures.environment(), 30000, 0x60, 0x2a, 0x60, 0x01, 0x55)
    assert(frame.refundCounter == BigInt(0), "only clearing a slot that held something earns anything back")
  }

  it should "earn no refund for clearing a slot that already held nothing" in {
    val (frame, _) = execIn(EvmFixtures.environment(), 30000, 0x60, 0x00, 0x60, 0x01, 0x55)
    assert(frame.refundCounter == BigInt(0), "there was nothing to clear, so nothing is given back")
  }

  // ── The digest of a region of memory ─────────────────────────────────────

  "KECCAK256" should "answer with the digest of an empty region" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x00, 0x20)
    assert(
      frame.stack.peek(0) == Right(Word.fromBytes(EvmFixtures.bytesOf(EmptyDigest))),
      "a region of no bytes is the empty input, not an absent one"
    )
  }

  it should "cost the settled part alone where nothing is read" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x00, 0x20)
    assert(
      frame.gasLeft == BigInt(100 - 3 - 3 - 30),
      "two pushes at 3 and the settled part at 30, with no word to hash"
    )
  }

  it should "answer with the digest of the bytes the region holds" in {
    val (frame, _) = exec(100, 0x60, 0x2a, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00, 0x20)
    assert(
      frame.stack.peek(0) == Right(Word.fromBytes(EvmFixtures.bytesOf(WordOfFortyTwoDigest))),
      "the region read is the one the operands name"
    )
  }

  it should "cost a word of hashing and a word of memory" in {
    val (frame, _) = exec(100, 0x60, 0x20, 0x60, 0x00, 0x20)
    assert(
      frame.gasLeft == BigInt(100 - 3 - 3 - 30 - 6 - 3),
      "two pushes at 3, a settled 30, one word hashed at 6, and one word of memory at 3"
    )
  }

  // ── What the invocation emitted ──────────────────────────────────────────

  "LOG0" should "emit an entry under the account this invocation runs as" in {
    val (frame, _) = exec(1000, 0x60, 0x00, 0x60, 0x00, 0xa0)
    assert(
      frame.logs.map(_.address) == Vector(EvmFixtures.address(0x22)),
      "a log belongs to the account it runs as, never to the caller"
    )
  }

  it should "emit no topics" in {
    val (frame, _) = exec(1000, 0x60, 0x00, 0x60, 0x00, 0xa0)
    assert(frame.logs.head.topics.isEmpty, "the count comes from which operation this is")
  }

  it should "cost the settled part" in {
    val (frame, _) = exec(1000, 0x60, 0x00, 0x60, 0x00, 0xa0)
    assert(frame.gasLeft == BigInt(1000 - 3 - 3 - 375), "two pushes at 3 and a settled 375")
  }

  it should "carry the bytes the region holds" in {
    val (frame, _) = exec(1000, 0x60, 0x2a, 0x60, 0x00, 0x53, 0x60, 0x01, 0x60, 0x00, 0xa0)
    assert(frame.logs.head.data == EvmFixtures.bytesOf("2a"), "the data is read from memory, not from the stack")
  }

  it should "charge for every byte carried" in {
    val (frame, _) = exec(1000, 0x60, 0x2a, 0x60, 0x00, 0x53, 0x60, 0x01, 0x60, 0x00, 0xa0)
    assert(
      frame.gasLeft == BigInt(1000 - 3 - 3 - 3 - 3 - 3 - 3 - 375 - 8),
      "the store costs three pushes and a word of memory, and the entry costs 375 plus 8 for its one byte"
    )
  }

  "LOG1" should "take its topic from below the region on the stack" in {
    val (frame, _) = exec(1000, 0x60, 0x07, 0x60, 0x00, 0x60, 0x00, 0xa1)
    assert(
      frame.logs.head.topics == Seq(topicOf(7)),
      "the region is taken first and the topics after it"
    )
  }

  it should "cost one topic more than an entry with none" in {
    val (frame, _) = exec(1000, 0x60, 0x07, 0x60, 0x00, 0x60, 0x00, 0xa1)
    assert(frame.gasLeft == BigInt(1000 - 3 - 3 - 3 - 375 - 375), "three pushes at 3, a settled 375, one topic at 375")
  }

  "LOG4" should "take four topics in the order the stack lists them" in {
    val program =
      Seq(0x60, 0x04, 0x60, 0x03, 0x60, 0x02, 0x60, 0x01, 0x60, 0x00, 0x60, 0x00, 0xa4)
    val (frame, _) = exec(4000, program*)
    assert(
      frame.logs.head.topics == Seq(topicOf(1), topicOf(2), topicOf(3), topicOf(4)),
      "the first topic is the one nearest the top of the stack"
    )
  }

  it should "cost four topics" in {
    val program =
      Seq(0x60, 0x04, 0x60, 0x03, 0x60, 0x02, 0x60, 0x01, 0x60, 0x00, 0x60, 0x00, 0xa4)
    val (frame, _) = exec(4000, program*)
    assert(
      frame.gasLeft == BigInt(4000 - 6 * 3 - 375 - 4 * 375),
      "six pushes at 3, a settled 375, and four topics at 375 each"
    )
  }

  "several entries" should "be kept in the order they were emitted" in {
    val program = Seq(0x60, 0x01, 0x60, 0x00, 0xa0, 0x60, 0x00, 0x60, 0x00, 0xa0)
    val (frame, _) = exec(4000, program*)
    assert(
      frame.logs.map(_.data.length) == Vector(1, 0),
      "a receipt lists entries in the order they happened, so the order is part of the record"
    )
  }

  // ── Ending this invocation with something to hand back ───────────────────

  "RETURN" should "hand back the bytes the region holds" in {
    val (_, outcome) = exec(100, 0x60, 0x2a, 0x60, 0x00, 0x53, 0x60, 0x01, 0x60, 0x00, 0xf3)
    assert(
      outcome == Right(Outcome.Stopped(BigInt(100 - 3 - 3 - 3 - 3 - 3 - 3), EvmFixtures.bytesOf("2a"))),
      "the answer is read from memory and the operation itself has no settled price"
    )
  }

  it should "end execution before the rest of the code runs" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x00, 0xf3, 0x60, 0x01)
    assert(frame.stack.isEmpty, "the PUSH after RETURN never ran")
  }

  it should "leave the program counter where it stood" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x00, 0xf3, 0x60, 0x01)
    assert(frame.pc == 4, "the specification does not move it, and a counter past the end would resume nowhere")
  }

  it should "hand back nothing where the region is empty" in {
    val (_, outcome) = exec(100, 0x60, 0x00, 0x60, 0x00, 0xf3)
    assert(outcome == Right(Outcome.Stopped(BigInt(100 - 3 - 3), Bytes.Empty)), "an empty answer is not a missing one")
  }
