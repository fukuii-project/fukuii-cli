package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.evm.{
  ContractAddress,
  Cost,
  EvmFixtures,
  EvmRules,
  JournaledWorldState,
  Opcode,
  Operation,
  PrecompileSet,
  StateTrieWorldState,
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

  /** Settlement at every height before an account reached and left holding
    * nothing ceases to exist.
    */
  private val notClearing: ExecutionRules =
    ExecutionRules(touchedEmptyAccountsAreDeleted = false, receiptCarriesStatus = false)

  /** The same, from the height that deletes one. */
  private val clearing: ExecutionRules = notClearing.copy(touchedEmptyAccountsAreDeleted = true)

  /** The address the RIPEMD-160 native answers at, which is the one address each
    * of the four implementations read for
    * `org.fukuii.evm.EvmRules.touchSurvivesFailure` exempts from the rule that a
    * reach is undone with the invocation that made it.
    */
  private val exempt: Address = PrecompileSet.Ripemd160

  /** Rules under which reaching [[exempt]] is not undone by a failure. */
  private val exempting: EvmRules = EvmFixtures.rules.copy(touchSurvivesFailure = Set(exempt))

  /** Calls [[exempt]] forwarding nothing, so the native cannot be paid for and
    * the invocation it runs in halts, then stops.
    */
  private val callsTheNativeTooCheaply: Bytes = EvmFixtures.bytesOf("0x6000600060006000600060036000f100")

  /** The same call, then two removals from a stack holding one thing, so the
    * OUTERMOST invocation halts after the native's has already failed.
    */
  private val callsTheNativeTooCheaplyThenHalts: Bytes =
    EvmFixtures.bytesOf("0x6000600060006000600060036000f15050")

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
      funded: BigInt = Funded,
      execution: ExecutionRules = notClearing,
      present: Set[Address] = Set.empty
  ): Ran =
    val base = new EvmFixtures.MapWorldState
    base.setBalance(sender, Word(funded))
    code.foreach((address, bytes) => base.setCode(address, bytes))
    present.foreach(base.touch)
    val destroyed = mutable.ArrayBuffer.empty[Address]
    def record(address: Address): Unit =
      val _ = destroyed.append(address)
    val settlement = TransactionProcessor.settle(
      admitted,
      new JournaledWorldState(base),
      record,
      block,
      EvmFixtures.blockHashAt,
      rules,
      execution
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
        EvmFixtures.rules,
        notClearing
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

  // ── What a transaction reached, and what that costs an empty account ──────
  //
  // Every case below is stated as a call to the removal, not as a state, for the
  // reason the section above is: what the removal then does to the trie is the
  // trie's contract. The two roots at the end are the exception, and they are
  // there because a create-then-delete is only equal to a never-create at the
  // level a root is taken over.

  "an account a transaction reached and left holding nothing" should "be destroyed" in
    assert(
      settle(transaction(value = 0), execution = clearing).destroyed.contains(recipient),
      "an account reached by a transfer of nothing is left in the trie holding nothing"
    )

  it should "survive where the network has not adopted the rule" in
    // The gate, and the control without which every case here would pass against
    // a settlement that swept unconditionally.
    assert(
      settle(transaction(value = 0), execution = notClearing).destroyed.isEmpty,
      "an account was cleared at a height whose rules do not clear one"
    )

  it should "survive where the transaction left it holding something" in
    // The predicate, and the one place the two look-alike account tests come
    // apart: this account has a balance and no storage, so `deployableAt` admits
    // it and `deadAt` does not.
    assert(
      !settle(transaction(value = 1000), execution = clearing).destroyed.contains(recipient),
      "an account holding a balance was cleared as though it held nothing"
    )

  "an account a nested invocation reached" should "be destroyed when the transaction succeeds" in
    // The proposal's primary case: an account that ALREADY EXISTS holding
    // nothing has nothing transferred to it through a call. It is seeded rather
    // than left absent so that the call is not charged for bringing it into
    // being -- at this schedule that surcharge is larger than the gas the
    // transaction has left, and the transaction would halt before reaching the
    // callee at all.
    assert(
      settle(transaction(), Map(recipient -> callsThenStops), execution = clearing, present = Set(callee)).destroyed
        .contains(callee),
      "a reach a nested invocation made is its caller's once that invocation ends normally"
    )

  it should "not be destroyed when the transaction then fails" in
    // The revert rule, which is the absence of an act rather than an act: the
    // outermost invocation halted, so what it took up goes with its logs. Seeded
    // for the reason above, and here the seeding is what makes the case a
    // measurement -- unseeded, the transaction halts at the call rather than
    // after it and the account is never reached.
    assert(
      !settle(transaction(), Map(recipient -> callsThenHalts), execution = clearing, present = Set(callee)).destroyed
        .contains(callee),
      "an account reached inside a transaction that failed was cleared anyway"
    )

  // ── The one address whose reaching is not undone ──────────────────────────

  "an account an exempt address names, reached by an invocation that failed" should "be destroyed" in
    // The historical case, in the shape that produced it: a call to a native
    // that cannot be paid for, inside a transaction that goes on to succeed.
    assert(
      settle(
        transaction(),
        Map(recipient -> callsTheNativeTooCheaply),
        rules = exempting,
        execution = clearing,
        present = Set(exempt)
      ).destroyed.contains(exempt),
      "the one reach a failure does not undo was undone"
    )

  it should "survive where the network exempts nothing" in
    // The control. Without it the case above passes against a settlement that
    // kept every reach a failed invocation made, which is the pre-amendment
    // behavior the proposal's Addendum corrects.
    assert(
      !settle(
        transaction(),
        Map(recipient -> callsTheNativeTooCheaply),
        execution = clearing,
        present = Set(exempt)
      ).destroyed.contains(exempt),
      "a reach a failed invocation made survived at a network exempting no address"
    )

  it should "survive where the failure left no account there to destroy" in
    // Exempt, reached, and absent: the invocation that brought it into being was
    // undone, so the reach outlives the account. go-ethereum-pow at v1.10.26
    // meets the same state and skips it -- Finalise's own comment says the
    // address can be in the dirty set and not in the object map.
    assert(
      !settle(
        transaction(),
        Map(recipient -> callsTheNativeTooCheaply),
        rules = exempting,
        execution = clearing
      ).destroyed.contains(exempt),
      "an address that does not exist was destroyed"
    )

  "an exempt address reached before the OUTERMOST invocation failed" should "be destroyed" in
    // The reading two production implementations share and the executable
    // specification does not. Reversing it is one expression in
    // `TransactionProcessor`, and this case and the next are what say which way
    // it points.
    assert(
      settle(
        transaction(),
        Map(recipient -> callsTheNativeTooCheaplyThenHalts),
        rules = exempting,
        execution = clearing,
        present = Set(exempt)
      ).destroyed.contains(exempt),
      "the exemption stopped at the outermost invocation, which is the specification's reading and not this one"
    )

  it should "survive where the network exempts nothing" in
    assert(
      !settle(
        transaction(),
        Map(recipient -> callsTheNativeTooCheaplyThenHalts),
        execution = clearing,
        present = Set(exempt)
      ).destroyed.contains(exempt),
      "an outermost failure kept a reach at a network exempting no address"
    )

  it should "be destroyed where the executable specification destroys nothing" in
    // THE DIVERGENCE, named here so reconciling this project to the executable
    // specification has to delete a case that says why not. Whoever does that
    // is reading `TransactionProcessor.touchedAccounts`, which carries the
    // evidence and the one expression that reverses this.
    assert(
      settle(
        transaction(),
        Map(recipient -> callsTheNativeTooCheaplyThenHalts),
        rules = exempting,
        execution = clearing,
        present = Set(exempt)
      ).destroyed.contains(exempt),
      "the exemption was narrowed to the specification's reading, which two production implementations do not share"
    )

  // ── The block's beneficiary, which is nobody's invocation ─────────────────

  "the block's beneficiary" should "be destroyed where the fee left it holding nothing" in
    assert(
      settle(transaction(gasPrice = 0, value = 0), execution = clearing).destroyed.contains(coinbase),
      "a fee of nothing brings the beneficiary into being, and nothing removed it again"
    )

  it should "be destroyed even where the transaction failed" in
    // The fee is paid on both paths, so the account it reaches is reached on
    // both -- while everything the invocation reached is discarded on one of
    // them. A beneficiary taken from the frame's own set would be missed here.
    assert(
      settle(
        transaction(gasPrice = 0),
        Map(recipient -> callsThenHalts),
        execution = clearing,
        present = Set(callee)
      ).destroyed.contains(coinbase),
      "a beneficiary left holding nothing by a failed transaction was not removed"
    )

  it should "survive where the fee left it something" in
    assert(
      !settle(transaction(), execution = clearing).destroyed.contains(coinbase),
      "a beneficiary paid a fee was cleared as though it had been paid nothing"
    )

  /** The state root a zero-fee settlement leaves, with `beneficiary` as the
    * block's, over a real trie.
    *
    * Nothing else in the two runs differs, so two roots that disagree disagree
    * about the beneficiary's leaf and about nothing else.
    */
  private def rootAfterZeroFee(beneficiary: Address, execution: ExecutionRules): Hash =
    val trie = EvmFixtures.stateTrie()
    val base = new StateTrieWorldState(trie)
    base.setBalance(sender, Word(Funded))
    val _ = TransactionProcessor.settle(
      transaction(gasPrice = 0, value = 0),
      new JournaledWorldState(base),
      trie.destroyAccount,
      block.copy(coinbase = beneficiary),
      EvmFixtures.blockHashAt,
      EvmFixtures.rules,
      execution
    )
    trie.stateRoot

  "a beneficiary brought into being by a fee of nothing and removed again" should
    "leave the root one never brought into being would" in
    // The specification declines to credit a zero and removes only an account
    // already there; this credits unconditionally and removes what that
    // created. The two agree only if the removal leaves no leaf, which is a
    // claim about the trie and not about the settlement -- so it is asserted
    // over a root rather than over a call.
    assert(
      rootAfterZeroFee(EvmFixtures.address(0x77), clearing) ==
        rootAfterZeroFee(EvmFixtures.address(0x88), clearing),
      "two blocks differing only in a beneficiary that holds nothing produced different state roots"
    )

  it should "have left two roots without the clearing, or the case above tests nothing" in
    // The control. Were the beneficiary never written at all, the case above
    // would hold for a settlement that swept nothing.
    assert(
      rootAfterZeroFee(EvmFixtures.address(0x77), notClearing) !=
        rootAfterZeroFee(EvmFixtures.address(0x88), notClearing),
      "a beneficiary paid nothing left no leaf, so the case above is not measuring the removal"
    )
