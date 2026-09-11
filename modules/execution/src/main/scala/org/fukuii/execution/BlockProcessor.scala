package org.fukuii.execution

import org.fukuii.bytes.{Address, Hash, UInt64}
import org.fukuii.evm.{BlockContext, EvmRules, JournaledWorldState, Unsupported, WorldState}
import org.fukuii.types.{Log, PostStateOrStatus, Receipt, Transaction, TransactionType, Withdrawal}

/** What processing one block produced.
  *
  * ==Named for the specification's record rather than for a result==
  *
  * `ethereum/execution-specs` @ `ccaaaba58` calls it `BlockOutput` and carries
  * the same three facts on it -- the gas the block used, a receipt per
  * transaction, and the logs those receipts hold. `besu-eth/besu` @
  * `c2addd9424` says `BlockProcessingResult` and `ethereum/go-ethereum` @
  * `6bb0588ad` says `ProcessResult`, so two of the three name a result and the
  * specification names an output; the specification is read first here.
  *
  * **It is not [[org.fukuii.evm.Outcome]] under another spelling.** That
  * enumeration is how one invocation ended and is answered per invocation; this
  * is what a whole block left behind. The two words are close enough to confuse
  * and the concepts are a layer apart, which is why the distinction is stated
  * rather than left to the reader.
  *
  * @param receipts
  *   one per transaction, in the order the block carries them. A receipts root
  *   is taken over exactly this sequence, so the order is part of the value
  *   rather than a convenience.
  * @param gasUsed
  *   what every transaction in the block was charged, after each transaction's
  *   own refund. It is carried rather than read off the last receipt because a
  *   block carrying no transactions has no last receipt and still has an
  *   answer -- zero -- which a header commits to.
  * @param unbuilt
  *   the first operation this build cannot run, where a transaction reached
  *   one.
  *
  *   **An output carrying this is not a chain result**, for the reason
  *   [[Settlement.unbuilt]] states of one transaction: what the other fields
  *   hold is what the block would have produced had the operation halted, which
  *   is the only shape in which the block ends somewhere a caller can compare.
  *   One is enough to say so, so the first is kept rather than all of them.
  * @param withdrawalsRoot
  *   the commitment over the withdrawals the block carried, absent where the
  *   block carried no withdrawals field at all.
  *
  *   **The absence and an empty list are different answers**, which is why this
  *   is an option over a hash rather than a hash that is sometimes the empty
  *   trie's. A block below the withdrawals proposal has no such field and its
  *   header states no root; a block at or above it carrying an empty list
  *   states the empty trie's root. Collapsing the two would make a header that
  *   omitted the field indistinguishable from one that stated the empty root,
  *   and `org.fukuii.chainspec.HeaderRules` resolves which a fork requires.
  *
  *   It sits beside [[gasUsed]] rather than being derived by a caller for the
  *   same reason that figure does: `ethereum/execution-specs` @ `20f7f6271a`
  *   (2026-08-26) carries `withdrawals_trie` on its own `BlockOutput` and takes
  *   `root(...)` of it in `state_transition` (`forks/shanghai/fork.py:199`),
  *   beside the transactions root, the receipts root and the bloom. This
  *   project has only the one of those four so far.
  * @param blobGasUsed
  *   what every transaction in the block spent on blobs, absent where the block
  *   accounts for no blob gas at all.
  *
  *   **The absence and a zero are different answers**, exactly as they are for
  *   [[withdrawalsRoot]]: a block below the blob accounting has no such header
  *   field, and a block at or above it carrying no blob transaction states
  *   zero. Collapsing the two would make a header that omitted the field
  *   indistinguishable from one stating zero.
  *
  *   `ethereum/execution-specs` @ `0cc100eb1` (2026-09-11) carries
  *   `blob_gas_used` on its own `BlockOutput` and accumulates it one
  *   transaction at a time (`src/ethereum/forks/cancun/fork.py:778`), then
  *   compares it against the header at `:241`.
  *
  *   ==It needs no EXECUTION, which the accumulation here obscures==
  *
  *   Every addend is a blob count times a fixed figure, read off the decoded
  *   transaction and nothing else -- `org.fukuii.evm.BlobGas.spentBy` is that
  *   derivation, and a caller holding a block's transactions can sum it without
  *   running any of them. So a header's stated spend can be checked against its
  *   own body at a layer that executes nothing, and what this member adds is
  *   that a block which HAS been executed carries the figure it was executed
  *   under rather than one recomputed beside it.
  */
