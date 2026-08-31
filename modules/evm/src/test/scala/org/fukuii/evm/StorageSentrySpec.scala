package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.flatspec.AnyFlatSpec

/** The refusal a store owes an invocation carrying too little gas, and where it
  * sits relative to the charge that earns a refund.
  *
  * ==Three things have to be pinned, and only the first is obvious==
  *
  * That the refusal fires is the easy half. That it fires on `<=` rather than
  * `<` is a consensus divergence one unit wide. And that it fires BEFORE the
  * charging clause is observable only through [[Frame.refundCounter]], which
  * both charging helpers move as a side effect -- so a sentry moved one line
  * down would leave a refund earned by a store that never happened, and every
  * assertion about gas would go on passing.
  *
  * ==Each case is paired with the metering that does not refuse==
  *
  * A sentry that ignored [[EvmRules.storageMetering]] altogether would satisfy
  * every case that only ever runs [[StorageMetering.NetWithSentry]]. The two
  * other schemes are therefore run at the same gas level throughout, and what
  * they do there is asserted rather than assumed.
  *
  * Expected behavior is `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-2200.md`
  * (Final): *"If gasleft is less than or equal to gas stipend, fail the current
  * call frame with 'out of gas' exception."* The placement is
  * `ethereum/execution-specs` @ `20f7f6271a`,
  * `src/ethereum/forks/istanbul/vm/instructions/storage.py`, whose `sstore`
  * raises immediately after its two pops and before it reads either the
  * original or the current value -- so before every clause that touches the
  * refund counter.
  */
class StorageSentrySpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  // Held above every test registration: Scala 3's initialization checker reads
  // a val declared below one as read-before-init, and reports it against the
  // first test in the class rather than against the val.
  private val target: Address = EvmFixtures.address(0x22)

  private val slot: Word = EvmFixtures.word(1)

  private val held: Word = EvmFixtures.word(0x2a)

  /** What the two operands of a store cost before the store itself is reached,
    * so that a case can name the gas the store sees rather than the gas the
    * frame started with.
    */
  private val reachingStore: BigInt = schedule.veryLow * 2

  /** `PUSH1 value, PUSH1 slot, SSTORE` -- the operand order the machine pops in,
    * slot first.
    */
  private def storing(value: Int): Seq[Int] = Seq(0x60, value, 0x60, 0x01, Opcode.SStore.code)

  private def worldHolding(committed: Option[Word]): EvmFixtures.MapWorldState =
    val world = new EvmFixtures.MapWorldState
    committed.foreach(value => world.setStorage(target, slot, value))
    world

  /** Runs `program` under `metering` with exactly `gasAtStore` left when the
    * store is reached.
    */
  private def storeWith(
      metering: StorageMetering,
      gasAtStore: BigInt,
      program: Seq[Int],
      committed: Option[Word] = Some(held)
  ): (Frame, Either[Unsupported, Outcome]) =
    val rules = EvmFixtures.rules.copy(storageMetering = metering)
    val environment = EvmFixtures.environmentUnder(rules, worldHolding(committed))
    val frame = new Frame(
      EvmFixtures.message(currentTarget = target, transfersValue = true),
      Code(Bytes.fromArray(program.map(_.toByte).toArray)),
      gasAtStore + reachingStore
    )
    (frame, Interpreter.run(frame, environment))

  /** A store that changes nothing, which is the cheapest clause net metering
    * has -- and so the only one a frame sitting just above the threshold can
    * still afford.
    */
  private val noOpStore: Seq[Int] = storing(0x2a)

  /** A store that clears a slot holding a value, which is the clause that earns
    * a refund.
    */
  private val clearingStore: Seq[Int] = storing(0x00)

  private val stipend: BigInt = schedule.callStipend

  /** The four clauses net metering distinguishes, as programs over a slot whose
    * committed value each one names.
    */
  private val clauses: Seq[(String, Seq[Int], Option[Word])] =
    Seq(
      ("no-op", noOpStore, Some(held)),
      ("init", storing(0x2a), None),
      ("clean", storing(0x2b), Some(held)),
      ("dirty", storing(0x2b) ++ storing(0x2c), Some(held))
    )

  private def clauseResults(metering: StorageMetering): Seq[(Either[Unsupported, Outcome], BigInt)] =
    clauses.map { (_, program, committed) =>
      val (frame, outcome) = storeWith(metering, BigInt(100000), program, committed)
      (outcome, frame.refundCounter)
    }

  "a store metered with the sentry" should "be refused where the gas left equals the stipend" in {
    val (_, outcome) = storeWith(StorageMetering.NetWithSentry, stipend, noOpStore)
    assert(
      outcome == Right(Outcome.Halted(Halt.OutOfGas)),
      "the document refuses at less than OR EQUAL to the stipend, so the boundary itself is refused"
    )
  }

  it should "not be refused where the gas left is one above the stipend" in {
    val (_, outcome) = storeWith(StorageMetering.NetWithSentry, stipend + 1, noOpStore)
    assert(
      outcome == Right(Outcome.Stopped(stipend + 1 - schedule.netStorageNoop, Bytes.Empty)),
      "one unit above the threshold the store runs and is charged, which is what makes the case above about the boundary"
    )
  }

  "a store metered without the sentry" should "run where the sentry would refuse it" in {
    val (_, outcome) = storeWith(StorageMetering.Net, stipend, noOpStore)
    assert(
      outcome == Right(Outcome.Stopped(stipend - schedule.netStorageNoop, Bytes.Empty)),
      "the refusal is one scheme's and not the machine's, so net metering alone completes the same store"
    )
  }

  /** Legacy metering cannot be shown to run here the way net metering is, and
    * the reason is arithmetic rather than a gap: its cheapest charge is the
    * resetting price, which is far above the stipend, so no store it meters is
    * affordable at a gas level where the sentry fires. What it CAN be shown to
    * do is reach the clause that moves the refund counter, which is the same
    * observable the ordering cases below rest on.
    */
  it should "reach its charging clause under legacy metering, where the sentry refuses first" in {
    val (frame, _) = storeWith(StorageMetering.Legacy, stipend, clearingStore)
    assert(
      frame.refundCounter == schedule.refundStorageClear,
      "legacy metering is not sentried, so it charges -- and its charge earns the clearing refund on the way"
    )
  }

  "a store the sentry refuses" should "earn no refund" in {
    val (frame, _) = storeWith(StorageMetering.NetWithSentry, stipend, clearingStore)
    assert(
      frame.refundCounter == BigInt(0),
      "the refusal precedes the charging clause, so a store that never happened leaves the counter alone"
    )
  }

  /** The calibration for the case above. Without it that assertion passes for a
    * machine whose refund counter never moves at all, and passes for a store
    * that was never going to earn anything.
    */
  it should "have earned one under the same metering minus the sentry" in {
    val (frame, outcome) = storeWith(StorageMetering.Net, stipend, clearingStore)
    assert(
      (frame.refundCounter, outcome) == (schedule.refundNetStorageClear, Right(Outcome.Halted(Halt.OutOfGas))),
      "net metering applies the refund and then fails the charge, which is the state a sentry placed one line later would leave"
    )
  }

  "metering with the sentry" should "charge what net metering charges for every clause above the threshold" in
    assert(
      clauseResults(StorageMetering.NetWithSentry) == clauseResults(StorageMetering.Net),
      "the two share one clause body, so above the threshold they differ in nothing a frame can observe"
    )

  /** The calibration for the case above, which would otherwise pass for four
    * identical halts or four clauses that were never distinguished.
    */
  it should "have driven four clauses that do not agree with each other" in
    assert(
      clauseResults(StorageMetering.Net).distinct.length == clauses.length,
      "each clause charges its own price, so the comparison above is over four different answers"
    )
