package org.fukuii.consensus

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.chainspec.{ConsensusRules, HeaderConstants, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.crypto.Keccak256
import org.fukuii.evm.{Cost, EvmFixtures, Opcode, Operation, StateTrieWorldState, Unsupported, Word, WorldState}
import org.fukuii.evm.fixtures.{FixtureValues, VmFixtureRunner}
import org.fukuii.execution.{BlockRejection, Refusal}
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.StateTrie
import org.fukuii.types.{BlobGasTail, Block, BlockHeader, Withdrawal}
import org.scalatest.flatspec.AnyFlatSpec

/** Whether a block is valid against its parent, decided over published blocks.
  *
  * ==The known positives are the release's, not this tree's==
  *
  * Every block below is one [[PublishedBlocks]] carries, run from the genesis
  * state the release publishes beside it, so a commitment this validator
  * accepts is one the filling tool produced. A block assembled here would carry
  * roots derived by the same primitives the validator calls and could only show
  * that the two agree.
  *
  * ==Each commitment is refused by one mutation of the block that accepts it==
  *
  * The generated `blockchain_tests` tier refuses no block for a wrong state
  * root, receipts root, transactions root, bloom or gas figure, so a validator
  * that skipped any of those comparisons would pass it whole. Every mutation
  * below changes one field of [[PublishedBlocks.withdrawalToCreatedContract]]
  * and nothing else, and the refusal is asserted by name and, where the release
  * states it, by the value the block actually carries.
  *
  * ==What these cannot show==
  *
  * That a comparison refuses under the right name is shown; that every
  * published block's commitments are derived correctly is not, beyond the three
  * here -- that is the blockchain tier's to certify. None of these blocks predates
  * the merge, carries an ommer the release validates, or runs under a mechanism
  * other than the default one.
  */
class BlockValidatorSpec extends AnyFlatSpec:

  private val Cancun: UpgradeRules = ethereum.Upgrades.cancun

  private val Mainnet: UInt64 = UInt64.fromBits(1L)

  /** A published case's decoded literals, failing the test that asked for them
    * where one did not decode.
    *
    * Lazy, and asked only from inside a test, so a copying error fails the tests
    * that read the block, naming the literal, and leaves the suite running.
    */
  private def decodedOrFailed(published: Either[String, PublishedBlock]): PublishedBlock =
    published.fold(error => fail(error), identity)

  private lazy val WithdrawalBlock: PublishedBlock = decodedOrFailed(PublishedBlocks.withdrawalToCreatedContract)

  private lazy val LogsBlock: PublishedBlock = decodedOrFailed(PublishedBlocks.logsFromCalls)

  private lazy val BlobBlock: PublishedBlock = decodedOrFailed(PublishedBlocks.oneBlob)

  /** A value no published commitment below takes, standing for a wrong one. */
  private val Wrong: Hash = EvmFixtures.hash(0x5a)

  /** A world holding exactly the accounts `published`'s genesis commits to. */
  private def seeded(published: PublishedBlock): (StateTrie, StateTrieWorldState) =
    val trie = VmFixtureRunner.freshTrie()
    val world = new StateTrieWorldState(trie)
    FixtureValues.seed(world, published.pre) match
      case Left(error) => fail("the published pre-state did not seed: " + error)
      case Right(())   => (trie, world)

  /** The verdict, and the state root the world was left at. */
  final private case class Ran(verdict: BlockVerdict, rootAfter: Hash)

  /** Validates `block` against `published`'s genesis, from that genesis's state.
    *
    * The genesis resolves to the same rules as the block unless `parentRules`
    * says otherwise, which is what every published label below states: a block
    * at timestamp 12 or 1000 over a genesis at 0, both under one fork.
    */
  private def ran(published: PublishedBlock)(
      block: Block = published.block,
      rules: UpgradeRules = Cancun,
      engine: ConsensusEngine = ConsensusEngine.Unmodifying,
      chainId: UInt64 = Mainnet,
      parentRules: Option[UpgradeRules] = None
  ): Ran =
    val (trie, world) = seeded(published)
    val verdict = BlockValidator.validate(
      block = block,
      rules = rules,
      parent = Resolved(published.genesis, parentRules.getOrElse(rules)),
      engine = engine,
      world = world,
      destroyAccount = trie.destroyAccount,
      stateRoot = () => trie.stateRoot,
      blockHashAt = number => if number == BigInt(0) then published.publishedGenesisHash else EvmFixtures.hash(0),
      chainId = chainId
    )
    Ran(verdict, trie.stateRoot)

  /** The withdrawal block with its header edited and its body as published. */
  private def withHeader(edit: BlockHeader => BlockHeader): Block =
    WithdrawalBlock.block.copy(header = edit(WithdrawalBlock.block.header))

  private def withBlobGas(header: BlockHeader)(edit: BlobGasTail => BlobGasTail): BlockHeader =
    header.copy(tail = header.tail.map(fee => fee.copy(next = fee.next.map(w => w.copy(next = w.next.map(edit))))))

  private def withWithdrawalsRoot(header: BlockHeader, root: Hash): BlockHeader =
    header.copy(tail = header.tail.map(fee => fee.copy(next = fee.next.map(_.copy(withdrawalsRoot = root)))))

  private def isValid(verdict: BlockVerdict): Boolean = verdict match
    case BlockVerdict.Valid(_) => true
    case _                     => false

  /** Whether the literals in `published` decode and seed to exactly what the
    * release states for them.
    */
  private def transcribedIntact(published: PublishedBlock): Boolean =
    val (trie, _) = seeded(published)
    trie.stateRoot == published.genesis.stateRoot &&
    published.genesis.hash == published.publishedGenesisHash &&
    published.block.hash == published.publishedBlockHash

  private val ExtraDataOf33: Bytes = Bytes.fromIArray(IArray.fill(33)(0x2a.toByte))

  private val ExtraDataOf32: Bytes = Bytes.fromIArray(IArray.fill(32)(0x2a.toByte))

  /** Rules whose table prices `CALLER` in a way this build does not run.
    *
    * The beacon-roots contract's first operation is `CALLER`, so the call the
    * block makes before its transactions is what reaches it.
    */
  private val CannotRunCaller: UpgradeRules =
    Cancun.copy(evm = Cancun.evm.copy(table = Cancun.evm.table.adding(Operation(Opcode.Caller, Cost.Computed))))

  /** Cancun's rules without the merge's header constants, so an ommers
    * commitment in the header is not already refused by a header rule.
    */
  private val Unconstrained: UpgradeRules =
    Cancun.copy(header = Cancun.header.copy(constants = HeaderConstants.Unconstrained))

  /** The withdrawal block's genesis, carried as an ommer by a body it was never
    * an ommer of -- which is irrelevant to a commitment over the list.
    */
  private lazy val CarriedOmmers: Seq[BlockHeader] = Seq(WithdrawalBlock.genesis)

  /** A header shaped for the fork before withdrawals: its tail stops at the base
    * fee.
    */
  private lazy val WithoutWithdrawalsField: BlockHeader =
    WithdrawalBlock.block.header.copy(tail = WithdrawalBlock.block.header.tail.map(_.copy(next = None)))

  /** An engine accepting any extra data, as a mechanism with no length bound
    * does.
    */
  private val unboundedExtraData: ConsensusEngine = new ConsensusEngine:
    override def validateHeader(block: Resolved, parent: Resolved): Either[HeaderFault, Set[EngineRule]] =
      Right(Set.empty)

  /** An engine whose mechanism states a seal rule it does not run. */
  private val leavesTheSealUnrun: ConsensusEngine = new ConsensusEngine:
    override def validateHeader(block: Resolved, parent: Resolved): Either[HeaderFault, Set[EngineRule]] =
      super.validateHeader(block, parent).map(_ + EngineRule.Seal)

  /** An engine whose withdrawals leave state alone. */
  private val withdrawalsWriteNothing: ConsensusEngine = new ConsensusEngine:
    override def processWithdrawals(withdrawals: Seq[Withdrawal], destroyAccount: Address => Unit): WorldState => Unit =
      _ => ()

  /** An engine whose withdrawals are not defined over state the block's
    * transactions have not written.
    *
    * The withdrawal block's one withdrawal pays the contract its one transaction
    * creates, so a withdrawal reached ahead of that transaction finds no code at
    * its recipient. The published root cannot show the difference: crediting
    * the address first and creating the contract over that balance reaches the
    * same account.
    */
  private val needsTheTransactionsRun: ConsensusEngine = new ConsensusEngine:
    override def processWithdrawals(withdrawals: Seq[Withdrawal], destroyAccount: Address => Unit): WorldState => Unit =
      val credit = super.processWithdrawals(withdrawals, destroyAccount)
      world =>
        if withdrawals.exists(withdrawal => world.codeOf(withdrawal.address).isEmpty) then
          throw new IllegalStateException("a withdrawal reached ahead of the transaction that creates its recipient")
        else credit(world)

  /** The account the settlement below marks and the withdrawals beside it look
    * for. No published pre-state holds it.
    */
  private val SettlementMarker: Address = EvmFixtures.address(0x7e)

  /** An engine whose withdrawals are not defined over state its settlement has
    * not written.
    *
    * The settlement brings a marker account into being and the withdrawals
    * remove it again, so the block's published root still holds -- and a
    * validator running the withdrawals first finds no marker.
    */
  private val withdrawalsNeedTheSettlement: ConsensusEngine = new ConsensusEngine:
    override def settlement(
        rules: ConsensusRules,
        beneficiary: Address,
        number: BigInt,
        ommers: Seq[BlockHeader]
    ): WorldState => Unit =
      val settle = super.settlement(rules, beneficiary, number, ommers)
      world =>
        settle(world)
        world.setBalance(SettlementMarker, Word(BigInt(1)))
    override def processWithdrawals(withdrawals: Seq[Withdrawal], destroyAccount: Address => Unit): WorldState => Unit =
      val credit = super.processWithdrawals(withdrawals, destroyAccount)
      world =>
        if !world.accountExists(SettlementMarker) then
          throw new IllegalStateException("the withdrawals were reached ahead of the settlement")
        else
          destroyAccount(SettlementMarker)
          credit(world)

  /** An engine paying a block reward the fork resolved as none. */
  private val paysAReward: ConsensusEngine = new ConsensusEngine:
    override def rulesFrom(rules: UpgradeRules): UpgradeRules =
      rules.copy(consensus = rules.consensus.copy(blockReward = UInt256.fromLong(2).toOption.get))

  /** An engine supplying the fee market to a rule set that states none. */
  private val suppliesAFeeMarket: ConsensusEngine = new ConsensusEngine:
    override def rulesFrom(rules: UpgradeRules): UpgradeRules =
      rules.copy(header = rules.header.copy(feeMarket = rules.header.feeMarket.orElse(Cancun.header.feeMarket)))

  /** An engine whose header rule is not defined over a header that does not
    * come after its parent, which is what a difficulty formula is.
    */
  private val needsSuccession: ConsensusEngine = new ConsensusEngine:
    override def validateHeader(block: Resolved, parent: Resolved): Either[HeaderFault, Set[EngineRule]] =
      if block.header.timestamp.toBigInt <= parent.header.timestamp.toBigInt then
        throw new IllegalStateException("reached with a header that does not follow its parent")
      else Right(Set.empty)

  // ── The published blocks, as published ────────────────────────────────────

  "each published block's literals" should "decode and seed to what the release states for the withdrawal block" in
    assert(transcribedIntact(WithdrawalBlock), "a copying error must fail here, naming the literal, before any verdict")

  it should "decode and seed to what the release states for the block that logs" in
    assert(transcribedIntact(LogsBlock), "a copying error must fail here before any verdict")

  it should "decode and seed to what the release states for the block that carries a blob" in
    assert(transcribedIntact(BlobBlock), "a copying error must fail here before any verdict")

  "a published block" should "be valid against its genesis when it carries a withdrawal" in
    assert(
      isValid(ran(WithdrawalBlock)().verdict),
      "every commitment in this header was produced by the filling tool, so each derivation here must agree with it"
    )

  it should "be valid against its genesis when its logs set bloom bits" in
    assert(
      isValid(ran(LogsBlock)().verdict),
      "an empty bloom agrees with a derivation that answers a constant; this one does not"
    )

  it should "be valid against its genesis when it spends blob gas" in
    assert(
      isValid(ran(BlobBlock)().verdict),
      "a spend of zero agrees with a derivation that answers a constant; this one does not"
    )

  // ── One mutation per commitment, each refused by its own name ─────────────

  "a block stating another state root" should "be refused for the state root" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(stateRoot = Wrong))).verdict ==
        BlockVerdict.Invalid(BlockFault.StateRootMismatch(Wrong, WithdrawalBlock.block.header.stateRoot)),
      "the state the block produced is the one its published header states"
    )

  "a block stating another receipts root" should "be refused for the receipts root" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(receiptsRoot = Wrong))).verdict ==
        BlockVerdict.Invalid(BlockFault.ReceiptsRootMismatch(Wrong, WithdrawalBlock.block.header.receiptsRoot)),
      "the receipts the block produced are the ones its published header commits to"
    )

  "a block stating another transactions root" should "be refused for the transactions root" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(transactionsRoot = Wrong))).verdict ==
        BlockVerdict.Invalid(BlockFault.TransactionsRootMismatch(Wrong, WithdrawalBlock.block.header.transactionsRoot)),
      "the body's transactions are the ones its published header commits to"
    )

  "a block stating another logs bloom" should "be refused for the bloom" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(logsBloom = LogsBlock.block.header.logsBloom))).verdict ==
        BlockVerdict.Invalid(
          BlockFault.LogsBloomMismatch(
            LogsBlock.block.header.logsBloom,
            WithdrawalBlock.block.header.logsBloom
          )
        ),
      "a block that logged nothing produces the empty bloom its published header states"
    )

  "a block stating another gas figure" should "be refused for the gas used" in
    assert(
      ran(WithdrawalBlock)(block =
        withHeader(h => h.copy(gasUsed = UInt64.fromBits(h.gasUsed.toBigInt.toLong + 1)))
      ).verdict ==
        BlockVerdict.Invalid(
          BlockFault.GasUsedMismatch(
            WithdrawalBlock.block.header.gasUsed.toBigInt + 1,
            WithdrawalBlock.block.header.gasUsed.toBigInt
          )
        ),
      "one unit is still inside the gas limit, so only the figure the block used can refuse it"
    )

  "a block stating another withdrawals root" should "be refused for the withdrawals root" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(withWithdrawalsRoot(_, Wrong))).verdict ==
        BlockVerdict.Invalid(
          BlockFault.WithdrawalsRootMismatch(Wrong, WithdrawalBlock.block.header.withdrawalsRoot.get)
        ),
      "the body's withdrawals are the ones its published header commits to"
    )

  "a block stating a blob's worth of gas it did not spend" should "be refused for the blob gas used" in
    assert(
      ran(WithdrawalBlock)(block =
        withHeader(withBlobGas(_)(_.copy(blobGasUsed = UInt64.fromBits(131072L))))
      ).verdict ==
        BlockVerdict.Invalid(BlockFault.BlobGasUsedMismatch(BigInt(131072), BigInt(0))),
      "a whole blob under the fork's maximum passes every header bound, so only the body can refuse it"
    )

  "a block whose body carries ommers its header does not commit to" should "be refused for the ommers hash" in
    assert(
      (ran(WithdrawalBlock)(block =
        WithdrawalBlock.block.copy(body = WithdrawalBlock.block.body.copy(ommers = CarriedOmmers))
      ).verdict match
        case BlockVerdict.Invalid(BlockFault.OmmersHashMismatch(stated, _)) =>
          stated == WithdrawalBlock.block.header.ommersHash
        case _ => false
      ),
      "the header still commits to the empty list, so the header rule accepts it and the body commitment refuses it"
    )

  "a block naming another parent" should "be refused for the parent hash" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(parentHash = Wrong))).verdict ==
        BlockVerdict.Invalid(BlockFault.ParentHashMismatch(Wrong, WithdrawalBlock.publishedGenesisHash)),
      "the parent this block is validated against is the one the release publishes"
    )

  "a block carrying 33 bytes of extra data" should "be refused by the engine's extra-data bound" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(extraData = ExtraDataOf33))).verdict ==
        BlockVerdict.Invalid(BlockFault.Header(HeaderFault.ExtraDataAboveLimit(33, 32))),
      "extra data moves no commitment, so only the bound can refuse it"
    )

  // ── The other side of each boundary ───────────────────────────────────────

  "a block carrying 32 bytes of extra data" should "be valid" in
    assert(
      isValid(ran(WithdrawalBlock)(block = withHeader(_.copy(extraData = ExtraDataOf32))).verdict),
      "every source read refuses on more than 32 bytes, so exactly 32 is accepted"
    )

  "a header committing to ommers the rules do not fix" should "be refused for the ommers hash over an empty body" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(ommersHash = Wrong)), rules = Unconstrained).verdict ==
        BlockVerdict.Invalid(BlockFault.OmmersHashMismatch(Wrong, HeaderValidator.EmptyOmmersHash)),
      "where no header rule fixes the commitment, the body is the only thing it can be compared against"
    )

  "a block whose header commits to withdrawals its body does not carry" should "be refused as missing them" in
    assert(
      ran(WithdrawalBlock)(block =
        WithdrawalBlock.block.copy(body = WithdrawalBlock.block.body.copy(withdrawals = None))
      ).verdict ==
        BlockVerdict.Invalid(BlockFault.WithdrawalsMissing),
      "a commitment over a list the body lacks is a different fault from a commitment over another list"
    )

  "a block carrying an empty withdrawals list below the withdrawals fork" should "be refused as carrying one" in
    assert(
      ran(WithdrawalBlock)(
        block = Block(WithoutWithdrawalsField, WithdrawalBlock.block.body.copy(withdrawals = Some(Seq.empty))),
        rules = ethereum.Upgrades.paris
      ).verdict == BlockVerdict.Invalid(BlockFault.WithdrawalsUnexpected),
      "an empty list is not an absent one, and a header with no commitment admits only the absent one"
    )

  "a block below the withdrawals fork carrying no withdrawals list" should "be run" in {
    val absent = ran(WithdrawalBlock)(
      block = Block(WithoutWithdrawalsField, WithdrawalBlock.block.body.copy(withdrawals = None)),
      rules = ethereum.Upgrades.paris
    )
    assert(
      absent.rootAfter != WithdrawalBlock.genesis.stateRoot,
      "no commitment and no list agree, so nothing stops the block before its transaction writes state"
    )
  }

  // ── What stops a block before it runs, and what it runs into ──────────────

  "a block reaching an operation this build does not run" should "be undecided rather than refused" in
    assert(
      ran(WithdrawalBlock)(rules = CannotRunCaller).verdict ==
        BlockVerdict.Undecided(RuleNotRun.Operation(Unsupported(Opcode.Caller))),
      "what such a block produces is not a chain result, so a commitment disagreeing with it refuses nothing"
    )

  it should "still be refused for a body commitment that needs nothing to run" in
    assert(
      ran(WithdrawalBlock)(
        block = withHeader(withBlobGas(_)(_.copy(blobGasUsed = UInt64.fromBits(131072L)))),
        rules = CannotRunCaller
      ).verdict == BlockVerdict.Invalid(BlockFault.BlobGasUsedMismatch(BigInt(131072), BigInt(0))),
      "a spend derived from the body is decided before anything runs, so an unbuilt operation cannot hide it"
    )

  it should "still be refused for a wrong transactions root" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(transactionsRoot = Wrong)), rules = CannotRunCaller).verdict ==
        BlockVerdict.Invalid(BlockFault.TransactionsRootMismatch(Wrong, WithdrawalBlock.block.header.transactionsRoot)),
      "the root is derived from the body before anything runs, so an unbuilt operation cannot hide it"
    )

  it should "still be refused for a wrong withdrawals root" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(withWithdrawalsRoot(_, Wrong)), rules = CannotRunCaller).verdict ==
        BlockVerdict.Invalid(
          BlockFault.WithdrawalsRootMismatch(Wrong, WithdrawalBlock.block.header.withdrawalsRoot.get)
        ),
      "the root is derived from the body before anything runs, so an unbuilt operation cannot hide it"
    )

  it should "still be refused for a wrong ommers hash" in
    assert(
      ran(WithdrawalBlock)(
        block = withHeader(_.copy(ommersHash = Wrong)),
        rules = Unconstrained.copy(evm = CannotRunCaller.evm)
      ).verdict == BlockVerdict.Invalid(BlockFault.OmmersHashMismatch(Wrong, HeaderValidator.EmptyOmmersHash)),
      "the hash is taken over the body before anything runs, so an unbuilt operation cannot hide it"
    )

  it should "be undecided where a transaction it carries is refused after that operation" in
    assert(
      ran(WithdrawalBlock)(rules = CannotRunCaller, chainId = UInt64.fromBits(2L)).verdict ==
        BlockVerdict.Undecided(RuleNotRun.Operation(Unsupported(Opcode.Caller))),
      "the beacon-root call reached CALLER first, so the refusal was reached over state that is no chain result"
    )

  "a block whose engine did not run one of its header rules" should "be undecided once every rule run accepts it" in {
    val unrun = ran(WithdrawalBlock)(engine = leavesTheSealUnrun)
    assert(
      unrun.verdict == BlockVerdict.Undecided(RuleNotRun.EngineRules(Set(EngineRule.Seal))) &&
        unrun.rootAfter == WithdrawalBlock.block.header.stateRoot,
      "execution reads no seal, so the block runs and is compared in full, leaving the state its header commits to"
    )
  }

  it should "still be refused for a wrong transactions root" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(transactionsRoot = Wrong)), engine = leavesTheSealUnrun).verdict ==
        BlockVerdict.Invalid(BlockFault.TransactionsRootMismatch(Wrong, WithdrawalBlock.block.header.transactionsRoot)),
      "a comparison this build runs refusing the block decides it, whatever the rule not run would say"
    )

  it should "still be refused for a wrong state root" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(stateRoot = Wrong)), engine = leavesTheSealUnrun).verdict ==
        BlockVerdict.Invalid(BlockFault.StateRootMismatch(Wrong, WithdrawalBlock.block.header.stateRoot)),
      "a comparison over the run refuses the block before the rule not run is asked"
    )

  it should "still be refused for a transaction its rules refuse" in
    assert(
      ran(WithdrawalBlock)(engine = leavesTheSealUnrun, chainId = UInt64.fromBits(2L)).verdict ==
        BlockVerdict.Invalid(BlockFault.TransactionRefused(BlockRejection(0, Refusal.WrongChainId, None))),
      "a refusal reached over a chain result decides the block before the rule not run is asked"
    )

  "the default engine" should "name the difficulty and the seal where the header constants fix neither" in
    assert(
      ran(WithdrawalBlock)(rules = Unconstrained).verdict ==
        BlockVerdict.Undecided(RuleNotRun.EngineRules(Set(EngineRule.Difficulty, EngineRule.Seal))),
      "every mechanism read under such constants states both, and a block reaching this default was paired with none"
    )

  "a block whose body carries ommers its header commits to" should "be undecided without running" in {
    val carried = ran(WithdrawalBlock)(
      block = Block(
        WithdrawalBlock.block.header.copy(ommersHash = Keccak256.hash(RlpCodec.encodeTo(CarriedOmmers))),
        WithdrawalBlock.block.body.copy(ommers = CarriedOmmers)
      ),
      rules = Unconstrained
    )
    assert(
      carried.verdict == BlockVerdict.Undecided(RuleNotRun.OmmerValidation(1)) &&
        carried.rootAfter == WithdrawalBlock.genesis.stateRoot,
      "ommers nothing validates must not reach a settlement that relies on their having been validated"
    )
  }

  "a block breaking a header rule" should "be refused for it before anything runs" in {
    val broken = ran(WithdrawalBlock)(block = withHeader(_.copy(timestamp = UInt64.Zero)))
    assert(
      broken.verdict == BlockVerdict.Invalid(
        BlockFault.Header(HeaderFault.TimestampNotAfterParent(BigInt(0), BigInt(0)))
      ) && broken.rootAfter == WithdrawalBlock.genesis.stateRoot,
      "the beacon-root call would write the contract's storage, so an unchanged root is a block that never ran"
    )
  }

  "a block carrying a transaction its rules refuse" should "be refused for that transaction" in
    assert(
      ran(WithdrawalBlock)(chainId = UInt64.fromBits(2L)).verdict ==
        BlockVerdict.Invalid(BlockFault.TransactionRefused(BlockRejection(0, Refusal.WrongChainId, None))),
      "the transaction is signed for chain 1, so asking as chain 2 refuses it and the block with it"
    )

  "a block whose excess its parent does not require" should "be refused before anything prices that excess" in
    assert(
      ran(WithdrawalBlock)(
        block = withHeader(withBlobGas(_)(_.copy(excessBlobGas = UInt64.fromBits(1L)))),
        rules = Cancun.copy(evm = Cancun.evm.copy(blobBaseFeeUpdateFraction = Some(BigInt(0))))
      ).verdict == BlockVerdict.Invalid(
        BlockFault.Header(HeaderFault.ExcessBlobGasMismatch(UInt64.fromBits(1L), BigInt(0)))
      ),
      "a fraction of zero divides by zero when priced, so this answers only if the header rule ran first"
    )

  // ── What the engine decides ───────────────────────────────────────────────

  "an engine with no extra-data bound" should "accept a block the default engine refuses for its extra data" in
    assert(
      isValid(
        ran(WithdrawalBlock)(block = withHeader(_.copy(extraData = ExtraDataOf33)), engine = unboundedExtraData).verdict
      ),
      "the bound reaches through the engine, so an engine without one is what decides"
    )

  "an engine whose withdrawals leave state alone" should "be what the block's withdrawals do" in
    assert(
      (ran(WithdrawalBlock)(engine = withdrawalsWriteNothing).verdict match
        case BlockVerdict.Invalid(BlockFault.StateRootMismatch(stated, _)) =>
          stated == WithdrawalBlock.block.header.stateRoot
        case _ => false
      ),
      "the published root includes the withdrawal's credit, so a validator crediting it itself would accept this"
    )

  "an engine's withdrawals" should "run over the state the block's transactions left" in
    assert(
      isValid(ran(WithdrawalBlock)(engine = needsTheTransactionsRun).verdict),
      "withdrawals are processed after the transactions, and this block's pays the contract its transaction creates"
    )

  it should "run over the state its settlement left" in
    // UNSETTLED, and pinned as an arrangement rather than as a rule:
    // `BlockValidator.run` records the sources and the trigger that reverses it.
    assert(
      isValid(ran(WithdrawalBlock)(engine = withdrawalsNeedTheSettlement).verdict),
      "the settlement writes a marker the withdrawals remove, so running them the other way round raises"
    )

  "an engine paying a reward the fork resolved as none" should "be what the block settles" in
    assert(
      (ran(WithdrawalBlock)(engine = paysAReward).verdict match
        case BlockVerdict.Invalid(BlockFault.StateRootMismatch(stated, _)) =>
          stated == WithdrawalBlock.block.header.stateRoot
        case _ => false
      ),
      "a validator that did not apply the engine's rules would settle the fork's reward of nothing and accept this"
    )

  "an engine's transformation" should "reach the parent's rules as well as the block's" in
    // The parent is resolved at a fork with no fee market. Through the engine it
    // has one, so this block is not the first under a market and its gas limit
    // is compared with its parent's own; untransformed, the block would be the
    // first, compared with twice that, and refused.
    assert(
      isValid(ran(WithdrawalBlock)(engine = suppliesAFeeMarket, parentRules = Some(ethereum.Upgrades.berlin)).verdict),
      "the parent's rules pass through the same engine as the block's"
    )

  "an engine's header rule" should "run only after the rules every mechanism shares have accepted the header" in
    assert(
      ran(WithdrawalBlock)(block = withHeader(_.copy(timestamp = UInt64.Zero)), engine = needsSuccession).verdict ==
        BlockVerdict.Invalid(BlockFault.Header(HeaderFault.TimestampNotAfterParent(BigInt(0), BigInt(0)))),
      "an override may take succession as settled, so reaching it first would raise rather than refuse"
    )
