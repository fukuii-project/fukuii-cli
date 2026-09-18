package org.fukuii.consensus

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.crypto.Keccak256
import org.fukuii.evm.{BlobGas, BlobGasPrice, BlockContext, BlockRandomness, Unsupported, WorldState}
import org.fukuii.execution.{
  BlobGasAccounting,
  BlockOutput,
  BlockProcessor,
  BlockRejection,
  ExecutionRequests,
  RequestRules,
  SystemCall
}
import org.fukuii.execution.Withdrawals
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.Trie
import org.fukuii.types.{Block, BlockHeader, Bloom, Receipt, Seal, Transaction}

/** Why a block is not valid against its parent.
  *
  * ==One reason per comparison, for [[HeaderFault]]'s reason==
  *
  * The published corpora name a refused block by the comparison that refused it
  * -- `BlockException.INVALID_STATE_ROOT` and `INVALID_RECEIPTS_ROOT` in the
  * generated tier, `InvalidStateRoot` and `InvalidReceiptsStateRoot` in
  * `ethereum/legacytests`' `bcInvalidHeaderTest` -- so each comparison here
  * refuses under a name of its own, which is what those names are mapped onto.
  * `paradigmxyz/reth` @ `e63ec720ac` gives the same comparisons one variant each
  * with the stated and computed value beside it
  * (`crates/consensus/consensus/src/lib.rs:196-257`).
  *
  * ==Stated, derived and produced==
  *
  * A header STATES each value. A commitment over the body is DERIVED from the
  * body without running anything; one over what the block did is PRODUCED by
  * running it. The member names keep the three apart, because which of the two
  * a figure came from says whether the block was executed when it was refused.
  */
enum BlockFault:

  /** A header rule refused it, whether one every mechanism shares or one the
    * block's own mechanism settles.
    */
  case Header(fault: HeaderFault)

  /** A block naming some other block as its parent.
    *
    * The published corpora call this an unknown parent, because a client that
    * finds a parent by looking it up under the stated hash finds none. A caller
    * handing this validator the parent has not looked anything up, so it is
    * asked here.
    */
  case ParentHashMismatch(stated: Hash, parent: Hash)

  /** A header committing to some other ommers than its body carries. */
  case OmmersHashMismatch(stated: Hash, derived: Hash)

  /** A header committing to some other transactions than its body carries. */
  case TransactionsRootMismatch(stated: Hash, derived: Hash)

  /** A header committing to withdrawals its body carries no list of. */
  case WithdrawalsMissing

  /** A body carrying a withdrawals list its header commits nothing to. */
  case WithdrawalsUnexpected

  /** A header committing to some other withdrawals than its body carries. */
  case WithdrawalsRootMismatch(stated: Hash, derived: Hash)

  /** A header stating some other blob-gas spend than its body's blobs cost. */
  case BlobGasUsedMismatch(stated: BigInt, derived: BigInt)

  /** A transaction the block carries that its rules refuse. */
  case TransactionRefused(rejection: BlockRejection)

  /** A header stating some other gas figure than the block used. */
  case GasUsedMismatch(stated: BigInt, produced: BigInt)

  /** A header stating some other bloom than the block's logs produce. */
  case LogsBloomMismatch(stated: Bloom, produced: Bloom)

  /** A header committing to some other receipts than the block produced. */
  case ReceiptsRootMismatch(stated: Hash, produced: Hash)

  /** A header committing to some other state than the block produced. */
  /** The header's commitment over the block's request list against the list
    * the block actually produced.
    */
  case RequestsHashMismatch(stated: Hash, produced: Hash)

  /** A header stating a requests commitment where the block assembled no list.
    *
    * Distinct from the header rule of the same shape: that one asks whether the
    * FORK admits the field, and this one whether the block's own run produced
    * anything to commit to.
    */
  case RequestsHashUnexpected(stated: Hash)

  /** A block that assembled a request list under a header stating no commitment
    * over it.
    */
  case RequestsHashMissing

  case StateRootMismatch(stated: Hash, produced: Hash)

