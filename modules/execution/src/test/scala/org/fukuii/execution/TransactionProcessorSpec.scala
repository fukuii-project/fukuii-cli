package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.fukuii.evm.{
  ContractAddress,
  Cost,
  EvmFixtures,
  EvmRules,
  JournaledWorldState,
  Opcode,
  Operation,
  Unsupported,
  Word
}

/** What settling a transaction does that running one does not.
  *
  * ==These are the parts a published state fixture cannot isolate==
  *
  * The corpus compares a state root, so it sees the sum of the nonce, the fee,
  * the refund and the destruction at once and reports one number when any of
  * them is wrong. Each is asserted separately here, against a schedule whose
  * every price is distinct, so that a settlement reading the wrong field fails
  * rather than coinciding.
  *
  * ==Destruction is observed as a call, not as a state==
  *
  * The account removal a settlement performs reaches state through a function it
  * is handed, so a test records the calls instead of building a trie. That is
  * what the parameter is for: whether the removal then clears the storage under
  * the account is the trie's contract and is asserted where the trie is.
  */
class TransactionProcessorSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  private val sender: Address = EvmFixtures.address(0x11)
  private val recipient: Address = EvmFixtures.address(0x22)
  private val coinbase: Address = EvmFixtures.address(0x33)

  /** An address a `PUSH1` can name, which one built from a repeated byte is
    * not: the operand is one byte and the address it becomes is left-padded.
    */
  private val callee: Address = Address.fromBytesTruncating(IArray(0x44.toByte))

  private val block = EvmFixtures.block.copy(coinbase = coinbase, gasLimit = BigInt(10000000))

  private val Funded: BigInt = BigInt(10).pow(18)

  /** Ends the invocation and gives its balance to an account that does not
    * exist, so the surcharge for that is part of what it spends.
    */
  private val selfDestructs: Bytes = EvmFixtures.bytesOf("0x6022ff")

  /** Calls [[callee]] with nothing and stops. */
  private val callsThenStops: Bytes = EvmFixtures.bytesOf("0x60006000600060006000604461fffff100")

  /** The same call, then two removals from a stack holding one thing, so the
    * invocation halts after the nested one has already registered.
    */
  private val callsThenHalts: Bytes = EvmFixtures.bytesOf("0x60006000600060006000604461fffff15050")

  /** What a transaction ending in [[selfDestructs]] spends: the intrinsic
    * charge, the operand it pushes, and the operation at both its terms.
    */
  private val selfDestructSpend: BigInt =
    schedule.transactionBase + schedule.veryLow + schedule.selfDestruct + schedule.selfDestructNewAccount

  /** Adds two operands, so an invocation over it reaches `ADD`. */
  private val adds: Bytes = EvmFixtures.bytesOf("0x6003600501")

  /** Initialization code that meets a byte naming no operation, so a deployment
    * over it halts.
    */
  private val haltingInit: Bytes = EvmFixtures.bytesOf("0x0c")

  /** Rules giving a created account a count before its initialization code
    * runs, which is the state a failed deployment has to leave nothing of.
    */
  private val countingCreations: EvmRules =
    EvmFixtures.rules.copy(createdAccountNonce = UInt64.fromBits(1L))

  /** Rules whose table says `ADD` works out its own price, where this build
    * prices it from the table.
    *
    * That mismatch is the reachable form of an operation this build cannot run:
    * it needs no unimplemented opcode, so it goes on being reachable once every
    * operation is built. `InterpreterSpec` states the same construction where
    * the machine is the subject; here the subject is what a settlement does
    * around one.
    */
  private val cannotRunAdd: EvmRules =
    EvmFixtures.rules.copy(table = EvmFixtures.rules.table.adding(Operation(Opcode.Add, Cost.Computed)))

  /** What admission would hand settlement for a transaction of this shape.
    *
    * The intrinsic charge is worked out here because admission is what works it
    * out, and a test standing in for admission owes the same figure. A case
    * wanting a charge this schedule does not price says so with `copy`, which
    * is the shape a later fork's admission produces.
    */
  private def transaction(
      nonce: BigInt = 0,
      gasPrice: BigInt = 3,
      gasLimit: BigInt = 100000,
      to: Option[Address] = Some(recipient),
      value: BigInt = 1000,
      data: Bytes = Bytes.Empty
  ): AdmittedTransaction =
    AdmittedTransaction(
      sender,
      nonce,
      gasPrice,
      gasLimit,
      to,
      value,
      data,
      IntrinsicGas.of(schedule, data, to.isEmpty)
    )

  /** What one settlement did, as the three things a case below asks about. */
  final private case class Ran(
      settlement: Settlement,
      world: EvmFixtures.MapWorldState,
      destroyed: Vector[Address]
  )

  private def settle(
      admitted: AdmittedTransaction,
      code: Map[Address, Bytes] = Map.empty,
      rules: EvmRules = EvmFixtures.rules,
      funded: BigInt = Funded
  ): Ran =
    val base = new EvmFixtures.MapWorldState
    base.setBalance(sender, Word(funded))
    code.foreach((address, bytes) => base.setCode(address, bytes))
    val destroyed = mutable.ArrayBuffer.empty[Address]
    def record(address: Address): Unit =
      val _ = destroyed.append(address)
    val settlement = TransactionProcessor.settle(
      admitted,
      new JournaledWorldState(base),
      record,
      block,
      EvmFixtures.blockHashAt,
      rules
    )
    Ran(settlement, base, destroyed.toVector)

  /** A settlement whose invocation reached [[cannotRunAdd]]'s gap. */
  private def overAGap: Ran = settle(transaction(), Map(recipient -> adds), cannotRunAdd)

  // ── What is charged before the machine runs ────────────────────────────────

  "a transfer to an account with no code" should "be charged the intrinsic base and nothing more" in
    assert(
      settle(transaction()).settlement.gasUsed == schedule.transactionBase,
      "an invocation over empty code runs no operation, so what it costs is the charge every transaction pays"
    )

  it should "be charged for every byte of its input" in
    // Three non-zero bytes. This is what makes the data prices read at the
    // moment they are spent rather than only where a transaction is admitted.
    assert(
      settle(transaction(data = EvmFixtures.bytesOf("0x010203"))).settlement.gasUsed ==
        schedule.transactionBase + schedule.transactionDataPerNonZeroByte * 3,
      "a transaction pays for its input before it runs, at the price the schedule states"
    )

  "a deployment carrying no init code" should "pay the creation surcharge over the base" in
    assert(
      settle(transaction(to = None, data = Bytes.Empty)).settlement.gasUsed ==
        schedule.transactionBase + schedule.transactionCreate,
      "a transaction stating no recipient deploys, and the surcharge for that is spent here"
    )

  it should "leave an account at the address its sender's count derives" in
    assert(
      settle(transaction(to = None, data = Bytes.Empty)).world
        .accountExists(ContractAddress.of(sender, UInt64.Zero)),
      "the address is derived from the count the sender held when it signed, not from the one it holds after"
    )

  it should "leave nothing at that address where its initialization code halted" in
    // Observed after `commit`, which is what makes this a different reading from
    // the machine's own: the count a deployment is created with is written
    // outside the snapshot its execution takes, so a reversal missing from the
    // creating side reaches the state a root is taken over rather than stopping
    // at a journal nothing published.
    assert(
      !settle(transaction(to = None, data = haltingInit), rules = countingCreations).world
        .accountExists(ContractAddress.of(sender, UInt64.Zero)),
      "a failed deployment committed the account it was given"
    )

  "the intrinsic charge" should "be the figure admission stated, not one settlement works out" in
    // The whole of what carrying the number buys, made reachable at this fork.
    // A later fork prices the charge from fields this record does not hold --
    // EIP-2930 charges per access-list entry -- so admission hands settlement a
    // figure no computation over the input and the recipient reproduces, and
    // spending anything but that figure is spending a second definition of one
    // number.
    assert(
      settle(transaction().copy(intrinsicGas = schedule.transactionBase + 4242)).settlement.gasUsed ==
        schedule.transactionBase + 4242,
      "settlement worked the charge out from its own rules instead of spending the one it was handed"
    )

  // ── What moves ────────────────────────────────────────────────────────────

  "a settled transaction" should "move its sender's transaction count on by one" in
    assert(
      settle(transaction()).world.nonceOf(sender) == UInt64.fromBits(1),
      "a settled transaction must not be settleable again, and the count is what refuses it"
    )

  it should "leave its sender charged for the gas it used and nothing else" in
    // The whole limit is taken before the machine runs and the unused part
    // handed back, so a sender that never spends its limit is out of pocket only
    // by what it spent. A settlement that failed to hand any back would leave
    // this short by the difference.
    assert(
      settle(transaction()).world.balanceOf(sender).toBigInt ==
        Funded - 1000 - schedule.transactionBase * 3,
      "the fee is the gas actually used at the price the transaction offered"
    )

  it should "pay the block's beneficiary exactly what the sender was charged" in
    assert(
      settle(transaction()).world.balanceOf(coinbase).toBigInt == schedule.transactionBase * 3,
      "what leaves the sender as a fee arrives at the beneficiary, and the two are the same figure"
    )

  it should "move its value to the recipient" in
    assert(
      settle(transaction()).world.balanceOf(recipient).toBigInt == BigInt(1000),
      "a transfer to an account with no code still transfers"
    )

  // ── What the fee arithmetic refuses to do quietly ─────────────────────────
  //
  // The machine's word is modular, so every one of these would otherwise answer
  // a plausible figure: a debit below zero becomes a near-2^256 credit and a
  // credit past the ceiling becomes a small balance. Each is a fee no network
  // charged, and nothing downstream can tell one from a figure a chain agreed
  // on -- so each is asserted at its own site rather than once at the helper.

  "the upfront fee" should "be refused rather than taken from a sender that cannot cover it" in
    // Admission refuses a sender that cannot fund the whole fee it offers, so
    // this is a caller settling a transaction it would have turned away.
    assertThrows[IllegalStateException](settle(transaction(gasPrice = Funded)))

  "the beneficiary's credit" should "be refused rather than wrapped past what a word holds" in {
    val base = new EvmFixtures.MapWorldState
    base.setBalance(sender, Word(Funded))
    base.setBalance(coinbase, Word.MaxValue)
    assertThrows[IllegalStateException](
      TransactionProcessor.settle(
        transaction(),
        new JournaledWorldState(base),
        (_: Address) => (),
        block,
        EvmFixtures.blockHashAt,
        EvmFixtures.rules
      )
    )
  }

  "the sender's refund" should "be refused rather than wrapped when the charge exceeded the limit" in
    // Admission refuses a limit that cannot pay the intrinsic charge, so what
    // comes back is bounded by what was taken. Settling one it would have
    // refused makes the remainder negative, and the sender is funded to exactly
    // the fee so that the negative credit has nothing left to hide in.
    assertThrows[IllegalStateException](
      settle(transaction(value = 0).copy(intrinsicGas = BigInt(100001)), funded = BigInt(300000))
    )

  // ── The refund, and the bound on it ───────────────────────────────────────

  "a refund" should "be capped at half of what the transaction spent" in
    assert(
      settle(transaction(), Map(recipient -> selfDestructs)).settlement.gasUsed ==
        selfDestructSpend - selfDestructSpend / 2,
      "a transaction cannot claim back more than half of what it consumed, however much it earned"
    )

  it should "have been larger than the cap in the case above, or that case tests nothing" in
    // The control. Were the earned refund below the cap, the assertion above
    // would hold for an implementation that applied no cap at all.
    assert(
      schedule.refundSelfDestruct > selfDestructSpend / 2,
      "the fixture schedule must earn more than half the spend, or the cap is not what bounds the figure"
    )

  // ── What a registration becomes, and when it does not ─────────────────────

  "an account a successful transaction registered" should "be destroyed" in
    assert(
      settle(transaction(), Map(recipient -> selfDestructs)).destroyed == Vector(recipient),
      "the removal belongs to whatever ends the transaction, because until then the account still answers"
    )

  "an account registered by a nested invocation" should "be destroyed when the transaction succeeds" in
    // The control for the case below. Without it, a settlement that destroyed
    // nothing at all would satisfy that one.
    assert(
      settle(transaction(), Map(recipient -> callsThenStops, callee -> selfDestructs)).destroyed ==
        Vector(callee),
      "a registration a nested invocation made is its caller's once that invocation ends normally"
    )

  it should "not be destroyed when the transaction then fails" in
    // The registration reached the outer frame and the outer frame halted. Every
    // write that invocation made is undone, and the registration must go with
    // them -- this is the one of the four discarded things whose survival would
    // be a state root difference rather than a receipt difference.
    assert(
      settle(transaction(), Map(recipient -> callsThenHalts, callee -> selfDestructs)).destroyed.isEmpty,
      "an account registered inside a transaction that failed must still be there afterwards"
    )

  it should "emit no logs and report failure when the transaction failed" in
    assert(
      !settle(transaction(), Map(recipient -> callsThenHalts, callee -> selfDestructs)).settlement.succeeded,
      "an invocation that halted is reported as one, whatever it had done before it halted"
    )

  "a transaction whose invocation halted" should "be charged its whole limit" in
    assert(
      settle(transaction(), Map(recipient -> callsThenHalts, callee -> selfDestructs)).settlement.gasUsed ==
        BigInt(100000),
      "an exceptional halt at this fork keeps nothing, so the limit is what the sender pays for"
    )

  // ── What this build cannot run is carried out whole ──────────────────────
  //
  // All four fields are asserted, separately, because a settlement over a gap is
  // the one shape whose other three fields are not a chain result and are read
  // as though they were. Nothing downstream can tell a fabricated figure from a
  // settled one, so each is stated here rather than inferred from the gap.

  "a transaction that reached an operation this build cannot run" should "name that operation" in
    assert(
      overAGap.settlement.unbuilt == Some(Unsupported(Opcode.Add)),
      "a gap is carried out by name, because a caller comparing this against a chain must know which one it met"
    )

  it should "be charged its whole limit" in
    // What the machine had left is not the sender's to keep: the run did not
    // reach an end a chain reaches, so there is no remainder to hand back and
    // the settlement is the one an exceptional halt would have produced.
    assert(
      overAGap.settlement.gasUsed == BigInt(100000),
      "a settlement over a gap charges what a halt would, which is the whole limit"
    )

  it should "report that it did not succeed" in
    assert(
      !overAGap.settlement.succeeded,
      "nothing this build could not run ended normally, and a receipt built from this must not say it did"
    )

  it should "emit no logs" in
    assert(
      overAGap.settlement.logs.isEmpty,
      "what an invocation emitted before it met the gap is discarded with it, as it is when one halts"
    )

  it should "have run the operation at rules that price it, or the four cases above test nothing" in
    // The control. The same code and the same transaction under the shipped
    // table settle normally, so what the four above observe is the table's
    // mismatch and not something else about the fixture.
    assert(
      settle(transaction(), Map(recipient -> adds)).settlement.unbuilt.isEmpty,
      "the gap must come from the table saying ADD computes its own price, and from nothing else"
    )
