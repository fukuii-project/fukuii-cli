package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The wrapper around BLAKE2b's compression function: what it charges, what it
  * refuses, and what the seam between those two costs a caller.
  *
  * ==The compression function itself is NOT the subject==
  *
  * `org.fukuii.crypto.Blake2bSpec` certifies `F` against EIP-152's own vectors,
  * against RFC 7693 and against the provider's complete BLAKE2b. Nothing here
  * asserts a digest, because a second copy of that certification would add a
  * place for it to go stale without adding an oracle. What is left once the
  * mixing is somebody else's is this native's own contract: a price read out of
  * the caller's own input, a refusal, and the order of the two.
  *
  * ==The width and the output length below are the document's, not the
  * module's==
  *
  * Both are stated as literals rather than read from `Blake2b`, so that a case
  * here disagrees with an implementation that moved one of them rather than
  * moving with it.
  *
  * Expected behavior is `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-152.md`
  * (Final): a precompile *"at address `0x09`"* whose input is *"tightly
  * encoded, taking exactly 213 bytes"*, whose first field is *"`rounds` - the
  * number of rounds - 32-bit unsigned big-endian word"*, and where *"Each
  * operation will cost `GFROUND * rounds` gas"*. Read against
  * `ethereum/execution-specs` @ `20f7f6271a`,
  * `src/ethereum/forks/istanbul/vm/precompiled_contracts/blake2f.py`.
  */