/** A rule this build does not run, which a block reached.
  *
  * Not a fault: the block may be valid. What this records is that no verdict
  * this build reaches about it would be one the network's rules decided.
  *
  * **Named for not running rather than for not being built**, because a rule
  * can be both built and not run: an engine may carry the arithmetic of a
  * difficulty or a seal and still not apply it to a header, and
  * [[EngineRules]] is that case.
  */
enum RuleNotRun:

  /** An operation the block's rules admit and this build cannot run.
    *
    * [[org.fukuii.execution.BlockOutput.unbuilt]] states why the block's other
    * outputs are then not a chain result, which is why no commitment is compared
    * against them -- and [[org.fukuii.execution.BlockRejection.unbuilt]] why a
    * transaction refused after one is not a refusal either.
    */
  case Operation(gap: Unsupported)

  /** Ommers whose depth, kinship and uniqueness this build does not validate.
    *
    * Reached only once the body's ommers agree with the header's commitment
    * over them, so a block refused for that commitment is still refused.
    */
  case OmmerValidation(ommers: Int)

  /** Header rules the block's own mechanism states and its engine did not run.
    *
    * [[ConsensusEngine.validateHeader]] is what names them, and states why the
    * name is an answer rather than a skip nobody reports.
    */
  case EngineRules(rules: Set[EngineRule])

/** What validating a block against its parent answered.
  *
  * ==Three answers, because this build does not run every rule==
  *
  * A validator that reported [[Valid]] for a block it could not check would be
  * asserting a verdict the network's rules never reached, and one that reported
  * [[Invalid]] would be refusing a block those rules may accept. [[Undecided]]
  * is the third answer and is the only one available there. Every client read
  * for this answers two.
  */
enum BlockVerdict:

  /** Every rule this build runs accepted the block, and nothing it reached is
    * a rule this build does not run.
    */
  case Valid(output: BlockOutput)

  case Invalid(fault: BlockFault)

  case Undecided(rule: RuleNotRun)

/** Whether a block is valid against its parent: its header, its body's
  * commitments, the block run over its parent's state, and every commitment its
  * header states compared with what that run produced.
  *
  * ==The order, and where it comes from==
  *
  * **Before anything runs**: the parent hash, then the rules every mechanism
  * shares ([[HeaderValidator]]), then the block's own mechanism's
  * ([[ConsensusEngine.validateHeader]]), then the commitments over the body --
  * ommers, transactions, withdrawals and blob gas, in that order -- and then
  * ommers this build does not validate. **After it runs**: gas used, the logs
  * bloom, the receipts root and the state root, and last a header rule the
  * engine did not run.
  *
  * `ethereum/execution-specs` @ `0cc100eb1` puts the header rules and the
  * ommers first as well (`src/ethereum/forks/cancun/fork.py:195-197`,
  * `forks/london/fork.py:180-181`) and compares every other commitment after
  * running the body (`forks/cancun/fork.py:227-242`). The body commitments are
  * moved ahead of the run because three clients read for this compare them
  * there: `ethereum/go-ethereum` @ `02872e9ef` in `ValidateBody`
  * (`core/block_validator.go:67-112`, in this order), `NethermindEth/nethermind`
  * @ `3a98e0818` in `ValidateSuggestedBlock` (`Validators/BlockValidator.cs:75-82`)
  * and `paradigmxyz/reth` @ `e63ec720ac` in its pre-execution checks
  * (`crates/consensus/common/src/validation.rs:161-233`). None of the four
  * returns a reason for more than one comparison, and between two of their
  * refusals the order decides only which reason is returned.
  *
  * **Against an undecided answer the order decides the verdict itself**, which
  * the clients' two answers never face. A comparison placed after something
  * this build does not run is never reached, so a block breaking it would be
  * left undecided rather than refused. So each rule this build does not run is
  * decided as late as what reads it allows. A header rule the engine did not
  * run is decided after every comparison, because execution reads no such
  * rule's outcome. Ommers are decided before the run, because the settlement
  * reads them, and an operation this build cannot run where the run reaches it,
  * because what is compared after it would be a run that is no chain result.
  * Between two undecided answers, as between two refusals, the order decides
  * only which rule is named.
  *
  * The comparisons after the run follow go-ethereum's `ValidateState`
  * (`core/block_validator.go:153-206`) and nethermind's `ValidateProcessedBlock`
  * (`:186-216`), which run gas used, bloom, receipts and state root in that
  * order, rather than the specification's gas, state, receipts and bloom. All
  * four clients read for it compare the state root after the receipts root,
  * and it is the one comparison that costs a whole state commitment to compute.
  *
  * **Two positions are preconditions rather than preferences.** The parent hash
  * is first because a header that names another parent has no meaningful answer
  * to a rule stated against this one, and a client finding its parent by that
  * hash -- `besu-eth/besu` @ `b330564a94`
  * (`MainnetBlockValidator.java:155-164`), go-ethereum's engines -- never
  * reaches one. The mechanism's rules come after the shared ones because
  * [[ConsensusEngine.validateHeader]] states that an override may rely on them.
  *
  * ==Nothing is priced from a figure a header rule has not accepted==
  *
  * The blob charge is derived from the header's excess, and the derivation
  * takes time linear in that excess (`org.fukuii.evm.BlobGasPrice.at`). So the
  * block's context and its blob accounting are built only after
  * [[HeaderValidator]] has checked the excess against the parent, which is the
  * ordering `org.fukuii.consensus.pos.PayloadTranslation.contextOf` records as
  * owed by whatever runs a block.
  *
  * ==`world` is written through==
  *
  * [[org.fukuii.execution.BlockProcessor.process]] commits as it goes, so a
  * block refused or left undecided after running has already changed `world`.
  * Every refusal before the run leaves it untouched.
  */
