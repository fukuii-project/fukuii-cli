package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The operation that copies one region of memory to another.
  *
  * ==It is not in the original table, so every case here adds it==
  *
  * [[OpcodeTable.original]] does not carry the operation, which is what makes a
  * network that has not adopted the proposal refuse the byte. Each case below
  * runs a table that has been given it, working out its own price -- the same
  * arrangement [[InitcodeMeteringSpec]] uses for the other operation whose
  * charge depends on its operands.
  *
  * The four copies the document publishes are the sibling
  * [[MemoryCopyPropSpec]]'s, as one table of named vectors. What is here is the
  * charge, the boundaries, and the two properties the vectors cannot reach: an
  * empty copy, and a copy that has to grow memory.
  */
class MemoryCopySpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Held above every test registration: Scala 3's initialization checker reads
  // a val declared below one as read-before-init, and reports it against the
  // first test in the class rather than against the val.
  private val table: OpcodeTable =
    OpcodeTable.original(schedule).adding(Operation(Opcode.MCopy, Cost.Computed))

  private val environment: Environment = EvmFixtures.environment(withTable = table)

  /** The figures the document's own worked examples are quoted at.
    *
    * *"`g_verylow = 3`"* and *"`g_copy = 3 * words_copied`"* (`ethereum/EIPs` @
    * `d2a64c2d4`, `EIPS/eip-5656.md`, Final). [[EvmFixtures.schedule]] holds
    * neither, deliberately, so reproducing the document's *"gas used: 6"* takes
    * a schedule carrying its two figures rather than this project's fixture
    * ones.
    */
  private val documentPrices: GasSchedule = schedule.copy(veryLow = BigInt(3), copyPerWord = BigInt(3))

  private val atDocumentPrices: Environment =
    EvmFixtures.environment(
      withTable = OpcodeTable.original(documentPrices).adding(Operation(Opcode.MCopy, Cost.Computed)),
      withSchedule = documentPrices
    )

  /** Runs a single `MCOPY` with `seeded` already in memory and the operands
    * placed directly, and answers the frame it ran in.
    *
    * Placing the operands rather than compiling pushes is what makes
    * [[Frame.gasLeft]] readable as the operation's own charge: a program would
    * add three pushes to every figure below.
    */
  private def copying(
      where: Environment,
      gas: Int,
      seeded: Bytes,
      destination: Long,
      source: Long,
      size: Long
  ): Frame =
    val frame = new Frame(
      EvmFixtures.message(transfersValue = false),
      Code(Bytes.fromArray(Array(Opcode.MCopy.code.toByte))),
      BigInt(gas)
    )
    if seeded.length > 0 then frame.memory.write(0, seeded)
    val _ = frame.stack.push(EvmFixtures.word(size))
    val _ = frame.stack.push(EvmFixtures.word(source))
    val _ = frame.stack.push(EvmFixtures.word(destination))
    val _ = Interpreter.run(frame, where)
    frame

  private val oneWord: Bytes = EvmFixtures.bytesOf("00" * 32)

  private val twoWords: Bytes = EvmFixtures.bytesOf("00" * 64)

  private val threeWords: Bytes = EvmFixtures.bytesOf("00" * 96)

  "MCOPY" should "cost the six gas the document's worked examples state" in {
    val frame = copying(atDocumentPrices, 1000, twoWords, destination = 0L, source = 32L, size = 32L)
    assert(
      frame.gasLeft == BigInt(1000 - 6),
      "each of the document's four cases states \"gas used: 6\", which is g_verylow 3 plus 3 for one word"
    )
  }

  it should "charge the very-low tier plus the per-word figure for each whole word" in {
    // Three words are seeded so that both regions sit inside memory already
    // written. A case whose furthest byte ran past the mark would carry an
    // expansion charge inside the figure, and the two would be indistinguishable
    // -- which is how this case was first written, and it read as the operation
    // charging three gas too much.
    val frame = copying(environment, 1000, threeWords, destination = 0L, source = 33L, size = 33L)
    assert(
      frame.gasLeft == BigInt(1000) - (schedule.veryLow + schedule.copyPerWord * 2),
      "thirty-three bytes is two whole words, so the per-word figure is spent twice and nothing grows"
    )
  }

  it should "charge the tier alone for a copy of nothing" in {
    val frame = copying(environment, 1000, oneWord, destination = 0L, source = 0L, size = 0L)
    assert(
      frame.gasLeft == BigInt(1000) - schedule.veryLow,
      "no words are copied, so nothing beyond the settled part is spent"
    )
  }

  it should "grow memory for neither region when it copies nothing" in {
    // The specification skips a zero-size region outright rather than measuring
    // it (`ethereum/execution-specs` @ `0cc100eb1`, `forks/cancun/vm/gas.py`,
    // `calculate_gas_extend_memory`), so an empty copy at an offset no memory
    // could reach is affordable rather than refused.
    val frame = copying(environment, 1000, Bytes.Empty, destination = 1L << 40, source = 1L << 40, size = 0L)
    assert(frame.memory.size == 0, "an operation that touches nothing allocates nothing")
  }

  it should "grow memory to reach the furthest of its two regions" in {
    val frame = copying(environment, 100000, oneWord, destination = 64L, source = 0L, size = 32L)
    assert(frame.memory.size == 96, "the copy lands at 64 and runs 32 bytes, so memory holds three words after it")
  }

  it should "read zeroes from past the end of what was written" in {
    // The source runs past the seeded word, so the tail of the copy comes from
    // memory the expansion had just zero-filled.
    val frame = copying(environment, 100000, oneWord, destination = 32L, source = 16L, size = 32L)
    assert(
      frame.memory.read(48, 16) == EvmFixtures.bytesOf("00" * 16),
      "memory past the mark reads as zeroes, so a copy from there writes zeroes"
    )
  }

  it should "halt when it cannot afford the copy" in {
    val frame = copying(environment, 6, oneWord, destination = 0L, source = 0L, size = 32L)
    assert(frame.gasLeft == BigInt(0), "an exceptional halt keeps nothing")
  }

  it should "be permitted where the invocation may not change state" in {
    // Memory is the frame's own, so nothing about this operation is a state
    // change -- and the document places no restriction on it. The contrast is
    // TSTORE, which the sibling proposal does refuse in this context.
    val frame = new Frame(
      EvmFixtures.message(transfersValue = false, isStatic = true),
      Code(Bytes.fromArray(Array(Opcode.MCopy.code.toByte))),
      BigInt(1000)
    )
    frame.memory.write(0, EvmFixtures.bytesOf("ff" * 32))
    val _ = frame.stack.push(EvmFixtures.word(32))
    val _ = frame.stack.push(EvmFixtures.word(0))
    val _ = frame.stack.push(EvmFixtures.word(32))
    val _ = Interpreter.run(frame, environment)
    assert(
      frame.memory.read(32, 32) == EvmFixtures.bytesOf("ff" * 32),
      "a static invocation copies memory like any other"
    )
  }

  "a chain that has not adopted the proposal" should "run no operation at the byte" in {
    val frame = new Frame(
      EvmFixtures.message(transfersValue = false),
      Code(Bytes.fromArray(Array(Opcode.MCopy.code.toByte))),
      BigInt(1000)
    )
    val outcome = Interpreter.run(frame, EvmFixtures.environment())
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidOpcode(Opcode.MCopy.code))),
      "the byte is undefined until a rule set adds it, and an undefined byte halts"
    )
  }
