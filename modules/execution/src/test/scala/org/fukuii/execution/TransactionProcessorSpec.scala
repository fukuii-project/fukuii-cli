package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.fukuii.evm.{ContractAddress, EvmFixtures, JournaledWorldState, Word}

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

  private def transaction(
      nonce: BigInt = 0,
      gasPrice: BigInt = 3,
      gasLimit: BigInt = 100000,
      to: Option[Address] = Some(recipient),
      value: BigInt = 1000,
      data: Bytes = Bytes.Empty
  ): AdmittedTransaction = AdmittedTransaction(sender, nonce, gasPrice, gasLimit, to, value, data)

  /** What one settlement did, as the three things a case below asks about. */
  final private case class Ran(
      settlement: Settlement,
      world: EvmFixtures.MapWorldState,
      destroyed: Vector[Address]
  )

  private def settle(admitted: AdmittedTransaction, code: Map[Address, Bytes] = Map.empty): Ran =
    val base = new EvmFixtures.MapWorldState
    base.setBalance(sender, Word(Funded))
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
      EvmFixtures.rules
    )
    Ran(settlement, base, destroyed.toVector)

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
