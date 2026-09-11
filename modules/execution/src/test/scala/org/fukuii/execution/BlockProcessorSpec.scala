package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.crypto.Secp256k1
import org.fukuii.evm.{BlobGas, Cost, EvmFixtures, EvmRules, Opcode, Operation, Unsupported, Word, WorldState}
import org.fukuii.types.{PostStateOrStatus, Sender, SigningPreimage, Transaction, TransactionType, Withdrawal}

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

  /** A withdrawal of `gwei` to `to`.
    *
    * The two counters are unread by anything this file asserts and are set to
    * the position so that no two rows of one list are equal, which is what keeps
    * a commitment over several of them from collapsing.
    */
  private def withdrawal(index: Int, to: Address, gwei: Long): Withdrawal =
    Withdrawal(UInt64.fromBits(index.toLong), UInt64.fromBits(index.toLong), to, UInt64.fromBits(gwei))

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

  /** A typed envelope, unsigned, so a block holding one under rules that do not
    * admit the format exercises the refusal rather than the pricing -- the
    * refusal is asked before anything is read of the signature.
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

  /** The same envelope, signed, so a block carrying one under rules that admit
    * the format reaches a receipt rather than a refusal.
    *
    * The access-list format is the one typed shape this build can settle: it
    * states a price rather than a cap and a tip, so [[BlockProcessor]] can work
    * out what it offers without a base fee.
    */
  private def typedTransfer(nonce: Long): Transaction.AccessList =
    val unsigned: Transaction.AccessList = typedEnvelope.copy(nonce = UInt64.fromBits(nonce))
    val signature = Secp256k1
      .sign(SigningPreimage.hashForSigning(unsigned, None), signing)
      .getOrElse(fail("the fixture transaction could not be signed"))
    unsigned.copy(
      yParity = quantity(BigInt(signature.recoveryId)),
      r = quantity(signature.r),
      s = quantity(signature.s)
    )

  private val statusReceipts: ExecutionRules =
    ExecutionRules(touchedEmptyAccountsAreDeleted = false, receiptCarriesStatus = true, maxRefundQuotient = BigInt(2))

  private val rootReceipts: ExecutionRules =
    ExecutionRules(touchedEmptyAccountsAreDeleted = false, receiptCarriesStatus = false, maxRefundQuotient = BigInt(2))

  private val legacyOnly: AdmissionRules =
    AdmissionRules(
      admittedTypes = Set(TransactionType.Legacy),
      signatureMayCarryChainId = false,
      signatureSMustBeLow = false
    )

  /** Rules carrying a second format, so that what a receipt says about the
    * format its transaction had is answerable by more than one value.
    */
  private val alsoTypedEnvelopes: AdmissionRules =
    legacyOnly.copy(admittedTypes = Set(TransactionType.Legacy, TransactionType.AccessList))

  /** Rules carrying the blob format, so a block can hold a transaction that
    * spends blob gas.
    */
  private val alsoBlobs: AdmissionRules =
    legacyOnly.copy(admittedTypes = Set(TransactionType.Legacy, TransactionType.Blob))

  /** A commitment whose version byte is the one these rules know.
    *
    * Built from the version rather than from a literal digest, so a case below
    * cannot pass against a build that stopped reading the byte.
    */
  private def commitment(index: Int): Hash =
    Hash.fromBytesTruncating(
      (IArray(BlobGas.VersionedHashVersion) ++ IArray.fill(31)(index.toByte))
    )

  /** What a block accounting for blob gas offers a transaction that carries
    * some: the floor charge, and the fork's own maximum.
    *
    * The charge is the floor because this spec is about ACCUMULATION rather
    * than about pricing -- `org.fukuii.evm.BlobGasPriceSpec` certifies the
    * derivation, and a charge of one keeps a balance below within a figure a
    * reader can check by hand.
    */
  private def blobAccounting(maxBlobs: Int = 6): BlobGasAccounting =
    BlobGasAccounting(charge = BigInt(1), maximum = BigInt(maxBlobs) * BlobGas.PerBlob)

  /** A signed blob transaction at `nonce`, carrying `blobs` commitments.
    *
    * It states a ceiling and a tip rather than a price, because that is the
    * only shape its format has -- so a block carrying one must state a charge,
    * which every case below does.
    */
  private def blobTransfer(nonce: Long, blobs: Int): Transaction.Blob =
    val unsigned = Transaction.Blob(
      chainId = chainId,
      nonce = UInt64.fromBits(nonce),
      maxPriorityFeePerGas = quantity(BigInt(0)),
      maxFeePerGas = quantity(GasPrice),
      gasLimit = UInt64.fromBits(AskedPerTransaction),
      recipient = recipient,
      value = UInt256.Zero,
      data = Bytes.Empty,
      accessList = Seq.empty,
      maxFeePerBlobGas = quantity(BigInt(1)),
      blobVersionedHashes = (0 until blobs).map(commitment),
      yParity = UInt256.Zero,
      r = UInt256.Zero,
      s = UInt256.Zero
    )
    val signature = Secp256k1
      .sign(SigningPreimage.hashForSigning(unsigned, None), signing)
      .getOrElse(fail("the fixture transaction could not be signed"))
    unsigned.copy(
      yParity = quantity(BigInt(signature.recoveryId)),
      r = quantity(signature.r),
      s = quantity(signature.s)
    )

  /** Emits one empty log and stops, so a block's derived log sequence has
    * something in it that a receipt also holds.
    */
  private val emitsALog: Bytes = EvmFixtures.bytesOf("0x60006000a0")

  /** Adds against an empty stack, so the invocation halts rather than
    * stopping.
    */
  private val halts: Bytes = EvmFixtures.bytesOf("0x01")

  /** Reverts over an empty region, so the invocation ends the one way that
    * keeps its remaining gas.
    */
  private val reverts: Bytes = EvmFixtures.bytesOf("0x60006000fd")

  /** Rules carrying `REVERT`, priced from its operands as the document that
    * introduces it leaves it.
    *
    * The base table predates the operation, so an invocation over [[reverts]]
    * would otherwise meet an entry that is not there and end as a halt -- which
    * is the outcome the case using this has to be told apart from.
    */
  private val admittingRevert: EvmRules =
    EvmFixtures.rules.copy(table = EvmFixtures.rules.table.adding(Operation(Opcode.Revert, Cost.Computed)))

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

  /** A stand-in for a state root, derived from the world rather than from the
    * number of times a root has been asked for.
    *
    * The signer's transaction count moves once, at the moment a transaction
    * settles, so this answers one value before a given transaction, another
    * after it, and another again once the whole block has run. That is what
    * lets a case naming a root say which moment the root was taken at -- a
    * figure derived from the call alone answers the same sequence whenever it
    * is called, so every such case would hold for a processor taking its roots
    * at the wrong point.
    */
  private def rootOf(world: EvmFixtures.MapWorldState): Hash =
    EvmFixtures.hash(world.nonceOf(signer).toBigInt.toInt)

  /** The root [[rootOf]] answers once `settled` of the block's transactions
    * have settled.
    */
  private def rootAfter(settled: Int): Hash = EvmFixtures.hash(settled)

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
      evm: EvmRules = EvmFixtures.rules,
      admission: AdmissionRules = legacyOnly,
      withdrawals: Option[Seq[Withdrawal]] = None,
      blobGas: Option[BlobGasAccounting] = None,
      baseFee: Option[BigInt] = None,
      systemCalls: Seq[SystemCall] = Seq.empty
  ): Ran =
    val world = new EvmFixtures.MapWorldState
    world.setBalance(signer, Word(funded))
    code.foreach((address, bytes) => world.setCode(address, bytes))
    var rootsAsked = 0
    var coinbaseAtClose = BigInt(-1)
    def rootAfterTransaction(): Hash =
      rootsAsked += 1
      rootOf(world)
    val result = BlockProcessor.process(
      transactions = transactions,
      world = world,
      destroyAccount = _ => (),
      stateRootAfterTransaction = () => rootAfterTransaction(),
      block = EvmFixtures.block.copy(coinbase = coinbase, gasLimit = blockGasLimit, baseFee = baseFee),
      blockHashAt = EvmFixtures.blockHashAt,
      chainId = chainId,
      evm = evm,
      execution = execution,
      admission = admission,
      irregularStateChange = irregularStateChange,
      consensusStateChange = closing => coinbaseAtClose = closing.balanceOf(coinbase).toBigInt,
      withdrawals = withdrawals,
      blobGas = blobGas,
      systemCalls = systemCalls
    )
    Ran(result, world, rootsAsked, coinbaseAtClose)

  private val beaconRoots: SystemCall.Target = SystemCall.Target.BeaconRoots

  // ── A transaction runs against what the one before it left ────────────────

  // ── What the system-call sequence admits ──────────────────────────────────

  /** A system call is uncharged, so the sequence is where its cost is bounded.
    *
    * Each entry runs its target's code with [[SystemCall.GasLimit]] gas and
    * consults neither [[BlockOutput.gasUsed]] nor the block's own limit, so
    * nothing downstream notices a sequence that repeats. The bound is that each
    * proposal makes its call once per block, which makes the repetition a
    * caller's mistake -- and a thrown precondition rather than a
    * [[BlockRejection]], because no chain rule was broken and a block carrying
    * one is not a block this layer should answer for.
    */
  "a block making one proposal's system call twice" should "be refused as a precondition rather than run twice" in
    assert(
      intercept[IllegalArgumentException](
        run(Seq.empty, systemCalls = Seq(SystemCall(beaconRoots, Bytes.Empty), SystemCall(beaconRoots, Bytes.Empty)))
      ).getMessage.contains("once"),
      "a repeated target is 30,000,000 gas a second time that reaches no total and no limit"
    )

  it should "run a sequence whose targets are distinct" in
    assert(
      run(Seq.empty, systemCalls = Seq(SystemCall(beaconRoots, Bytes.Empty))).output.receipts.isEmpty,
      "one call per target is the shape a block actually makes, and the precondition must leave it alone"
    )

  /** The target is a closed set, so an arbitrary address cannot be given one.
    *
    * Asserted as a compile failure because that is the whole of the constraint:
    * a runtime check would still admit the call, and what makes an unvouched
    * address unreachable is that [[SystemCall]] does not take one.
    *
    * **The positive arm is what makes the negative one mean anything.** A
    * snippet that fails to compile for an unrelated reason -- a member that does
    * not exist, a name that does not resolve -- passes `assertDoesNotCompile`
    * just as well as one refused for its type, and reads identically. The two
    * snippets below differ in exactly one term, so the second compiling is what
    * establishes that the first is refused for that term and not for its
    * surroundings.
    */
  it should "not accept a bare address as a system-call target" in
    assertDoesNotCompile("SystemCall(coinbase, Bytes.Empty)")

  it should "accept a proposal-named target in the same position" in
    assertCompiles("SystemCall(SystemCall.Target.BeaconRoots, Bytes.Empty)")

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

  it should "state the format of a typed envelope, where the rules admit one" in
    // What makes the case above mean anything. Every other rule set here admits
    // the legacy format alone, so nothing else can tell a receipt that reads
    // the transaction it came from apart from one with that format written in.
    assert(
      run(Seq(typedTransfer(nonce = 0)), admission = alsoTypedEnvelopes).output.receipts
        .map(_.transactionType) == Vector(TransactionType.AccessList),
      "a receipt carries the envelope its own transaction had, and not the one its network's earlier ones had"
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

  it should "state that a transaction that halted did not succeed" in
    // The other half of the field, which a receipts root commits to exactly as
    // it commits to the first. Without it a status written as succeeded
    // whatever the transaction did satisfies the case above.
    assert(
      run(Seq(transfer(nonce = 0)), code = Map(recipient -> halts)).output.receipts.head.postStateOrStatus ==
        PostStateOrStatus.Failed,
      "a receipt states how its own transaction ended, and a transaction that halted did not succeed"
    )

  it should "state that a transaction that reverted did not succeed" in
    // The ending EIP-658 was written for, and the one a status derived from gas
    // would get wrong: EIP-140 made it possible to fail while keeping gas, so
    // "it is no longer possible for users to assume that a transaction failed
    // iff it consumed all gas", and the status is 0 "due to any operation that
    // can cause the transaction or top-level call to revert" (`ethereum/EIPs` @
    // `dbfa6bee8`, `EIPS/eip-658.md`, Final).
    assert(
      run(
        Seq(transfer(nonce = 0)),
        code = Map(recipient -> reverts),
        evm = admittingRevert
      ).output.receipts.head.postStateOrStatus == PostStateOrStatus.Failed,
      "a transaction whose top-level call reverted carries a status of failure"
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
        Vector(PostStateOrStatus.PostState(rootAfter(1)), PostStateOrStatus.PostState(rootAfter(2))),
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
    // The specification calls it unconditionally -- apply_body reaches
    // pay_rewards on every block, whatever the transaction count -- and a
    // mechanism with nothing to write supplies a change that writes nothing. A
    // processor
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

  // ── The withdrawals a block carries ───────────────────────────────────────

  "a block whose body carries no withdrawals field" should "state no commitment over them" in
    // The absence and an empty list are different facts about a block, and a
    // processor answering the empty trie's root here would make a header that
    // omitted the field indistinguishable from one that stated that root.
    assert(run(Seq(transfer(nonce = 0))).output.withdrawalsRoot.isEmpty, "no field carried is no commitment stated")

  "a block carrying an empty withdrawals list" should "state the empty commitment rather than none" in
    assert(
      run(Seq(transfer(nonce = 0)), withdrawals = Some(Seq.empty)).output.withdrawalsRoot ==
        Some(Withdrawals.root(Seq.empty)),
      "a list with nothing in it still commits to something"
    )

  "a block carrying withdrawals" should "credit each recipient" in
    assert(
      run(Seq.empty, withdrawals = Some(Seq(withdrawal(0, otherRecipient, 3)))).world
        .balanceOf(otherRecipient)
        .toBigInt == BigInt(3) * Withdrawals.WeiPerGwei,
      "a withdrawal is a balance increase nobody signed"
    )

  it should "state the commitment over exactly the list it carried" in
    assert(
      run(Seq.empty, withdrawals = Some(Seq(withdrawal(0, otherRecipient, 3)))).output.withdrawalsRoot ==
        Some(Withdrawals.root(Seq(withdrawal(0, otherRecipient, 3)))),
      "the commitment a header must state is the one over the block's own list"
    )

  it should "credit them after the transactions rather than before" in
    // The signer is funded below what one transfer costs and the withdrawal
    // would cover the difference, so a processor crediting first admits the
    // transaction and one crediting second refuses it. EIP-4895 states only
    // this ordering -- "processed after any user-level transactions are
    // applied" -- and it is the half a fixture of a funded block cannot show.
    assert(
      run(
        Seq(transfer(nonce = 0)),
        funded = BigInt(1),
        withdrawals = Some(Seq(withdrawal(0, signer, 1000000000L)))
      ).result == Left(BlockRejection(0, Refusal.InsufficientAccountFunds)),
      "a transaction cannot spend a withdrawal that lands in the same block"
    )

  it should "credit them before the mechanism's own change, so the close sees them" in
    // The order of these two is unobservable on every network -- no rule set
    // has both a block reward and withdrawals -- so this pins the arrangement
    // rather than a rule, and is what a later edit that moved one of them past
    // the other would have to answer for.
    assert(
      run(Seq.empty, withdrawals = Some(Seq(withdrawal(0, coinbase, 2)))).coinbaseAtClose ==
        BigInt(2) * Withdrawals.WeiPerGwei,
      "the mechanism's change runs last, so it observes what the withdrawals left"
    )

  it should "not be credited at all on a block that was rejected" in
    // The credit sits with the close, past the fold, so a block the network
    // does not accept reaches neither. A processor crediting before it knew the
    // block was good would leave a balance behind that no accepted block
    // explains.
    assert(
      run(
        Seq(transfer(nonce = 1)),
        withdrawals = Some(Seq(withdrawal(0, otherRecipient, 3)))
      ).world.accountExists(otherRecipient) == false,
      "there is no block, so there is nothing to credit"
    )

  // ── The commitment the output states ─────────────────────────────────────

  "the commitment a block states" should "be the one over its own list and not another" in
    // What a caller holding a header compares against. Both directions are
    // asserted in one case because the pair is what carries the meaning: the
    // right list agrees and a list differing by one Gwei does not.
    assert(
      run(Seq.empty, withdrawals = Some(Seq(withdrawal(0, otherRecipient, 3)))).output.withdrawalsRoot ==
        Some(Withdrawals.root(Seq(withdrawal(0, otherRecipient, 3)))) &&
        run(Seq.empty, withdrawals = Some(Seq(withdrawal(0, otherRecipient, 3)))).output.withdrawalsRoot !=
        Some(Withdrawals.root(Seq(withdrawal(0, otherRecipient, 4)))),
      "a commitment identifies the list it was taken over"
    )

  // ── The blob gas a block spends ──────────────────────────────────────────

  "a block that accounts for no blob gas" should "state no figure for it" in
    // The absence, which is a different answer from a zero. A block below the
    // accounting has no such header field at all, and a caller comparing an
    // absent figure against a header that states one is what catches a body
    // carrying blobs where the fork admits none.
    assert(
      run(Seq(transfer(nonce = 0))).output.blobGasUsed.isEmpty,
      "a fork with no blob schedule commits to nothing about blob gas"
    )

  "a block that accounts for blob gas" should "state zero where it carries none" in
    // The other half of that pair, and the case where the two are hardest to
    // tell apart: a block at the fork carrying no blob transaction has spent
    // nothing and still commits to a figure.
    assert(
      run(Seq(transfer(nonce = 0)), blobGas = Some(blobAccounting())).output.blobGasUsed.contains(BigInt(0)),
      "a block that could have carried blobs and did not has spent zero"
    )

  it should "state nothing but zero for a block with no transactions at all" in
    // The empty block, which reaches the figure through a different path: the
    // fold never runs, so what the output carries is what the opening value
    // held rather than anything accumulated.
    assert(
      run(Seq.empty, blobGas = Some(blobAccounting())).output.blobGasUsed.contains(BigInt(0)),
      "an empty block still answers"
    )

  it should "accumulate what its transactions carried" in
    // The figure a header commits to. Two blob transactions carrying two
    // commitments each is four blobs, which no single-transaction tier can
    // produce -- a state fixture is one transaction against an empty block.
    assert(
      run(
        Seq(blobTransfer(nonce = 0, blobs = 2), blobTransfer(nonce = 1, blobs = 2)),
        admission = alsoBlobs,
        blobGas = Some(blobAccounting()),
        baseFee = Some(BigInt(0))
      ).output.blobGasUsed.contains(BigInt(4) * BlobGas.PerBlob),
      "four blobs across two transactions"
    )

  it should "count only the transactions that carry blobs" in
    // A block holding both shapes, so the accumulation cannot be a count of
    // transactions or a constant per block.
    assert(
      run(
        Seq(transfer(nonce = 0), blobTransfer(nonce = 1, blobs = 3)),
        admission = alsoBlobs,
        blobGas = Some(blobAccounting()),
        baseFee = Some(BigInt(0))
      ).output.blobGasUsed.contains(BigInt(3) * BlobGas.PerBlob),
      "an ordinary transfer spends no blob gas"
    )

  "the blob allowance" should "be what the block has LEFT, not its whole maximum" in
    // THE RULE NO STATE FIXTURE CAN REACH, and the one the specification and a
    // production client disagree about. `ethereum/execution-specs` @
    // `0cc100eb1` `src/ethereum/forks/cancun/fork.py:432` measures each
    // transaction against `MAX_BLOB_GAS_PER_BLOCK - block_output.blob_gas_used`
    // while `besu-eth/besu` @ `b330564a9`
    // `MainnetTransactionValidator.java:228-234` measures it against the
    // maximum itself. The two agree on a block carrying one blob transaction
    // and disagree here: four blobs and then three, against a maximum of six.
    //
    // A state fixture is one transaction against an otherwise empty block, so
    // its allowance is always the whole maximum -- which is why the published
    // corpus cannot separate the two readings and this case is the only thing
    // that does.
    assert(
      run(
        Seq(blobTransfer(nonce = 0, blobs = 4), blobTransfer(nonce = 1, blobs = 3)),
        admission = alsoBlobs,
        blobGas = Some(blobAccounting()),
        baseFee = Some(BigInt(0))
      ).result == Left(BlockRejection(1, Refusal.BlobGasAllowanceExceeded)),
      "the second transaction wants three blobs where two remain"
    )

  it should "admit a block whose transactions total exactly the maximum" in
    // The off-by-one in the other direction, and the negative control for the
    // case above: four blobs and then two is six, which the fork allows. A
    // build comparing with `>=` refuses this and a build ignoring the
    // remainder admits the case above; only one reading passes both.
    assert(
      run(
        Seq(blobTransfer(nonce = 0, blobs = 4), blobTransfer(nonce = 1, blobs = 2)),
        admission = alsoBlobs,
        blobGas = Some(blobAccounting()),
        baseFee = Some(BigInt(0))
      ).output.blobGasUsed.contains(BigInt(6) * BlobGas.PerBlob),
      "exactly the maximum is spent, not exceeded"
    )