class Blake2fPrecompileSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Held above every test registration: Scala 3's initialization checker reads
  // a val declared below one as read-before-init, and reports it against the
  // first test in the class rather than against the val.
  private val perRound: BigInt = schedule.precompileBlake2fPerRound

  private val blake2f: Precompile = Precompile.Blake2f(perRound)

  /** EIP-152's packed width, and the length of what it answers with. */
  private val PackedWidth: Int = 213
  private val AnswerWidth: Int = 64

  /** The packed argument, carrying a round count and a final-block byte over a
    * state, a message and two offsets of zeros.
    *
    * Those three are zeros because nothing here asserts a digest, and a
    * well-formed argument is well-formed whatever they hold.
    */
  private def packed(rounds: Long, finalFlag: Int): Bytes =
    val out = new Array[Byte](PackedWidth)
    out(0) = ((rounds >>> 24) & 0xff).toByte
    out(1) = ((rounds >>> 16) & 0xff).toByte
    out(2) = ((rounds >>> 8) & 0xff).toByte
    out(3) = (rounds & 0xff).toByte
    out(PackedWidth - 1) = finalFlag.toByte
    Bytes.fromArray(out)

  private val wellFormed: Bytes = packed(rounds = 12, finalFlag = 1)

  private val wrongWidth: Bytes = Bytes.fromArray(new Array[Byte](PackedWidth - 1))

  private val wrongFinalFlag: Bytes = packed(rounds = 12, finalFlag = 2)

  /** A round count whose four bytes are all different, so that reading them in
    * the other order gives a different answer rather than the same one.
    */
  private val distinguishingRounds: Long = 0x01020304L

  /** The top of the 32-bit unsigned range, which is where a count assembled
    * through a signed `Int` reads as negative.
    */
  private val topOfRange: Long = 0xffffffffL

  private def roundsIn(input: Bytes): Long =
    val bytes = input.toIArray
    ((bytes(0) & 0xffL) << 24) | ((bytes(1) & 0xffL) << 16) | ((bytes(2) & 0xffL) << 8) | (bytes(3) & 0xffL)

  /** The specification's own order for a refused argument: the rounds are
    * charged, and the refusal comes after.
    *
    * A stand-in rather than a second implementation -- its only job is to be
    * the other order, so that the equivalence this native's contract rests on
    * is asserted against something rather than argued.
    */
  private def chargingBeforeRefusing(gasPerRound: BigInt): Precompile =
    new Precompile:
      def gasFor(input: Bytes): BigInt = gasPerRound * BigInt(roundsIn(input))
      def run(input: Bytes): Either[Halt, Bytes] = Left(Halt.InvalidParameter)

  private def invoking(precompile: Precompile, input: Bytes, gas: Int): (Frame, Either[Unsupported, Outcome]) =
    val environment =
      EvmFixtures.environment(withPrecompiles = PrecompileSet.Empty.adding(PrecompileSet.Blake2f, precompile))
    val message =
      EvmFixtures.message(currentTarget = PrecompileSet.Blake2f, data = input, transfersValue = false)
    val frame = new Frame(message, Code(Bytes.Empty), BigInt(gas))
    (frame, Interpreter.run(frame, environment))

  /** What a caller can tell about an invocation afterwards: what it kept, and
    * whether it halted. The reason a halt carries is deliberately not here --
    * every member of [[Halt]] is an exceptional halt, so it reaches a node's
    * diagnostics and never the chain.
    */
  private def keptAndHalted(precompile: Precompile, input: Bytes, gas: Int): (BigInt, Boolean) =
    val (frame, outcome) = invoking(precompile, input, gas)
    val halted = outcome match
      case Right(Outcome.Halted(_)) => true
      case _                        => false
    (frame.gasLeft, halted)

  "the price of a compression" should "be the per-round figure times the rounds asked for" in
    assert(
      blake2f.gasFor(packed(rounds = 12, finalFlag = 1)) == perRound * BigInt(12),
      "the document charges GFROUND per round, and the per-round figure is the schedule's"
    )

  it should "read the round count big-endian from the first four bytes" in
    assert(
      blake2f.gasFor(packed(distinguishingRounds, finalFlag = 1)) == perRound * BigInt(distinguishingRounds),
      "the four bytes differ, so reading them in the other order prices the call differently"
    )

  it should "reach the top of the unsigned range" in
    assert(
      blake2f.gasFor(packed(topOfRange, finalFlag = 1)) == perRound * BigInt(topOfRange),
      "the count is a 32-bit UNSIGNED word, and one assembled through a signed Int would price this negatively"
    )

  it should "be nothing where the width is not the one the document fixes" in
    assert(
      blake2f.gasFor(wrongWidth) == BigInt(0),
      "an argument this native cannot read has no round count, so nothing is guessed at"
    )

  it should "be nothing where the final-block byte is neither zero nor one" in
    assert(
      blake2f.gasFor(wrongFinalFlag) == BigInt(0),
      "the round count is only read out of an argument that is well formed throughout, flag included"
    )

  "a well-formed argument" should "be answered at the width the document states" in
    assert(
      blake2f.run(wellFormed).map(_.length) == Right(AnswerWidth),
      "the answer is the updated state vector, and this is the case the two refusals below are measured against"
    )

  "an argument of the wrong width" should "be refused" in
    assert(
      blake2f.run(wrongWidth) == Left(Halt.InvalidParameter),
      "the document fixes the width at 213 bytes and makes anything else an error"
    )

  "a final-block byte that is neither zero nor one" should "be refused" in
    assert(
      blake2f.run(wrongFinalFlag) == Left(Halt.InvalidParameter),
      "the document admits 0 and 1 for f, and a lenient read of it would answer where a conforming client halts"
    )

  "a refused argument" should "cost its caller the same under either order of charge and refusal" in
    assert(
      keptAndHalted(blake2f, wrongFinalFlag, 10000) ==
        keptAndHalted(chargingBeforeRefusing(perRound), wrongFinalFlag, 10000),
      "charging nothing then refusing, and charging the rounds then refusing, leave a caller the same two facts"
    )

  /** The calibration for the case above, which would otherwise pass for two
    * instances that charge alike -- in which case there would be no second
    * order for it to have compared against.
    */
  it should "have been charged differently by the two orders" in
    assert(
      blake2f.gasFor(wrongFinalFlag) != chargingBeforeRefusing(perRound).gasFor(wrongFinalFlag),
      "the two really are the two orders: one prices the refused argument at nothing and the other at its rounds"
    )

  it should "leave its caller nothing under either order" in
    assert(
      keptAndHalted(blake2f, wrongFinalFlag, 10000) == (BigInt(0), true),
      "every Halt is an exceptional halt, so what makes the two orders alike is that both keep nothing"
    )