final case class BlockOutput(
    receipts: Vector[Receipt],
    gasUsed: BigInt,
    unbuilt: Option[Unsupported],
    withdrawalsRoot: Option[Hash] = None,
    blobGasUsed: Option[BigInt] = None
):

  /** Every log the block emitted, oldest first.
    *
    * Derived rather than stored: a receipt already holds its own logs in order,
    * so a second copy would be one sequence with two definitions and a block
    * bloom could then be taken over the copy that was wrong. What consumes it
    * is the block's own bloom, which
    * [[org.fukuii.types.Bloom.fromLogs]] takes over the whole sequence.
    */
  def logs: Vector[Log] = receipts.flatMap(_.logs)

/** Why a block is not one this network accepts.
  *
  * A record rather than an enumeration, because at this layer there is one
  * reason: the block carries a transaction these rules refuse. Everything else
  * that makes a block invalid -- its header, its seal, its ommers, its
  * commitments -- is decided by layers this project has not built, and each
  * will have its own reasons rather than more cases here.
  *
  * `ethereum/go-ethereum` @ `6bb0588ad` reports exactly this pair, as
  * `fmt.Errorf("could not apply tx %d [%v]: %w", i, ...)`, and `besu-eth/besu`
  * @ `c2addd9424` the same in `AbstractBlockProcessor`'s
  * `"Block processing error: transaction invalid {0}. Block {1} Transaction
  * {2}"`.
  *
  * @param index
  *   where the offending transaction sits in the block. Carried because the
  *   refusal alone does not identify it and a block can hold many transactions
  *   that could each break the same rule.
  */
final case class BlockRejection(index: Int, reason: Refusal)

/** What a block accounts for in blob gas: what it charges per unit, and the
  * most it may spend.
  *
  * ==The BLOCK's pair, where [[BlobGasTerms]] is one transaction's==
  *
  * The two records hold the same charge and differ in the other member, which
  * is the whole reason they are not one type: a block states a MAXIMUM, and
  * what a transaction is measured against is the REMAINDER after the
  * transactions before it. `ethereum/execution-specs` @ `0cc100eb1`
  * (2026-09-11) `src/ethereum/forks/cancun/fork.py:432` derives the second from
  * the first on the transaction's own line --
  * `MAX_BLOB_GAS_PER_BLOCK - block_output.blob_gas_used` -- exactly as it
  * derives the gas remainder on the line above. Holding one type for both
  * would let a caller hand a transaction the block's maximum, which admits a
  * second blob transaction the network refuses.
  *
  * ==Absent below the fork that accounts for blob gas at all==
  *
  * Not zero: a maximum of zero is a fork that admits blob transactions and
  * lets none through, which no schedule states, and a charge of zero is below
  * the floor every source puts under it. `org.fukuii.execution.BlockOutput`
  * carries the same absence forward.
  *
  * @param charge
  *   what this block charges per unit of blob gas, derived by the caller from
  *   the excess its header states and the fraction its fork resolves.
  *   `org.fukuii.evm.BlobGasPrice.at` is that derivation.
  * @param maximum
  *   the most blob gas a block at these rules may spend.
  *   `org.fukuii.chainspec.BlobSchedule` holds the blob count a fork allows and
  *   `org.fukuii.evm.BlobGas.PerBlob` the figure it is multiplied by; both sit
  *   in layers this one cannot see, which is why the product arrives rather
  *   than being computed here.
  */
final case class BlobGasAccounting(charge: BigInt, maximum: BigInt)

