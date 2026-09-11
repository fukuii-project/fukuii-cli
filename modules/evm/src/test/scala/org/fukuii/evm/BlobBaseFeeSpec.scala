package org.fukuii.evm

import org.fukuii.bytes.{Bytes, UInt64}
import org.scalatest.flatspec.AnyFlatSpec

/** The operation that answers what a blob costs per unit of blob gas.
  *
  * ==It reads two things and derives a third, which is what separates it from
  * every other block operation==
  *
  * [[BaseFeeSpec]]'s subject pushes a header field. This one runs
  * [[BlobGasPrice]] over the excess a header states and the fraction the fork
  * resolves, so there are two ways to misconfigure it rather than one and the
  * value it pushes is in neither place. The arithmetic itself is
  * [[BlobGasPriceSpec]]'s; what is asserted here is that the operation reaches
  * it with the right two arguments, at the right price, from the right table.
  *
  * Expected behavior is `ethereum/EIPs` @ `d2a64c2d4` (2026-09-11),
  * `EIPS/eip-7516.md:27-31`, Final: *"Add a `BLOBBASEFEE` instruction with
  * opcode `0x4a`, with gas cost `2`"*, zero inputs and one output. Read against
  * `ethereum/execution-specs` @ `0cc100eb1` (2026-09-11), whose `blob_base_fee`
  * at `src/ethereum/forks/cancun/vm/instructions/environment.py:596-602` charges
  * the base tier and pushes
  * `calculate_blob_gas_price(evm.message.block_env.excess_blob_gas)`.
  */
class BlobBaseFeeSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Above every test registration, for the initialization-checker reason
  // `BaseFeeSpec` records.
  private val table: OpcodeTable =
    OpcodeTable.original(schedule).adding(Operation(Opcode.BlobBaseFee, Cost.Fixed(schedule.base)))

  private val UpdateFraction: BigInt = BigInt(3338477)

  /** An excess the chain reaches early, and one far enough along to price above
    * the floor.
    *
    * Two are needed because the floor is where almost every published case sits:
    * a build answering the constant one would satisfy a spec written only at the
    * first of these.
    */
  private val lowExcess: UInt64 = UInt64.fromBigInt(BigInt(0x140000)).getOrElse(UInt64.Zero)

  private val highExcess: UInt64 = UInt64.fromBigInt(BigInt(6000000)).getOrElse(UInt64.Zero)

  private def environmentWith(excess: Option[UInt64], fraction: Option[BigInt]): Environment =
    new Environment(
      new JournaledWorldState(new EvmFixtures.MapWorldState),
      EvmFixtures.blockHashAt,
      EvmFixtures.block.copy(excessBlobGas = excess),
      EvmFixtures.transaction,
      EvmFixtures.chainId,
      EvmFixtures.rules.copy(table = table, blobBaseFeeUpdateFraction = fraction)
    )

  private def runIn(environment: Environment, gas: Int, program: Int*): (Frame, Either[Unsupported, Outcome]) =
    val frame = new Frame(
      EvmFixtures.message(transfersValue = true),
      Code(Bytes.fromArray(program.map(_.toByte).toArray)),
      BigInt(gas)
    )
    (frame, Interpreter.run(frame, environment))

  private def answerAt(excess: UInt64, fraction: BigInt): Either[Halt, Word] =
    val (frame, _) = runIn(environmentWith(Some(excess), Some(fraction)), 1000, Opcode.BlobBaseFee.code)
    frame.stack.peek(0)

  "BLOBBASEFEE" should "push the charge the block's excess derives to" in
    assert(
      answerAt(highExcess, UpdateFraction) == Right(Word(BlobGasPrice.at(highExcess.toBigInt, UpdateFraction))),
      "the operation answers the derivation, taking no operand to do it"
    )

  it should "push a charge above the floor where the excess is far enough along" in
    // Without this the case above is satisfied by a build that pushes the
    // minimum unconditionally, which is what the whole published state corpus
    // for this operation would also accept.
    assert(answerAt(highExcess, UpdateFraction) == Right(Word(BigInt(6))), "measured at 6 for this pair")

  it should "push the floor where the excess is small, and that is not the same answer" in
    assert(
      answerAt(lowExcess, UpdateFraction) == Right(Word(BigInt(1))) &&
        answerAt(lowExcess, UpdateFraction) != answerAt(highExcess, UpdateFraction),
      "two blocks get two charges back, which no compiled-in value can do for both"
    )

  it should "follow the FORK's fraction and not only the block" in
    // The second argument, which a spec varying only the excess never reaches.
    // A build that had hardcoded this fork's figure passes every case above.
    assert(
      answerAt(highExcess, BigInt(5007716)) == Right(Word(BigInt(3))) &&
        answerAt(highExcess, BigInt(5007716)) != answerAt(highExcess, UpdateFraction),
      "the same excess under a later fork's fraction is a different charge: measured at 3 against 6"
    )

  it should "push the floor where the block's excess is genuinely zero" in
    // Distinct from the absent case below, and the distinction is why the member
    // is an option: every block at the fork's own start states a zero excess,
    // and a block below the fork states none at all.
    assert(
      answerAt(UInt64.Zero, UpdateFraction) == Right(Word(BlobGasPrice.Minimum)),
      "a zero excess is an excess and prices at the floor"
    )

  it should "cost the base tier" in {
    val (frame, _) =
      runIn(environmentWith(Some(lowExcess), Some(UpdateFraction)), 1000, Opcode.BlobBaseFee.code)
    assert(
      frame.gasLeft == BigInt(1000) - schedule.base,
      "the document prices it at 2, and the tier is read from the schedule rather than fixed here"
    )
  }

  it should "leave the stack holding one word" in {
    val (frame, _) =
      runIn(environmentWith(Some(lowExcess), Some(UpdateFraction)), 1000, Opcode.BlobBaseFee.code)
    assert(frame.stack.peek(1).isLeft, "it uses no stack argument, so nothing sits under what it pushed")
  }

  it should "not run under a table that does not carry it" in {
    val (_, outcome) = runIn(EvmFixtures.environment(), 1000, Opcode.BlobBaseFee.code)
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidOpcode(Opcode.BlobBaseFee.code))),
      "a network that has not adopted the document meets a byte naming no operation"
    )
  }

  it should "refuse a block carrying no excess rather than answer for it" in {
    val thrown = intercept[IllegalStateException] {
      runIn(environmentWith(None, Some(UpdateFraction)), 1000, Opcode.BlobBaseFee.code)
    }
    assert(
      thrown.getMessage.contains("excess blob gas on the block"),
      "the refusal names which half is missing, because the two are different mistakes"
    )
  }

  it should "refuse rules carrying no update fraction rather than answer for them" in {
    // The second half, which the case above cannot reach. A build defaulting
    // this to zero would divide by zero; one defaulting it to some figure would
    // price every blob on the network wrongly and say nothing.
    val thrown = intercept[IllegalStateException] {
      runIn(environmentWith(Some(lowExcess), None), 1000, Opcode.BlobBaseFee.code)
    }
    assert(
      thrown.getMessage.contains("update fraction"),
      "a composition adopting the operation without the document that prices it has to be findable"
    )
  }
