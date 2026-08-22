package org.fukuii.execution

import org.fukuii.bytes.{Address, Hash, UInt64}
import org.fukuii.evm.{BlockContext, EvmRules, JournaledWorldState, Unsupported, WorldState}
import org.fukuii.types.{Log, PostStateOrStatus, Receipt, Transaction}

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
  */
final case class BlockOutput(
    receipts: Vector[Receipt],
    gasUsed: BigInt,
    unbuilt: Option[Unsupported]
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

/** What a block does around the transactions it carries.
  *
  * ==The whole of it is an ordered loop and two state changes nobody signed==
  *
  * `ethereum/execution-specs` @ `ccaaaba58` is the plainest statement of the
  * loop: `frontier/fork.py`'s `apply_body` is a `for` over the transactions
  * followed by `pay_rewards`, and nothing else.
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
  * fork, and the surveyed clients disagree about it more than about anything
  * else in this record: `ethereumclassic/core-geth` @ `4185df450` has
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
    * ==Three things happen in an order that is consensus-critical==
    *
    * The irregular state change first, where one is scheduled, so that a
    * transaction in this very block sees the state it left; then the
    * transactions, each seeing what the one before it wrote; then the consensus
    * mechanism's own change, which every surveyed client applies after the last
    * transaction and before anything reads the block's final root.
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
      consensusStateChange: WorldState => Unit
  ): Either[BlockRejection, BlockOutput] =
    irregularStateChange.foreach(change => change(world))
    val processed = transactions.zipWithIndex.foldLeft[Either[BlockRejection, BlockOutput]](Empty) {
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
            admission
          )
        }
    }
    processed.map { output =>
      consensusStateChange(world)
      output
    }

  /** A block that has run nothing yet. */
  private val Empty: Either[BlockRejection, BlockOutput] =
    Right(BlockOutput(Vector.empty, BigInt(0), None))

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
      admission: AdmissionRules
  ): Either[BlockRejection, BlockOutput] =
    val journal = new JournaledWorldState(world)
    val admitted =
      for
        sender <- TransactionAdmission.senderOf(transaction, chainId, admission)
        settling <- TransactionAdmission.admit(
          offered(transaction, sender),
          journal,
          block.gasLimit - output.gasUsed,
          admission,
          evm.schedule
        ) match
          case Admission.Refused(reason)       => Left(reason)
          case Admission.Admitted(settling, _) => Right(settling)
      yield settling
    admitted.left
      .map(reason => BlockRejection(index, reason))
      .map { settling =>
        val settlement =
          TransactionProcessor.settle(settling, journal, destroyAccount, block, blockHashAt, evm)
        val used = output.gasUsed + settlement.gasUsed
        BlockOutput(
          receipts = output.receipts :+ receiptFor(transaction, settlement, used, stateRootAfterTransaction, execution),
          gasUsed = used,
          unbuilt = output.unbuilt.orElse(settlement.unbuilt)
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
    */
  private def receiptFor(
      transaction: Transaction,
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
      transaction.transactionType,
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
    * ==Only a format stating its own price is reachable here==
    *
    * The three formats whose charge is computed against a block's base fee
    * state a cap and a tip rather than a price, and no rule set in this project
    * admits one: each holds the legacy format alone.
    * [[TransactionAdmission.admitsFormat]] is asked before this, by
    * [[TransactionAdmission.senderOf]], so a block carrying such a transaction
    * is refused for its FORMAT and never priced. A rule set that admitted one
    * without the fee rule that prices it is a configuration this project would
    * have had to write, which is why the impossible branch is raised rather
    * than returned -- there is no caller who could act on it and nothing on a
    * chain that produces it.
    */
  private def offered(transaction: Transaction, sender: Address): OfferedTransaction =
    val price = transaction match
      case t: Transaction.Legacy     => t.gasPrice.toBigInt
      case t: Transaction.AccessList => t.gasPrice.toBigInt
      case t: Transaction.DynamicFee => unpriced(t)
      case t: Transaction.Blob       => unpriced(t)
      case t: Transaction.SetCode    => unpriced(t)
    OfferedTransaction(
      transactionType = transaction.transactionType,
      sender = sender,
      nonce = transaction.nonce.toBigInt,
      gasPrice = price,
      gasLimit = transaction.gasLimit.toBigInt,
      to = transaction.to,
      value = transaction.value.toBigInt,
      data = transaction.data
    )

  private def unpriced(transaction: Transaction): Nothing =
    throw new IllegalStateException(
      "these rules admit " + transaction.transactionType.toString +
        ", whose charge is computed against a base fee this build does not hold"
    )