/** What a block does around the transactions it carries.
  *
  * ==The whole of it is an ordered loop and the state changes nobody signed==
  *
  * `ethereum/execution-specs` @ `ccaaaba58` is the plainest statement of the
  * loop: `frontier/fork.py`'s `apply_body` is a `for` over the transactions
  * followed by `pay_rewards`, and nothing else. What follows the loop grows
  * with the forks -- the count is deliberately not stated here, being the thing
  * that goes stale on the commit that adds the next one.
  * `ethereum/go-ethereum` @ `6bb0588ad`, `ethereum/go-ethereum-pow` @
  * `v1.10.26` and `ethereumclassic/core-geth` @ `4185df450` reach the same
  * loop and close it the same way, calling `engine.Finalize` after the last
  * transaction.
  *
  * ==Where the irregular state change is applied is the one place they differ==
  *
  * The three Go trees put it at the head of block processing, gated on the
  * block's own number: `if config.DAOForkBlock.Cmp(block.Number()) == 0 then
  * ApplyDAOHardFork(statedb)`. The specification does not -- it applies the
  * same mutation from a chain-level hook, `dao_fork/fork.py`'s `apply_fork`
  * calling `apply_dao(old.state)` at the transition, so its `apply_body` never
  * sees one. The states reached agree; the seam sits a layer apart.
  *
  * **The clients' position is the one taken here**, because the change has to
  * land between the parent's state and this block's first transaction, and
  * that is a point only block processing holds. Which blocks have one stays
  * the caller's answer, so nothing here reads a schedule.
  *
  * ==A refused transaction invalidates the BLOCK, which inverts the mempool
  * reading==
  *
  * [[TransactionAdmission]] answers whether a transaction may run. Asked of a
  * transaction offered to a node, a refusal drops that transaction. Asked of a
  * transaction a block already contains, a refusal condemns the block: the
  * producer put in something the rules do not admit, so no state after it is a
  * state this network reaches. Both go-ethereum lines and besu stop at the
  * first such transaction rather than skipping it, and this returns
  * [[BlockRejection]] for the same reason.
  *
  * ==What is deliberately absent==
  *
  * A block's reward is not computed here and no figure for one appears in this
  * module. Where it comes from differs by consensus mechanism rather than by
  * fork, and the two clients read here disagree about it more than about
  * anything else in this record: `ethereumclassic/core-geth` @ `4185df450` has
  * `Ethash.Finalize` accumulate a reward against world state while its
  * `Clique.Finalize` is an empty body commented *"No block rewards in PoA, so
  * the state remains as is"*, and `NethermindEth/nethermind` @ `c35ce1b1ab`
  * ships `NoBlockRewards`, whose `CalculateRewards` returns an empty array,
  * beside `ZeroWeiRewards`, which returns one reward of zero to the
  * beneficiary. **Those last two are different state roots** -- one touches no
  * account and the other brings the beneficiary into being holding nothing --
  * which is why the seam below is a change to state and not a number to add.
  *
  * Header validation is absent for a different reason: nothing here reads the
  * header the block arrived with, so nothing here can find the gas it used, the
  * roots it commits to or the extra data it carries to disagree with what this
  * produced. A caller holding a header compares them itself.
  */
