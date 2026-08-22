package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.crypto.Secp256k1
import org.fukuii.evm.{Cost, EvmFixtures, EvmRules, Opcode, Operation, Unsupported, Word, WorldState}
import org.fukuii.types.{PostStateOrStatus, Sender, SigningPreimage, Transaction, TransactionType}

/** What a block does that one transaction cannot show.
  *
  * ==Every case here needs more than one transaction, or it belongs elsewhere==
  *
  * What settling a single transaction does is asserted in
  * `TransactionProcessorSpec` and certified against the published state corpus,
  * which states one transaction against an otherwise empty block. Neither can
  * see the two properties this layer adds: that a transaction runs against what
  * the one before it left, and that the gas each one takes is gone for the rest
  * of the block. A block processor that reversed its transactions, or that
  * offered every transaction the block's whole limit, passes every state
  * fixture published for these forks.
  *
  * ==The signatures are real, so the sender is recovered rather than asserted==
  *
  * A block carries signed transactions and nothing in one names its sender, so
  * each transaction below is signed with the curve. That is also what makes the
  * ordering cases mean something: a transaction's nonce is checked against the
  * account the signature recovered to, so two transactions from one signer can
  * only both be admitted in the order they were signed in.
  */
class BlockProcessorSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  private val recipient: Address = EvmFixtures.address(0x22)
  private val coinbase: Address = EvmFixtures.address(0x33)

  /** A second account holding code, so two transactions in one block can reach
    * different operations.
    */
  private val otherRecipient: Address = EvmFixtures.address(0x44)

  private val signing: BigInt = BigInt("4a2ffc8867fd8d1773481cf13f36e44f033133c579520d2745e46c3bbbf21e6a", 16)

  /** The account [[signing]] belongs to, derived rather than stated. */
  private val signer: Address =
    Sender.addressOf(Secp256k1.publicKeyOf(signing).getOrElse(fail("the signing key has no public key")))

  private val chainId: UInt64 = UInt64.fromBits(1)

  private val Funded: BigInt = BigInt(10).pow(18)

  /** What one transfer over empty code is charged: the intrinsic base alone. */
  private val TransferSpend: BigInt = schedule.transactionBase

  private val GasPrice: BigInt = BigInt(10)

  /** What each transaction below asks for, which every case keeps identical so
    * that the only thing varying across the accumulation cases is the block's
    * own limit.
    */
  private val AskedPerTransaction: Long = 30000L

  private def quantity(value: BigInt): UInt256 =
    UInt256.fromBigInt(value).getOrElse(fail("a fixture quantity does not fit a machine word"))

  /** A signed transfer at `nonce`, unprotected, so no rule set below has to
    * admit a signature naming a chain.
    */
  private def transfer(
      nonce: Long,
      to: Option[Address] = Some(recipient),
      value: BigInt = 1000,
      gasLimit: Long = AskedPerTransaction
  ): Transaction.Legacy =
    val unsigned = Transaction.Legacy(
      nonce = UInt64.fromBits(nonce),
      gasPrice = quantity(GasPrice),
      gasLimit = UInt64.fromBits(gasLimit),
      to = to,
      value = quantity(value),
      data = Bytes.Empty,
      v = UInt256.Zero,
      r = UInt256.Zero,
      s = UInt256.Zero
    )
    val signature = Secp256k1
      .sign(SigningPreimage.hashForSigning(unsigned, None), signing)
      .getOrElse(fail("the fixture transaction could not be signed"))
    unsigned.copy(
      v = quantity(BigInt(27) + signature.recoveryId),
      r = quantity(signature.r),
      s = quantity(signature.s)
    )

  /** A typed envelope no rule set here admits, carried so that a block holding
    * one exercises the refusal rather than the pricing.
    */
  private val typedEnvelope: Transaction.AccessList =
    Transaction.AccessList(
      chainId = chainId,
      nonce = UInt64.Zero,
      gasPrice = quantity(GasPrice),
      gasLimit = UInt64.fromBits(AskedPerTransaction),
      to = Some(recipient),
      value = UInt256.Zero,
      data = Bytes.Empty,
      accessList = Seq.empty,
      yParity = UInt256.Zero,
      r = quantity(BigInt(1)),
      s = quantity(BigInt(2))
    )

  private val statusReceipts: ExecutionRules =
    ExecutionRules(touchedEmptyAccountsAreDeleted = false, receiptCarriesStatus = true)

  private val rootReceipts: ExecutionRules =
    ExecutionRules(touchedEmptyAccountsAreDeleted = false, receiptCarriesStatus = false)

  private val admission: AdmissionRules =
    AdmissionRules(
      admittedTypes = Set(TransactionType.Legacy),
      signatureMayCarryChainId = false,
      signatureSMustBeLow = false
    )

  /** Emits one empty log and stops, so a block's derived log sequence has
    * something in it that a receipt also holds.
    */
  private val emitsALog: Bytes = EvmFixtures.bytesOf("0x60006000a0")

  /** Adds two operands, so an invocation over it reaches `ADD`. */
  private val adds: Bytes = EvmFixtures.bytesOf("0x6003600501")

  /** Multiplies two operands, so an invocation over it reaches `MUL`. */
  private val multiplies: Bytes = EvmFixtures.bytesOf("0x6003600502")

  /** Rules whose table says `ADD` and `MUL` each work out their own price,
    * where this build prices both from the table.
    *
    * Two of them rather than one, because which gap a block keeps is only
    * answerable where the transactions met different ones -- with a single
    * operation in the table, reporting the first and reporting the last are the
    * same value. `InterpreterSpec` states the same construction for one
    * operation where the machine is the subject.
    */
  private val cannotRunAddOrMul: EvmRules =
    EvmFixtures.rules.copy(table =
      EvmFixtures.rules.table
        .adding(Operation(Opcode.Add, Cost.Computed))
        .adding(Operation(Opcode.Mul, Cost.Computed))
    )

  /** What one block run produced, together with the three things only an
    * observer outside the processor can see.
    */
  final private case class Ran(
      result: Either[BlockRejection, BlockOutput],
      world: EvmFixtures.MapWorldState,
      rootsAsked: Int,
      coinbaseAtClose: BigInt
  ):

    def output: BlockOutput = result.getOrElse(fail("the block was rejected: " + result.toString))

  private def run(
      transactions: Seq[Transaction],
      blockGasLimit: BigInt = BigInt(10000000),
      funded: BigInt = Funded,
      execution: ExecutionRules = statusReceipts,
      irregularStateChange: Option[WorldState => Unit] = None,
      code: Map[Address, Bytes] = Map.empty,
      evm: EvmRules = EvmFixtures.rules
  ): Ran =
    val world = new EvmFixtures.MapWorldState
    world.setBalance(signer, Word(funded))
    code.foreach((address, bytes) => world.setCode(address, bytes))
    var rootsAsked = 0
    var coinbaseAtClose = BigInt(-1)
    def rootAfterTransaction(): Hash =
      rootsAsked += 1
      EvmFixtures.hash(rootsAsked)
    val result = BlockProcessor.process(
      transactions = transactions,
      world = world,
      destroyAccount = _ => (),
      stateRootAfterTransaction = () => rootAfterTransaction(),
      block = EvmFixtures.block.copy(coinbase = coinbase, gasLimit = blockGasLimit),
      blockHashAt = EvmFixtures.blockHashAt,
      chainId = chainId,
      evm = evm,
      execution = execution,
      admission = admission,
      irregularStateChange = irregularStateChange,
      consensusStateChange = closing => coinbaseAtClose = closing.balanceOf(coinbase).toBigInt
    )
    Ran(result, world, rootsAsked, coinbaseAtClose)

  // ── A transaction runs against what the one before it left ────────────────

  "two transactions from one signer" should "both be admitted when they are run in the order the block carries them" in
    // The second states the count the first leaves behind, so it is admissible
    // only after the first has settled. A processor running them in any other
    // order refuses one of them for its nonce.
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 1))).output.receipts.length == 2,
      "a block's transactions must run in the order it carries them, so each sees what the last one wrote"
    )

  it should "leave the signer's count moved on once per transaction" in
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 1))).world.nonceOf(signer) == UInt64.fromBits(2),
      "each settled transaction moves the count, and the block's effect is the sum of them"
    )

  it should "leave the recipient holding what both of them sent" in
    assert(
      run(Seq(transfer(nonce = 0, value = 1000), transfer(nonce = 1, value = 2000))).world
        .balanceOf(recipient)
        .toBigInt == BigInt(3000),
      "a transaction writes into the state the next one reads, so two transfers accumulate"
    )

  "a block whose transactions are out of order" should "be rejected at the one that does not fit" in
    // The control for the cases above: the same two transactions, carried the
    // other way round. Without it, a processor that admitted anything would
    // satisfy them.
    assert(
      run(Seq(transfer(nonce = 1), transfer(nonce = 0))).result ==
        Left(BlockRejection(0, Refusal.NonceMismatch)),
      "a block stating a transaction before the one whose count it follows is not a block this network accepts"
    )

  // ── The gas a transaction takes is gone for the rest of the block ─────────

  "a block whose transactions together overrun its limit" should "be rejected at the first one that no longer fits" in
    // Three transfers asking 30000 each against a limit of 65000. Each spends
    // the intrinsic base, so 42004 is gone by the third, leaving 22996 -- less
    // than it asks for. A processor offering every transaction the block's whole
    // limit admits all three.
    assert(
      run(
        Seq(transfer(nonce = 0), transfer(nonce = 1), transfer(nonce = 2)),
        blockGasLimit = BigInt(65000)
      ).result == Left(BlockRejection(2, Refusal.GasAllowanceExceeded)),
      "what the transactions before it already spent is what a transaction's room is measured against"
    )

  it should "have admitted all three at a limit that leaves room, or the case above tests nothing" in
    // The control. The three transactions are otherwise identical, so the only
    // thing that refused the third above is the block's own limit.
    assert(
      run(
        Seq(transfer(nonce = 0), transfer(nonce = 1), transfer(nonce = 2)),
        blockGasLimit = BigInt(100000)
      ).output.receipts.length == 3,
      "the refusal above must come from the limit and not from anything else about the third transaction"
    )

  "a block's gas used" should "be every transaction's charge added up" in
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 1))).output.gasUsed == TransferSpend * 2,
      "a header commits to what the whole block spent, which is the sum over its transactions"
    )

  "an empty block" should "use no gas" in
    assert(run(Seq.empty).output.gasUsed == BigInt(0), "a block carrying nothing charges nothing")

  // ── What each transaction leaves behind ───────────────────────────────────

  "a receipt" should "carry what the block had spent including its own transaction" in
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 1))).output.receipts.map(_.cumulativeGasUsed.toBigInt) ==
        Vector(TransferSpend, TransferSpend * 2),
      "the cumulative figure is taken after the transaction is charged, not before"
    )

  it should "state the format of the transaction that produced it" in
    assert(
      run(Seq(transfer(nonce = 0))).output.receipts.map(_.transactionType) == Vector(TransactionType.Legacy),
      "a receipt carries the envelope its transaction had, and a receipts root is taken over that"
    )

  it should "carry the logs its transaction emitted" in
    assert(
      run(Seq(transfer(nonce = 0)), code = Map(recipient -> emitsALog)).output.receipts.head.logs.length == 1,
      "what a transaction emitted is what its own receipt holds"
    )

  "a block's logs" should "be every receipt's logs in order" in
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 1)), code = Map(recipient -> emitsALog)).output.logs.length == 2,
      "the block's bloom is taken over all of them, so the derived sequence must hold every one"
    )

  // ── Which of two first fields a receipt carries is the fork's answer ──────

  "a receipt at rules whose receipts carry a status" should "state that the transaction succeeded" in
    assert(
      run(Seq(transfer(nonce = 0))).output.receipts.head.postStateOrStatus == PostStateOrStatus.Successful,
      "EIP-658 replaced the intermediate root with a status, and the rule that says so is read here"
    )

  it should "never ask for an intermediate state root" in
    // The root is not merely unused at these rules -- it is not computed. A
    // processor taking one per transaction and discarding it would pass the case
    // above and pay for a root nothing reads.
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 1))).rootsAsked == 0,
      "a fork whose receipts carry a status has no use for a per-transaction root and must not take one"
    )

  "a receipt at rules whose receipts carry a root" should "state the root the state reached after it" in
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 1)), execution = rootReceipts).output.receipts
        .map(_.postStateOrStatus) ==
        Vector(PostStateOrStatus.PostState(EvmFixtures.hash(1)), PostStateOrStatus.PostState(EvmFixtures.hash(2))),
      "a receipt below EIP-658 carries the root as it stood after its own transaction, and each is a different root"
    )

  it should "ask for exactly one root per transaction" in
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 1)), execution = rootReceipts).rootsAsked == 2,
      "the root is taken once per transaction, after that transaction and before the next"
    )

  // ── The two changes nobody signed, and when each of them runs ─────────────

  "a scheduled irregular state change" should "run before the transactions do" in
    // The signer holds nothing until the change funds it, so the first
    // transaction is admissible only if the change has already run.
    assert(
      run(
        Seq(transfer(nonce = 0)),
        funded = 0,
        irregularStateChange = Some(world => world.setBalance(signer, Word(Funded)))
      ).output.receipts.length == 1,
      "a transaction in the same block sees the state an irregular change left, so the change runs first"
    )

  it should "leave the block rejected when it is absent, or the case above tests nothing" in
    // The control. Without it the case above would hold for a processor that
    // never ran the change at all and funded the signer some other way.
    assert(
      run(Seq(transfer(nonce = 0)), funded = 0).result ==
        Left(BlockRejection(0, Refusal.InsufficientAccountFunds)),
      "the signer must be unable to pay without the change, or the change is not what admitted the transaction"
    )

  "the consensus mechanism's own change" should "run after every transaction has been charged" in
    // It observes the beneficiary's balance at the moment it is called. The fees
    // are already there, so it cannot have run before the transactions.
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 1))).coinbaseAtClose == TransferSpend * 2 * GasPrice,
      "a reward is applied over the state the transactions left, which is why it runs last"
    )

  it should "run on a block carrying no transactions at all" in
    // Every surveyed client calls it unconditionally, and a mechanism with
    // nothing to write supplies a change that writes nothing. A processor
    // skipping it on an empty block would make "no reward" and "a reward of
    // zero" the same thing.
    assert(run(Seq.empty).coinbaseAtClose == BigInt(0), "the close runs on every block, including one that is empty")

  it should "not run on a block that was rejected" in
    assert(
      run(Seq(transfer(nonce = 1))).coinbaseAtClose == BigInt(-1),
      "a block this network does not accept reaches no close, because there is no block to close"
    )

  // ── A format this network does not carry is refused before it is priced ──

  "a block carrying a format these rules do not admit" should "be rejected for its format" in
    // The refusal is asked before anything is read of the transaction, which is
    // what keeps a format whose charge this build cannot compute from ever
    // reaching the pricing. A processor asking for a price first raises instead.
    assert(
      run(Seq(typedEnvelope)).result == Left(BlockRejection(0, Refusal.TypeNotAdmitted)),
      "a transaction of a format this network does not carry is refused for that and never priced"
    )

  "a block that was rejected" should "report where the offending transaction sits" in
    assert(
      run(Seq(transfer(nonce = 0), transfer(nonce = 0))).result ==
        Left(BlockRejection(1, Refusal.NonceMismatch)),
      "a refusal alone does not identify a transaction, so the index is carried with it"
    )

  // ── What this build cannot run is carried, not hidden ────────────────────

  "a block none of whose transactions met an unbuilt operation" should "report none" in
    assert(run(Seq(transfer(nonce = 0))).output.unbuilt.isEmpty, "nothing unbuilt was reached, and nothing is reported")

  "a block whose transactions met different unbuilt operations" should "report the one the earlier of them met" in
    // One is enough to say the block is not a chain result, so the block keeps
    // the first and discards the rest. A processor keeping the last reports the
    // other operation here, and one keeping none reports nothing.
    assert(
      run(
        Seq(transfer(nonce = 0), transfer(nonce = 1, to = Some(otherRecipient))),
        code = Map(recipient -> adds, otherRecipient -> multiplies),
        evm = cannotRunAddOrMul
      ).output.unbuilt == Some(Unsupported(Opcode.Add)),
      "the block reports the first gap any of its transactions reached, in the order it carries them"
    )

  it should "report the other one when the block carries them the other way round" in
    // The control. The same two operations, the same two transactions, the code
    // swapped between the recipients -- so both gaps are reachable and what the
    // case above observes is position rather than which operation it is.
    assert(
      run(
        Seq(transfer(nonce = 0), transfer(nonce = 1, to = Some(otherRecipient))),
        code = Map(recipient -> multiplies, otherRecipient -> adds),
        evm = cannotRunAddOrMul
      ).output.unbuilt == Some(Unsupported(Opcode.Mul)),
      "both operations must be reachable, or the case above holds for a processor that always names the same one"
    )