object BlockValidator:

  /** Validates `block` against `parent`.
    *
    * @param rules
    *   what the schedule resolved at this block's own activation point.
    *   `parent.rules` is the same answer for the parent. **The engine's
    *   transformation is applied here, to both**, so a caller passes the
    *   schedule's answer and never one it has already transformed.
    * @param world
    *   the state at the parent block, which this advances.
    * @param destroyAccount
    *   removes an account and the storage under it.
    *   [[org.fukuii.execution.TransactionProcessor.settle]] states its contract.
    * @param stateRoot
    *   the root of `world` as it stands. Asked after each transaction where the
    *   block's receipts carry an intermediate root, and once more for the
    *   header's own.
    * @param blockHashAt
    *   the hash of an earlier block, which `BLOCKHASH` reads.
    * @param requestRules
    *   this network's own request parameters, chiefly where its beacon deposit
    *   contract was deployed. **Per NETWORK and not per fork**, which is why it
    *   arrives here beside `chainId` rather than on the rule set: the address is
    *   a deployment, and every other address the seam names is fixed by its
    *   proposal. `org.fukuii.chainspec.networks.ethereum.Mainnet` states this
    *   network's and carries the sourcing.
    *
    *   **Absent is a configuration error once the fork commits, not an empty
    *   list.** A fork whose headers state a requests commitment, on a network
    *   naming no deposit contract, cannot derive that commitment at all --
    *   answering an empty list there would state a header value the network
    *   disagrees with, so this refuses to run instead.
    */
  def validate(
      block: Block,
      rules: UpgradeRules,
      parent: Resolved,
      engine: ConsensusEngine,
      world: WorldState,
      destroyAccount: Address => Unit,
      stateRoot: () => Hash,
      blockHashAt: BigInt => Hash,
      chainId: UInt64,
      requestRules: Option[RequestRules] = None
  ): BlockVerdict =
    val running = Resolved(block.header, engine.rulesFrom(rules))
    val parentRunning = parent.copy(rules = engine.rulesFrom(parent.rules))
    val validated =
      for
        _ <- agrees(block.header.parentHash, parent.header.hash, BlockFault.ParentHashMismatch.apply)
        _ <- HeaderValidator.validate(running, parentRunning).left.map(refusedByHeader)
        notRun <- engine.validateHeader(running, parentRunning).left.map(refusedByHeader)
        _ <- agrees(block.header.ommersHash, ommersHashOf(block), BlockFault.OmmersHashMismatch.apply)
        _ <- agrees(block.header.transactionsRoot, transactionsRootOf(block), BlockFault.TransactionsRootMismatch.apply)
        _ <- withdrawalsAgree(block)
        _ <- blobGasAgrees(block, running.rules)
        _ <- ommersValidated(block)
        output <- run(
          block,
          running.rules,
          engine,
          world,
          destroyAccount,
          stateRoot,
          blockHashAt,
          chainId,
          requestsOf(running.rules, requestRules)
        )
        _ <- agrees(block.header.gasUsed.toBigInt, output.gasUsed, BlockFault.GasUsedMismatch.apply)
        _ <- agrees(block.header.logsBloom, Bloom.fromLogs(output.logs), BlockFault.LogsBloomMismatch.apply)
        _ <- agrees(block.header.receiptsRoot, receiptsRootOf(output), BlockFault.ReceiptsRootMismatch.apply)
        _ <- requestsAgree(block, output)
        _ <- agrees(block.header.stateRoot, stateRoot(), BlockFault.StateRootMismatch.apply)
        _ <- engineRulesRan(notRun)
      yield output
    validated match
      case Left(stopped) => stopped
      case Right(output) => BlockVerdict.Valid(output)

  private def agrees[A](stated: A, derived: A, fault: (A, A) => BlockFault): Either[BlockVerdict, Unit] =
    if stated == derived then Right(()) else Left(BlockVerdict.Invalid(fault(stated, derived)))

  private def refusedByHeader(fault: HeaderFault): BlockVerdict = BlockVerdict.Invalid(BlockFault.Header(fault))

  /** `keccak256(rlp(ommers))`, which is the specification's own statement of
    * the commitment (`forks/london/fork.py:636`).
    */
  private def ommersHashOf(block: Block): Hash = Keccak256.hash(RlpCodec.encodeTo(block.body.ommers))

  private def transactionsRootOf(block: Block): Hash =
    Trie.rootOfIndexed(block.body.transactions.map(t => Bytes.fromIArray(Transaction.canonicalBytes(t))))

  private def receiptsRootOf(output: BlockOutput): Hash =
    Trie.rootOfIndexed(output.receipts.map(r => Bytes.fromIArray(Receipt.canonicalBytes(r))))

  /** The body's withdrawals against the header's commitment over them.
    *
    * [[HeaderValidator]] has already settled whether the header carries a
    * commitment at this fork, so what is left is whether the body agrees with
    * the header -- in presence first, and then in value. The two presence arms
    * are refusals of their own because the clients read for this report them
    * apart from a wrong root: go-ethereum @ `02872e9ef`
    * (`core/block_validator.go:75-86`), nethermind @ `3a98e0818`
    * (`MissingWithdrawals` and `WithdrawalsNotEnabled` beside
    * `InvalidWithdrawalsRoot`) and reth @ `e63ec720ac`
    * (`crates/consensus/common/src/validation.rs:55-68,119-131`).
    */
  private def withdrawalsAgree(block: Block): Either[BlockVerdict, Unit] =
    (block.header.withdrawalsRoot, block.body.withdrawals) match
      case (None, None)                  => Right(())
      case (Some(_), None)               => Left(BlockVerdict.Invalid(BlockFault.WithdrawalsMissing))
      case (None, Some(_))               => Left(BlockVerdict.Invalid(BlockFault.WithdrawalsUnexpected))
      case (Some(stated), Some(carried)) =>
        agrees(stated, Withdrawals.root(carried), BlockFault.WithdrawalsRootMismatch.apply)

  /** The header's blob-gas spend against what the body's blobs cost.
    *
    * Derived from the body rather than from the run, because it reads no
    * result: `org.fukuii.evm.BlobGas.spentBy` is a blob count times a constant,
    * and admission charges exactly that. go-ethereum
    * (`core/block_validator.go:88-112`), nethermind (`BlockValidator.cs:337-394`)
    * and reth (`validation.rs:76-89`) all compare it before running the block.
    *
    * Compared only where the fork accounts for blob gas, which is where the
    * header's figure exists at all once [[HeaderValidator]] has accepted it.
    */
  private def blobGasAgrees(block: Block, rules: UpgradeRules): Either[BlockVerdict, Unit] =
    rules.header.blobSchedule.flatMap(_ => block.header.blobGasUsed) match
      case None         => Right(())
      case Some(stated) =>
        agrees(stated.toBigInt, block.body.transactions.map(BlobGas.spentBy).sum, BlockFault.BlobGasUsedMismatch.apply)

  /** Undecided where the block's own engine did not run a header rule its
    * mechanism states, once every rule this build runs has accepted the block.
    *
    * Last, after the run and every comparison over it, so a block any of those
    * refuses is refused whatever the rule not run would have said. That order is
    * sound only because execution reads no rule an engine may leave unrun, which
    * [[ConsensusEngine.validateHeader]] states as an engine's obligation. It
    * leaves `world` at the state the block produced, which is the state its
    * header commits to.
    */
  private def engineRulesRan(notRun: Set[EngineRule]): Either[BlockVerdict, Unit] =
    if notRun.isEmpty then Right(()) else Left(BlockVerdict.Undecided(RuleNotRun.EngineRules(notRun)))

  /** Undecided where the body carries ommers, before anything runs.
    *
    * Running would reach the engine's settlement with ommers nothing has
    * validated, and `org.fukuii.consensus.pow.EthashEngine.settlement` states
    * that it relies on them having been.
    */
  private def ommersValidated(block: Block): Either[BlockVerdict, Unit] =
    if block.body.ommers.isEmpty then Right(())
    else Left(BlockVerdict.Undecided(RuleNotRun.OmmerValidation(block.body.ommers.length)))

  /** Runs the block, with the engine's settlement and then its withdrawals as the
    * change that closes it.
    *
    * ==After the transactions, which is the one order a proposal states==
    *
    * Both are the change [[org.fukuii.execution.BlockProcessor.process]] applies
    * once the last transaction has settled, which is EIP-4895's order: *"The
    * `withdrawals` in an execution payload are processed **after** any
    * user-level transactions are applied"* (`ethereum/EIPs` @ `dbfa6bee8`,
    * `EIPS/eip-4895.md:129`). What a withdrawal does is the engine's --
    * [[ConsensusEngine.processWithdrawals]] -- and the commitment over the list
    * is compared before anything runs.
    *
    * ==Settlement before withdrawals is UNSETTLED==
    *
    * No source orders withdrawals against a block reward. The proposal orders
    * them against the transactions alone, and the executable specification never
    * schedules both: `ethereum/execution-specs` @ `20f7f6271a` ends a block's
    * body in `pay_rewards` below the merge (`forks/gray_glacier/fork.py:596`) and
    * in `process_withdrawals` from the proposal's fork
    * (`forks/shanghai/fork.py:500`). So nothing establishes the order
    * immaterial, and it can be observed: a reward that brings its beneficiary
    * into being holding nothing, and a zero-amount withdrawal to the same
    * address, reach different states depending on which runs last. No network
    * this build serves schedules both, so on each of those it is unobservable.
    *
    * **This build follows `NethermindEth/nethermind` and `erigontech/erigon`**,
    * which apply the mechanism's rewards first -- two independent
    * implementations against one, and the two read that serve Gnosis:
    * nethermind @ `3a98e0818`
    * (`Nethermind.Consensus/Processing/BlockProcessor.cs:196-197`) and erigon @
    * `ab8e9fde7` (`execution/protocol/rules/merge/merge.go:165-197`).
    * `besu-eth/besu` @ `b330564a94` processes withdrawals first
    * (`AbstractBlockProcessor.java:420-436`, then `:493`) and carries no Gnosis
    * configuration.
    *
    * **Reversing trigger:** a network scheduling both a block reward and
    * withdrawals, where the order is then settled from that network's own
    * specification rather than from this choice.
    */
  private def run(
      block: Block,
      rules: UpgradeRules,
      engine: ConsensusEngine,
      world: WorldState,
      destroyAccount: Address => Unit,
      stateRoot: () => Hash,
      blockHashAt: BigInt => Hash,
      chainId: UInt64,
      requestRules: Option[RequestRules]
  ): Either[BlockVerdict, BlockOutput] =
    val header = block.header
    val settlement = engine.settlement(rules.consensus, header.beneficiary, header.number.toBigInt, block.body.ommers)
    val withdrawn = block.body.withdrawals.map(carried => engine.processWithdrawals(carried, destroyAccount))
    val closing: WorldState => Unit = closed =>
      settlement(closed)
      withdrawn.foreach(change => change(closed))
    BlockProcessor.process(
      transactions = block.body.transactions,
      world = world,
      destroyAccount = destroyAccount,
      stateRootAfterTransaction = stateRoot,
      block = contextOf(header, rules),
      blockHashAt = blockHashAt,
      chainId = chainId,
      evm = rules.evm,
      execution = rules.execution,
      admission = rules.admission,
      irregularStateChange = None,
      consensusStateChange = closing,
      blobGas = blobGasOf(header, rules),
      systemCalls = systemCallsOf(header, rules),
      requestRules = requestRules
    ) match
      case Left(rejection) =>
        rejection match
          // An unbuilt operation is not a chain result, so it is reported as a
          // rule this build did not run rather than as a block anyone refused.
          case BlockRejection.RefusedTransaction(_, _, Some(gap)) =>
            Left(BlockVerdict.Undecided(RuleNotRun.Operation(gap)))
          // A system call that failed refused no transaction, so it cannot
          // carry an unbuilt operation and cannot be undecided for one: the
          // call either ran and failed, or its target held no code.
          case other => Left(BlockVerdict.Invalid(BlockFault.TransactionRefused(other)))
      case Right(output) =>
        output.unbuilt match
          case Some(gap) => Left(BlockVerdict.Undecided(RuleNotRun.Operation(gap)))
          case None      => Right(output)

  /** What the machine reads about the block, taken off a header its rules have
    * accepted.
    *
    * Presence is read from the rules and the value from the header. The two
    * agree once [[HeaderValidator]] has run, and reading presence off a field
    * instead is what a fixture generator's one environment shape defeats --
    * `org.fukuii.chainspec.certification.StateFixtureRunner` records the case.
    */
  private def contextOf(header: BlockHeader, rules: UpgradeRules): BlockContext =
    BlockContext(
      coinbase = header.beneficiary,
      number = header.number.toBigInt,
      timestamp = header.timestamp.toBigInt,
      difficulty = header.difficulty.toBigInt,
      gasLimit = header.gasLimit.toBigInt,
      baseFee = rules.header.feeMarket.flatMap(_ => header.baseFeePerGas.map(_.toBigInt)),
      prevRandao = randomnessOf(header, rules),
      excessBlobGas = rules.header.blobSchedule.flatMap(_ => header.excessBlobGas)
    )

  /** The seal's first slot, where the rules read it as randomness.
    *
    * [[org.fukuii.evm.BlockContext.prevRandao]] states why absence means the
    * slot is not randomness rather than that there is no slot.
    */
  private def randomnessOf(header: BlockHeader, rules: UpgradeRules): Option[Hash] =
    rules.evm.blockRandomness match
      case BlockRandomness.Unavailable => None
      case BlockRandomness.Eip4399     =>
        header.seal match
          case Seal.MixHashAndNonce(mixHash, _) => Some(mixHash)
          case Seal.AuthorityRound(_, _)        => None

  /** What the block charges for blob gas and the most it may spend, where its
    * rules account for blob gas at all.
    *
    * Presence is the rules' pair, the schedule and the update fraction, and never
    * a header field's -- the reading `StateFixtureRunner.blobGasTermsOf` takes
    * for the same reason [[contextOf]] states. The excess the charge is derived
    * from is the header's own.
    */
  private def blobGasOf(header: BlockHeader, rules: UpgradeRules): Option[BlobGasAccounting] =
    for
      schedule <- rules.header.blobSchedule
      fraction <- rules.evm.blobBaseFeeUpdateFraction
    yield
      val excess = header.excessBlobGas.getOrElse(
        throw new IllegalStateException(
          "a header accepted under a blob schedule stated no excess, at number " + header.number.toString
        )
      )
      BlobGasAccounting(BlobGasPrice.at(excess.toBigInt, fraction), schedule.maxBlobs * BlobGas.PerBlob)

  /** Whether this block assembles a request list, and with what.
    *
    * ==The fork decides WHETHER; the network decides WITH WHAT==
    *
    * Deriving it from the header facet rather than from the address's presence
    * is what keeps the two apart. A network stating an address at a fork below
    * the container must still assemble nothing, and a fork above it on a network
    * stating none is a configuration this build refuses to run rather than
    * answering an empty list for -- which would be a header value the network
    * disagrees with, and so a split.
    *
    * `besu-eth/besu` @ `b330564a9` has the same two branches and the same
    * refusal: a no-op coordinator where no list is owed, and `orElseThrow` on a
    * Prague definition whose genesis names no system contract address.
    */
  private def requestsOf(rules: UpgradeRules, stated: Option[RequestRules]): Option[RequestRules] =
    if !rules.header.carriesRequestsHash then None
    else
      Some(
        stated.getOrElse(
          throw new IllegalArgumentException(
            "a fork committing to a request list needs the network's deposit contract, and none was stated"
          )
        )
      )

  /** The header's requests commitment against the list the block produced.
    *
    * **Absent and empty are different header values**, and both are compared
    * here: a fork below the container produces no list and must state no hash,
    * while a fork above it produces one -- possibly empty -- and must state the
    * hash over exactly what it produced. `HeaderValidator` has already decided
    * the presence question in both directions, so what is left here is the value.
    */
  private def requestsAgree(block: Block, output: BlockOutput): Either[BlockVerdict, Unit] =
    (block.header.requestsHash, output.requests) match
      case (None, None)                   => Right(())
      case (Some(stated), Some(produced)) =>
        agrees(stated, ExecutionRequests.hashOf(produced), BlockFault.RequestsHashMismatch.apply)
      // Neither of these reaches a conforming caller: the header's presence is
      // the fork's, the list's presence is derived from the same flag, and
      // `HeaderValidator` refuses a header disagreeing with it. They are stated
      // rather than left to a match error, because the value they would carry is
      // a split and a crash names it better than a wrong hash would.
      case (Some(stated), None) => Left(BlockVerdict.Invalid(BlockFault.RequestsHashUnexpected(stated)))
      case (None, Some(_))      => Left(BlockVerdict.Invalid(BlockFault.RequestsHashMissing))

  /** The calls the block makes on its own account before its transactions.
    *
    * EIP-4788's, where the rules require the header to state a beacon root, over
    * that root. `org.fukuii.chainspec.proposals.eip.Eip4788` records why the
    * header facet is what selects it, and the block is never a genesis here,
    * where the proposal makes no call: [[HeaderValidator]] has accepted it as
    * its parent's successor.
    *
    * Then EIP-2935's, over the parent's hash.
    *
    * ==The two are gated on different facets, and that asymmetry is the rule
    * rather than an inconsistency==
    *
    * The first is conditional on the header FIELD being present, not merely on
    * the fork -- a fork may require the field while an individual header states
    * none, and the two are separate questions. The second has no header field to
    * be conditional on, so it is gated on the fork alone, which is what every
    * client that implements it does.
    *
    * ==The order is the specification's and is agreed by the field==
    *
    * Beacon root, then parent hash. `ethereum/execution-specs` @ `0cc100eb1`
    * `forks/prague/fork.py:743-754` issues them in that order at the head of
    * `apply_body`, and `ethereum/go-ethereum` @ `02872e9ef`
    * `core/state_processor.go:165-171` the same. **Nothing read observes the
    * difference** -- neither contract reads state the other writes -- so this
    * follows the sources rather than resting on a measurement that could tell
    * them apart.
    */
  private def systemCallsOf(header: BlockHeader, rules: UpgradeRules): Seq[SystemCall] =
    val beaconRoot =
      if rules.header.carriesParentBeaconBlockRoot then
        header.parentBeaconBlockRoot.toSeq.map(root =>
          SystemCall(SystemCall.Target.BeaconRoots, Bytes.fromIArray(root.toBytes))
        )
      else Seq.empty
    val parentBlockHash =
      if rules.execution.recordsParentBlockHash then
        Seq(SystemCall(SystemCall.Target.HistoryStorage, Bytes.fromIArray(header.parentHash.toBytes)))
      else Seq.empty
    beaconRoot ++ parentBlockHash