object BlockProcessor:

  /** Runs `transactions` in order against `world`, under these rules.
    *
    * ==What happens, in an order that is consensus-critical==
    *
    * The irregular state change first, where one is scheduled, so that a
    * transaction in this very block sees the state it left; then the block's
    * own system calls, for the same reason and with the same visibility; then
    * the transactions, each seeing what the one before it wrote; then the
    * withdrawals the body carries; then the consensus mechanism's own change,
    * after the last transaction and before anything reads the block's final
    * root. **No count is stated, deliberately** -- the list has grown once and
    * the number is what would go stale on the commit that grows it again.
    *
    * That order is the specification's rather than a count of clients:
    * `ethereum/execution-specs` @ `ccaaaba58`'s `apply_body` runs its
    * `for i, tx in enumerate(transactions)` loop to completion and calls
    * `pay_rewards(block_env, ommers)` on the next statement, and at
    * `20f7f6271a` `forks/shanghai/fork.py:500` the statement in that position is
    * `process_withdrawals`.
    *
    * ==Withdrawals against the mechanism's change is an order nothing
    * specifies, and it is stated rather than left implicit==
    *
    * EIP-4895 orders them against the transactions and against nothing else:
    * *"The `withdrawals` in an execution payload are processed **after** any
    * user-level transactions are applied"* (`ethereum/EIPs` @ `dbfa6bee8`
    * (2026-08-26)). No source orders them against a block reward, because the
    * two occupy the same slot in successive forks rather than appearing
    * together -- the specification's `apply_body` ends in `pay_rewards` below
    * the merge and in `process_withdrawals` above it, and
    * `ethereum/go-ethereum` @ `e9e35a42f8` reaches one or the other from
    * `Beacon.Finalize` on a branch. **So no rule set this project can build has
    * both**, and the order below is unobservable on every network.
    *
    * It is stated because it would not be unobservable on a network that did
    * have both: a reward that brings its beneficiary into being holding nothing
    * and a zero-amount withdrawal to the same address reach opposite states
    * depending on which ran last, since the second destroys what the first
    * created. The mechanism's change is kept last so that the contract stated
    * for it below -- *after the last transaction and before anything reads the
    * block's final root* -- is unchanged, and the withdrawals occupy the
    * position the specification gives them relative to the loop.
    *
    * ==`world` is written through, so a rejection leaves it part-way==
    *
    * Each transaction is settled against its own journal over `world`, and
    * settling commits. A block refused at its third transaction has therefore
    * already committed its first two, exactly as `ethereum/go-ethereum` @
    * `6bb0588ad` leaves its `statedb` written when `Process` returns an error
    * and `besu-eth/besu` @ `c2addd9424` resets its updater on the way out.
    * **A caller that may keep the result of a rejected block must take its own
    * copy first**; nothing here can undo a commit.
    *
    * @param transactions
    *   what the block carries, in the order it carries them. Signed, because a
    *   block carries signed transactions and the account each runs as is
    *   recovered here rather than supplied -- which is what makes a signature
    *   naming another chain a refusal rather than a different sender.
    * @param world
    *   the state at the parent block, which this advances. Not a journal: the
    *   changes made here are ones nothing undoes, and each transaction takes
    *   its own journal over this.
    * @param destroyAccount
    *   removes an account and the storage under it, forwarded to each
    *   transaction's settlement. [[TransactionProcessor.settle]] states its
    *   contract, which this does not restate.
    * @param stateRootAfterTransaction
    *   the root of `world` as it stands. Asked once per transaction, and only
    *   where [[ExecutionRules.receiptCarriesStatus]] is unset -- a fork whose
    *   receipts carry a status never computes one, and asking anyway would be a
    *   root taken per transaction that nothing reads.
    *
    *   It arrives as a parameter for the reason `destroyAccount` does: it is a
    *   thing this layer calls rather than a value it reads, and what satisfies
    *   it varies underneath. [[org.fukuii.evm.WorldState]] deliberately does not
    *   carry it, being the whole of what the *machine* may ask, and no operation
    *   reads a state root.
    * @param chainId
    *   this network's registered identifier, against which a signature naming a
    *   chain is compared.
    * @param irregularStateChange
    *   a scheduled change to state that no transaction made, applied before any
    *   of them run. Optional because most blocks have none, and a caller states
    *   its absence rather than writing an empty function -- the schedule is what
    *   knows, and it sits in a module above this one.
    *
    *   `ethereum/EIPs` @ `9c915ee494`, EIP-779, is the case this exists for:
    *   *"the DAO Fork did not change the protocol; all EVM opcodes, transaction
    *   format, block structure, and so on remained the same. Rather, the DAO
    *   Fork was an 'irregular state change'"*. It is not the only one --
    *   `gnosischain/specs` reaches the same shape for its Balancer upgrade, and
    *   `ethereum-optimism/op-geth` @ `86be6726f8` files its Canyon transition in
    *   the same package as go-ethereum's `dao.go`.
    * @param consensusStateChange
    *   what the consensus mechanism writes into state once the transactions are
    *   done. Applied on every block rather than optionally, because a mechanism
    *   with nothing to write supplies a change that writes nothing and is still
    *   called -- which is what keeps *"no reward"* and *"a reward of zero"*
    *   distinguishable.
    *
    *   **This is the slot a block's reward eventually occupies, and it is
    *   deliberately left empty by this layer.** It is a change to state rather
    *   than a figure returned, so it composes with a mechanism that computes
    *   from an unbounded schedule, one that reads a contract at an earlier
    *   block, and one that does nothing at all.
    * @param withdrawals
    *   the operations the block's body carries, absent where the body carries
    *   no such field. [[Withdrawals]] states what crediting one does; this
    *   states where it happens.
    *
    *   **It arrives as a value rather than as a change to apply**, unlike the
    *   two above, because this layer must also state the commitment over it and
    *   a `WorldState => Unit` yields nothing to commit to.
    *
    *   **Whether a block at this height may carry the field is NOT decided
    *   here**, and this layer is not where it could be: the rule is
    *   `org.fukuii.chainspec.HeaderRules`'s, which this module sits below.
    *   `besu-eth/besu` @ `fdf1247c6d` (2026-08-26) splits it the same way,
    *   processing on `maybeWithdrawalsProcessor.isPresent() &&
    *   maybeWithdrawals.isPresent()` in `AbstractBlockProcessor` while its
    *   `WithdrawalsValidator` holds the agreement between the fork and the
    *   body. A block whose body carries withdrawals its height does not admit
    *   is caught by [[BlockOutput.withdrawalsRoot]] disagreeing with the header,
    *   which is where every other commitment this layer produces is caught.
    *
    *   [[systemCalls]] is split the same way for the same reason, and its own
    *   entry states the split from the other side.
    * @param systemCalls
    *   the invocations this block makes on its own account before any
    *   transaction runs, in the order given. [[SystemCall]] states what running
    *   one does; this states where it happens.
    *
    *   **A sequence rather than one, because the field already runs two.**
    *   `ethereum/go-ethereum` @ `02872e9ef` (2026-09-09)
    *   `core/state_processor.go:156-174` has a `PreExecution` calling
    *   `ProcessBeaconBlockRoot` and then `ProcessParentBlockHash`, and
    *   `besu-eth/besu` @ `b330564a9` (2026-09-09) reaches the same pair by
    *   having its Prague pre-execution processor extend its Cancun one. So the
    *   second consumer is running code rather than a shape guessed at, which is
    *   the test `.claude/rules/reference-first.md` sets for widening one -- and
    *   none of that second consumer is built here.
    *
    *   **Which calls a block makes is NOT decided here**, exactly as
    *   `withdrawals` is not. A proposal names the address and the caller
    *   supplies the input, and the three clients read for this gate on the
    *   header field the proposal added rather than on a fork alone --
    *   `NethermindEth/nethermind` @ `3a98e0818` (2026-09-09) is the most
    *   explicit, testing `spec.IsBeaconBlockRootAvailable && !header.IsGenesis
    *   && header.ParentBeaconBlockRoot is not null`. Whether a header at this
    *   height may carry that field is `org.fukuii.chainspec.HeaderRules`'s, and
    *   the genesis clause is the proposal's own: *"If this EIP is active in a
    *   genesis block, the genesis header's `parent_beacon_block_root` must be
    *   `0x0` and no system transaction may occur"* (`ethereum/EIPs` @
    *   `d2a64c2d4` (2026-09-09), `EIPS/eip-4788.md`, Final).
    *
    *   **The irregular state change is kept ahead of these**, which is
    *   `ethereum/go-ethereum` @ `02872e9ef`'s own order:
    *   `core/state_processor.go:85-86` applies the DAO change and `:107` calls
    *   `PreExecution`. It is unobservable on every network this build serves,
    *   because no fork schedules an irregular state change and a system call at
    *   one height, and it is stated so that a network that did would not be
    *   settling it by accident.
    */
  def process(
      transactions: Seq[Transaction],
      world: WorldState,
      destroyAccount: Address => Unit,
      stateRootAfterTransaction: () => Hash,
      block: BlockContext,
      blockHashAt: BigInt => Hash,
      chainId: UInt64,
      evm: EvmRules,
      execution: ExecutionRules,
      admission: AdmissionRules,
      irregularStateChange: Option[WorldState => Unit],
      consensusStateChange: WorldState => Unit,
      withdrawals: Option[Seq[Withdrawal]] = None,
      blobGas: Option[BlobGasAccounting] = None,
      systemCalls: Seq[SystemCall] = Seq.empty
  ): Either[BlockRejection, BlockOutput] =
    irregularStateChange.foreach(change => change(world))
    // Each is run whatever the one before it reported, because none of them is
    // conditional on another and an unbuilt operation is not a refusal. The
    // FIRST gap is the one carried, which is the order `settleInto` already
    // keeps for the transactions that follow.
    val unbuilt = systemCalls.foldLeft(Option.empty[Unsupported]) { (carried, call) =>
      val gap = SystemCall.run(call, world, block, blockHashAt, chainId, evm)
      carried.orElse(gap)
    }
    val processed = transactions.zipWithIndex.foldLeft[Either[BlockRejection, BlockOutput]](Empty(blobGas, unbuilt)) {
      (carried, indexed) =>
        carried.flatMap { output =>
          val (transaction, index) = indexed
          settleInto(
            output,
            transaction,
            index,
            world,
            destroyAccount,
            stateRootAfterTransaction,
            block,
            blockHashAt,
            chainId,
            evm,
            execution,
            admission,
            blobGas
          )
        }
    }
    processed.map { output =>
      withdrawals.foreach(carried => Withdrawals.credit(carried, world, destroyAccount))
      consensusStateChange(world)
      output.copy(withdrawalsRoot = withdrawals.map(Withdrawals.root))
    }

  /** A block that has run no TRANSACTION yet.
    *
    * A function of the accounting rather than a constant, because a block that
    * accounts for blob gas and carries no transactions has spent zero, while a
    * block below the accounting has no such answer at all -- and the empty case
    * is the one where the two are hardest to tell apart.
    *
    * **It is not a block that has done nothing**, which is why the gap arrives
    * as a parameter: a system call runs before the first transaction and can
    * reach an operation this build does not run, so a block whose transactions
    * are all still ahead of it may already have an answer to carry.
    */
  private def Empty(
      blobGas: Option[BlobGasAccounting],
      unbuilt: Option[Unsupported]
  ): Either[BlockRejection, BlockOutput] =
    Right(BlockOutput(Vector.empty, BigInt(0), unbuilt, None, blobGas.map(_ => BigInt(0))))

  /** Runs one transaction and folds what it produced into what the block holds.
    *
    * ==The gas the block has left is what admission is asked against==
    *
    * `ethereum/execution-specs` @ `ccaaaba58` opens `check_transaction` with
    * `gas_available = block_env.block_gas_limit - block_output.block_gas_used`,
    * and both go-ethereum lines take the same figure from a pool the block's
    * limit was put into and each transaction draws from. So a transaction is
    * refused for the room the transactions before it already took, and a block
    * whose transactions collectively overrun its limit is refused at the first
    * one that does not fit.
    */
  private def settleInto(
      output: BlockOutput,
      transaction: Transaction,
      index: Int,
      world: WorldState,
      destroyAccount: Address => Unit,
      stateRootAfterTransaction: () => Hash,
      block: BlockContext,
      blockHashAt: BigInt => Hash,
      chainId: UInt64,
      evm: EvmRules,
      execution: ExecutionRules,
      admission: AdmissionRules,
      blobGas: Option[BlobGasAccounting]
  ): Either[BlockRejection, BlockOutput] =
    val journal = new JournaledWorldState(world)
    val admitted =
      for
        sender <- TransactionAdmission.senderOf(transaction, chainId, admission)
        settling <- TransactionAdmission.admit(
          offered(transaction, sender),
          journal,
          block.gasLimit - output.gasUsed,
          block.baseFee,
          // The block's maximum less what the transactions before this one
          // carried, which is the figure the specification measures against
          // and not the maximum itself.
          blobGas.map(held => BlobGasTerms(held.charge, held.maximum - output.blobGasUsed.getOrElse(BigInt(0)))),
          admission,
          evm.schedule,
          evm.maxInitcodeSize
        ) match
          case Admission.Refused(reason)    => Left(reason)
          case Admission.Admitted(settling) => Right(settling)
      yield settling
    admitted.left
      .map(reason => BlockRejection(index, reason))
      .map { settling =>
        val settlement =
          TransactionProcessor.settle(settling, journal, destroyAccount, block, blockHashAt, chainId, evm, execution)
        val used = output.gasUsed + settlement.gasUsed
        BlockOutput(
          receipts = output.receipts :+
            receiptFor(transaction.transactionType, settlement, used, stateRootAfterTransaction, execution),
          gasUsed = used,
          unbuilt = output.unbuilt.orElse(settlement.unbuilt),
          withdrawalsRoot = output.withdrawalsRoot,
          // Accumulated from what ADMISSION priced the transaction at rather
          // than recounted off the transaction here, so the figure a block
          // commits to is the one its transactions were charged for. The
          // specification adds the same value it returned from
          // `check_transaction` (`fork.py:778`).
          blobGasUsed = output.blobGasUsed.map(_ + settling.blobGasUsed)
        )
      }

  /** The receipt one settled transaction leaves.
    *
    * ==Whether the first field is a root or a status is the fork's answer, and
    * it is read here==
    *
    * EIP-658 replaced the intermediate state root with a status code, and both
    * forms stay live for a client that reads history from genesis.
    * [[ExecutionRules.receiptCarriesStatus]] is that rule and this is what
    * consumes it. `ethereum/go-ethereum-pow` @ `v1.10.26` branches at the same
    * point, computing `statedb.IntermediateRoot(...)` for a receipt below the
    * fork and calling `statedb.Finalise` above it, so the root is not taken at
    * all where nothing carries one.
    *
    * The cumulative figure is the block's gas used *including* this
    * transaction: the specification adds to `block_gas_used` before it calls
    * `make_receipt`, and go-ethereum increments `usedGas` before it fills
    * `CumulativeGasUsed`.
    *
    * The bloom is derived from the logs rather than supplied, because there is
    * nothing else it could be: a receipt's bloom is a function of its own logs,
    * and taking it from anywhere else is how a receipts root goes wrong in a
    * way no other field reveals.
    *
    * ==Reachable from outside a block, because a receipt is settled one
    * transaction at a time==
    *
    * A caller that settles a transaction without a block around it holds
    * everything below and has no other way to reach the receipt that
    * transaction leaves. Keeping this private would leave such a caller to
    * write the fork branch again, and a second reading of
    * [[ExecutionRules.receiptCarriesStatus]] is exactly the duplicate that
    * makes a corpus agree with a copy of the rule rather than with the rule.
    *
    * It asks for the format rather than the transaction because the format is
    * all it reads of one, and a caller holding a transaction that was never
    * signed -- which the published state corpora are full of -- has a format
    * and no envelope to take it from.
    */
  def receiptFor(
      transactionType: TransactionType,
      settlement: Settlement,
      cumulativeGasUsed: BigInt,
      stateRootAfterTransaction: () => Hash,
      execution: ExecutionRules
  ): Receipt =
    val outcome =
      if execution.receiptCarriesStatus then
        if settlement.succeeded then PostStateOrStatus.Successful else PostStateOrStatus.Failed
      else PostStateOrStatus.PostState(stateRootAfterTransaction())
    Receipt.withDerivedBloom(
      transactionType,
      outcome,
      cumulativeReceiptGas(cumulativeGasUsed),
      settlement.logs
    )

  /** What the block has charged so far, as a receipt records it.
    *
    * Admission refuses a transaction asking for more than the block has left,
    * so the running total cannot exceed the block's own limit -- and a limit
    * arrives from a header, where it is already this width. A caller that
    * processed a block whose limit is wider than any header can state is what
    * makes this unrepresentable, which is a broken precondition rather than a
    * state a chain can reach, and is raised as one.
    * [[TransactionProcessor]]'s successor nonce carries its contract the same
    * way and for the same reason.
    */
  private def cumulativeReceiptGas(used: BigInt): UInt64 =
    UInt64
      .fromBigInt(used)
      .getOrElse(
        throw new IllegalStateException("a block charged gas no header could state: " + used.toString)
      )

  /** The transaction as the values admission reads.
    *
    * ==What a format states, never what it will pay==
    *
    * A format stating a cap and a tip is carried here as the pair it stated.
    * Resolving that pair into one price needs the block's charge, and this is
    * not where that happens -- [[FeeOffer]] holds the resolution and admission
    * applies it, because admission is where a base fee is finally in hand.
    *
    * ==One of the five is still unreachable, and the obligation has now been
    * discharged twice==
    *
    * [[TransactionAdmission.senderOf]] asks
    * [[TransactionAdmission.admitsFormat]] ahead of everything else it does,
    * and [[settleInto]] binds its answer before this is applied at all, so a
    * format these rules do not admit is refused for that FORMAT and never
    * reaches here. **That ordering was never the guarantee**: what kept the
    * branch out of reach was that no rule set admitted the format, and the
    * obligation recorded against it was that a fork admitting one brings the
    * fee rule that prices it, both landing together.
    *
    * It has now happened twice in the shape the obligation named. The fee
    * market arrived with the fee-market format. The blob-gas accounting and the
    * charge derived from it arrived with the blob format, and the two extra
    * fields that format carries -- what it will pay per unit of blob gas, and
    * the commitments it is paying for -- are read here for the first time,
    * against rules that reached this module in the same upgrade.
    *
    * **The remaining format carries a further field no rule here reads, so the
    * obligation stands unchanged for it**: a fork admitting it brings what
    * prices it. The branch is raised rather than returned because a rule set
    * admitting a format nothing prices is a configuration this project would
    * have had to write, so there is no caller who could act on it and nothing
    * on a chain that produces it.
    */
  private def offered(transaction: Transaction, sender: Address): OfferedTransaction =
    val price = transaction match
      case t: Transaction.Legacy     => FeeOffer.Fixed(t.gasPrice.toBigInt)
      case t: Transaction.AccessList => FeeOffer.Fixed(t.gasPrice.toBigInt)
      case t: Transaction.DynamicFee =>
        FeeOffer.Capped(t.maxFeePerGas.toBigInt, t.maxPriorityFeePerGas.toBigInt)
      case t: Transaction.Blob =>
        FeeOffer.Capped(t.maxFeePerGas.toBigInt, t.maxPriorityFeePerGas.toBigInt)
      case t: Transaction.SetCode => unpriced(t)
    // A format that carries no declaration offers an empty one, which is
    // charged nothing and warms nothing. Written out per payload rather than as
    // a wildcard, so a format added later cannot silently offer an empty
    // declaration while carrying a real one.
    val declared = transaction match
      case _: Transaction.Legacy     => Seq.empty
      case t: Transaction.AccessList => t.accessList
      case t: Transaction.DynamicFee => t.accessList
      case t: Transaction.Blob       => t.accessList
      case t: Transaction.SetCode    => t.accessList
    // NONE and not an empty offer, for every format that carries no such
    // field: a blob transaction stating an empty sequence is a transaction the
    // rules refuse, and collapsing the two would make that refusal unreachable
    // while refusing every ordinary transaction instead. [[BlobOffer]] states
    // the distinction; this is the one site that can express it, because only
    // here is the payload's own shape still visible.
    val blobs = transaction match
      case _: Transaction.Legacy     => None
      case _: Transaction.AccessList => None
      case _: Transaction.DynamicFee => None
      case t: Transaction.Blob       =>
        Some(BlobOffer(t.maxFeePerBlobGas.toBigInt, t.blobVersionedHashes))
      case _: Transaction.SetCode => None
    OfferedTransaction(
      transactionType = transaction.transactionType,
      sender = sender,
      nonce = transaction.nonce.toBigInt,
      fee = price,
      gasLimit = transaction.gasLimit.toBigInt,
      to = transaction.to,
      value = transaction.value.toBigInt,
      data = transaction.data,
      accessList = declared,
      blobs = blobs
    )

  private def unpriced(transaction: Transaction): Nothing =
    throw new IllegalStateException(
      "these rules admit " + transaction.transactionType.toString +
        ", whose charge is computed against a base fee this build does not hold"
    )
