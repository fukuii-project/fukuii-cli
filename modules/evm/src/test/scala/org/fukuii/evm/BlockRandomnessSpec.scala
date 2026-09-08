package org.fukuii.evm

import org.fukuii.bytes.{Bytes, Hash}
import org.scalatest.flatspec.AnyFlatSpec

/** What the operation at `0x44` reports, on both sides of the rule that changes
  * it.
  *
  * ==The byte is in the original table, so no case here adds it==
  *
  * Which separates this spec from [[BaseFeeSpec]] and [[ChainIdSpec]], where the
  * operation joins the table at a fork and every case has to put it there. This
  * operation has been in the table since the first block of every network in
  * scope, at the same price, and what a fork changes is the value it answers
  * with. **So the fixture that varies here is the rules, not the table.**
  *
  * Expected behavior is `ethereum/EIPs` @ `dbfa6bee8` (2026-08-26),
  * `EIPS/eip-4399.md` (Final): *"Beginning with `TRANSITION_BLOCK`, the
  * `DIFFICULTY (0x44)` instruction **MUST** return the value of the `mixHash`
  * field"* (`:44`), and *"The gas cost of the `DIFFICULTY (0x44)` opcode remains
  * unchanged"* (`:46`). Read against `ethereum/execution-specs` @ `20f7f6271a`
  * (2026-08-26), whose two implementations differ in the member they read and in
  * nothing else: `forks/gray_glacier/vm/instructions/block.py` pushes
  * `U256(...block_env.difficulty)` and
  * `forks/paris/vm/instructions/block.py:195` pushes
  * `U256.from_be_bytes(evm.message.block_env.prev_randao)`.
  *
  * ==The two cases that matter most are the crossed ones==
  *
  * A machine reading the wrong member passes every case where only one of the
  * two values is interesting. So the block below carries a difficulty AND a
  * randomness that are different values, and each reading is asserted to answer
  * its own -- which is the one arrangement neither member can satisfy by
  * accident.
  */
class BlockRandomnessSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Held above every test registration: Scala 3's initialization checker reads
  // a val declared below one as read-before-init and reports it against the
  // first test in the class rather than against the val.

  /** A difficulty that is not the randomness below it, and is not a value any
    * reading of those 32 bytes produces.
    */
  private val Difficulty: BigInt = BigInt(0x0100)

  private val Randomness: Hash = EvmFixtures.hash(0xab)

  private val OtherRandomness: Hash = EvmFixtures.hash(0x3c)

  /** The 32 bytes as the machine word a big-endian reading of them gives. */
  private def wordOf(value: Hash): Word = Word.fromBytes(Bytes.fromIArray(value.toBytes))

  private def environmentUnder(reading: BlockRandomness, randomness: Option[Hash]): Environment =
    new Environment(
      new JournaledWorldState(new EvmFixtures.MapWorldState),
      EvmFixtures.blockHashAt,
      EvmFixtures.block.copy(difficulty = Difficulty, prevRandao = randomness),
      EvmFixtures.transaction,
      EvmFixtures.chainId,
      EvmFixtures.rules.copy(blockRandomness = reading)
    )

  private def runIn(environment: Environment, gas: Int, program: Int*): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(
      EvmFixtures.message(transfersValue = true),
      Code(Bytes.fromArray(program.map(_.toByte).toArray)),
      BigInt(gas)
    )
    (frame, Interpreter.run(frame, environment))

  private def answerUnder(reading: BlockRandomness, randomness: Option[Hash]): Either[Halt, Word] =
    val (frame, _) = runIn(environmentUnder(reading, randomness), 1000, Opcode.Difficulty.code)
    frame.stack.peek(0)

  "the operation at 0x44 where no beacon supplies randomness" should "push the block's difficulty" in
    assert(
      answerUnder(BlockRandomness.Unavailable, None) == Right(Word(Difficulty)),
      "the operation answers the block's own difficulty, taking no operand to do it"
    )

  it should "push the difficulty even where the block carries a randomness value" in
    // The first crossed case. A block below the fork still has 32 bytes in that
    // header slot -- they are a mining artifact there -- so a machine deciding
    // from the carrier rather than from the rules would answer with them.
    assert(
      answerUnder(BlockRandomness.Unavailable, Some(Randomness)) == Right(Word(Difficulty)),
      "a filled randomness member decided the reading, which is the fork's decision and not the block's"
    )

  "the operation at 0x44 under EIP-4399" should "push the randomness the block carries" in
    assert(
      answerUnder(BlockRandomness.Eip4399, Some(Randomness)) == Right(wordOf(Randomness)),
      "the document supplants the return value with the beacon chain's randomness"
    )

  it should "push the difficulty for no value of the randomness" in
    // The second crossed case, and the sharper one: the block carries a
    // difficulty the whole time, so a machine that failed to switch members
    // answers plausibly rather than failing.
    assert(
      answerUnder(BlockRandomness.Eip4399, Some(Randomness)) != Right(Word(Difficulty)),
      "the operation answered the difficulty under rules that supplant it"
    )

  it should "follow the block rather than answer a constant" in
    assert(
      answerUnder(BlockRandomness.Eip4399, Some(OtherRandomness)) == Right(wordOf(OtherRandomness)),
      "a second block gets its own randomness back, which no compiled-in value can do for both"
    )

  it should "read the 32 bytes big-endian" in
    // Stated against a value whose two ends differ, so a little-endian reading
    // produces a different word. The fixture bytes are uniform, so this uses one
    // that is not.
    assert(
      answerUnder(BlockRandomness.Eip4399, Hash.fromHex("0x" + "00" * 31 + "01").toOption) ==
        Right(Word(BigInt(1))),
      "the trailing byte is the least significant, which is what from_be_bytes means"
    )

  it should "push zero where the randomness genuinely is zero" in
    // Distinct from the absent case below, and the distinction is why the
    // member is an option. Zero is a legal randomness value; a block below the
    // fork supplies none. Both read as zero if the absence is defaulted, and
    // only one of them is a chain state.
    assert(
      answerUnder(BlockRandomness.Eip4399, Some(EvmFixtures.hash(0))) == Right(Word(BigInt(0))),
      "a randomness of zero is a value and must be answered as one"
    )

  it should "refuse a block carrying no randomness rather than answer for it" in {
    // The configuration this cannot be reached from a chain by: rules answering
    // the later reading over a block that supplies nothing. Raised rather than
    // returned, because there is no caller who could act on it.
    val thrown = intercept[IllegalStateException] {
      runIn(environmentUnder(BlockRandomness.Eip4399, None), 1000, Opcode.Difficulty.code)
    }
    assert(
      thrown.getMessage.contains("carries none"),
      "the refusal must name what is missing, so the rule set that omitted it can be found"
    )
  }

  "the price of the operation at 0x44" should "be the base tier where the difficulty is reported" in {
    val (frame, _) = runIn(environmentUnder(BlockRandomness.Unavailable, None), 1000, Opcode.Difficulty.code)
    assert(
      frame.gasLeft == BigInt(1000) - schedule.base,
      "the operation is priced at the base tier, read from the schedule rather than fixed here"
    )
  }

  it should "be the same base tier where the randomness is reported" in {
    // The half the document states in its own sentence, and the half a change
    // to this operation is most likely to get wrong: it is easy to add a
    // repricing to a delta that supplants a value.
    val (frame, _) =
      runIn(environmentUnder(BlockRandomness.Eip4399, Some(Randomness)), 1000, Opcode.Difficulty.code)
    assert(
      frame.gasLeft == BigInt(1000) - schedule.base,
      "the document states the gas cost remains unchanged, and this reading moved it"
    )
  }

  it should "leave the stack holding one word under either reading" in {
    val (frame, _) =
      runIn(environmentUnder(BlockRandomness.Eip4399, Some(Randomness)), 1000, Opcode.Difficulty.code)
    assert(
      frame.stack.peek(1).isLeft,
      "it uses no stack argument, so nothing sits under what it pushed"
    )
  }
