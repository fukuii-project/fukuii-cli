package org.fukuii.blockchaintests

import scala.collection.mutable
import scala.util.control.NonFatal

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.consensus.{BlockValidator, BlockVerdict, Resolved, RuleNotRun}
import org.fukuii.evm.StateTrieWorldState
import org.fukuii.evm.fixtures.{ExpectedRejection, FixtureAccount, FixtureValues, SkipReason, VmFixtureRunner}
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.StateTrie
import org.fukuii.types.Block

/** What running one published case established. */
enum BlockchainVerdict:

  /** Every block the case states valid was accepted, the chain ended where the
    * case says, and a block it states invalid was refused under a name it
    * states.
    */
  case Agreed

  case Diverged(reasons: Vector[String])

  /** A block reached a rule this build does not run, so no answer this build
    * reaches about the case is one the network's rules decided.
    *
    * **Its own outcome and never a kind of agreement or of skip.** The block
    * was run, and what stopped it is named, so a count of these is the size of
    * the rules that stand between this build and a verdict.
    *
    * @param block
    *   where the case states the block, counting from its first after genesis.
    */
  case Undecided(block: Int, rule: RuleNotRun)

  /** The case was not run, for a reason on this side of the corpus. */
  case Skipped(reason: SkipReason)

/** A verdict, and what this build did with the case's blocks on the way to it.
  *
  * @param blocksAccepted
  *   blocks the validator accepted.
  * @param refusalsAgreed
  *   blocks the validator refused under a name the case states -- counted where
  *   the refusal happens rather than read off the verdict, because a runner that
  *   never ran the refused block would reach the same verdict and must not reach
  *   the same count.
  */
final case class BlockchainResult(verdict: BlockchainVerdict, blocksAccepted: Int, refusalsAgreed: Int)

/** Runs one published block-tier case against this build's block validator.
  *
  * ==Everything a block needs is production code==
  *
  * Decoding a block, resolving the rules at its height, validating its header
  * against its parent, running its body and comparing every commitment it
  * states is [[org.fukuii.consensus.BlockValidator.validate]], which is what a
  * node runs. What is left here is the harness's own: building the genesis the
  * case states, linking each block to the one before it, serving `BLOCKHASH`
  * from the blocks this chain imported, and comparing where the chain ended
  * with where the case says it ends. **Every production client read for this
  * tier delegates the same way** -- `ethereum/go-ethereum` @ `02872e9ef` to its
  * production chain (`tests/block_test_util.go:172`), `besu-eth/besu` @
  * `b330564a94` to `BlockValidator.validateAndProcessBlock`
  * (`BlockchainReferenceTestTools.java:194-202`), and `paradigmxyz/reth` @
  * `e63ec720ac` block by block over a linear parent (`blockchain_test.rs:229-277`).
  *
  * ==Compared before a refusal is run, and why that needs a precondition==
  *
  * The validator writes a block into the world as it runs it, so a block it
  * refuses has already moved state. The runner therefore compares the head and
  * the post-state after the last block the case accepts and before the block it
  * refuses, which needs no way to undo a write -- and is sound only where the
  * refused block is the case's last. A case where one is not fails here, loudly,
  * rather than being run against a world the refused block may have changed.
  *
  * **The precondition holds in every label this runner is built for, and not in
  * the whole release.** At `tests-v20.0.1` every refused block is its case's
  * last in each label from `for_frontier` to `for_cancun` and in both transition
  * labels between them; `for_praguetoosakaattime15k` is the exception, where
  * `osaka/eip7594_peerdas/max_blob_per_tx/max_blobs_per_tx_fork_transition.json`
  * refuses consecutive blocks -- two cases shaped refused, refused, refused and
  * accepted, refused, refused. **A label with sibling refusals needs each
  * refused block run against the state its accepted parent left**, which this
  * runner does not do: a copy of the world taken before each refusal, or a
  * replay of the accepted prefix, is what that label brings.
  *
  * ==Whether the reason is compared is where the field splits==
  *
  * `besu-eth/besu` compares it, by name; `ethereum/go-ethereum` compares only
  * whether the block was accepted (`tests/block_test_util.go:237-246`), as do
  * `paradigmxyz/reth` and `NethermindEth/nethermind`'s block path
  * (`Ethereum.Test.Base/BlockchainTestBase.cs:361-393`). This compares it, by
  * name, through [[BlockRefusalVocabulary]].
  *
  * ==Which of those clients this one follows past a refused block==
  *
  * Continuing past one or stopping at it cannot be told apart here, because the
  * refused block is last. This stops, which is what the specification's own
  * loader does (`ethereum/execution-specs` @ `0cc100eb1`,
  * `tests/json_loader/helpers/load_blockchain_tests.py:183-190`).
  */
object BlockchainRunner:

  /** Runs `fixture`, answering a raised failure as a divergence.
    *
    * A block this build throws on is a published block this build cannot run,
    * which is a disagreement with the corpus and not a harness fault; counting
    * it as either a skip or an abort would drop it from the one count that
    * reports disagreements.
    *
    * @param networks
    *   what a case's `network` resolves to. [[FixtureNetworks.named]] for every
    *   published run; a caller exercising an outcome no published label reaches
    *   yet -- a rule an engine does not run, a mechanism that raises -- supplies
    *   a network carrying that engine instead.
    */
  def run(
      fixture: BlockchainFixture,
      networks: String => Either[String, FixtureNetwork] = FixtureNetworks.named
  ): BlockchainResult =
    val progress = new Progress
    val verdict =
      try runCase(fixture, networks, progress).fold(identity, _ => BlockchainVerdict.Agreed)
      catch
        case NonFatal(cause) =>
          BlockchainVerdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    BlockchainResult(verdict, progress.accepted, progress.refused)

  /** How far a case got, kept apart from the verdict so a case that diverges
    * still reports how many of its blocks were accepted first.
    */
  final private class Progress:
    private var acceptedCount: Int = 0
    private var refusedCount: Int = 0
    def accepted: Int = acceptedCount
    def refused: Int = refusedCount
    def accept(): Unit = acceptedCount += 1
    def refuse(): Unit = refusedCount += 1

  private type Step[A] = Either[BlockchainVerdict, A]

  private def diverged(reason: String): BlockchainVerdict = BlockchainVerdict.Diverged(Vector(reason))

  private def runCase(
      fixture: BlockchainFixture,
      networks: String => Either[String, FixtureNetwork],
      progress: Progress
  ): Step[Unit] =
    for
      network <- networks(fixture.network).left.map(skippedFor)
      _ <- refusalIsLast(fixture)
      _ <- configAgrees(fixture, network)
      expectedRoot <- rootOf(fixture.postState)
      chain <- genesisOf(fixture, network)
      _ <- acceptEveryValidBlock(fixture, network, chain, progress)
      _ <- endsWhereStated(fixture, chain, expectedRoot)
      _ <- refusesTheInvalidBlock(fixture, network, chain, progress)
    yield ()

  private def skippedFor(reason: String): BlockchainVerdict =
    BlockchainVerdict.Skipped(SkipReason.RuleNotBuilt(reason))

  /** The precondition compare-before-refuse rests on: nothing follows a block
    * the case states invalid.
    */
  private def refusalIsLast(fixture: BlockchainFixture): Step[Unit] =
    val last = fixture.blocks.length - 1
    val followed = fixture.blocks.zipWithIndex.collect {
      case (StatedBlock.Invalid(_, _, _), index) if index != last => index
    }
    if followed.isEmpty then Right(())
    else
      Left(
        diverged(
          "the case states block " + followed.mkString(", ") + " of " + fixture.blocks.length.toString +
            " invalid with blocks after it, and this runner compares the chain before running a refused block"
        )
      )

  /** What a case's `config` states, against the network it resolved to.
    *
    * ==Every key is compared, or the case does not agree==
    *
    * A chain identifier is compared with the network's, and a network name with
    * the case's own. **A key neither comparison reads diverges by name**, the
    * rule a published refusal name this build lacks is held to: a configuration
    * passed over is a rule the case exercises and this runner never looked at,
    * and agreeing with such a case would claim a comparison nobody made.
    */
  private def configAgrees(fixture: BlockchainFixture, network: FixtureNetwork): Step[Unit] =
    fixture.config match
      case None         => Right(())
      case Some(config) =>
        val unread = Option.when(config.otherKeys.nonEmpty)(
          "the case's config states " + config.otherKeys.mkString(", ") + ", which this runner does not compare"
        )
        val named = config.network.filter(_ != fixture.network).map { stated =>
          "the case's config names the network " + stated + " where the case names " + fixture.network
        }
        val identified = config.chainId.filter(_ != network.chainId).map { stated =>
          "the case states chain id " + stated.show + " where the network " + fixture.network + " runs as " +
            network.chainId.show
        }
        val reasons = unread.toVector ++ named.toVector ++ identified.toVector
        if reasons.isEmpty then Right(()) else Left(BlockchainVerdict.Diverged(reasons))

  /** The root of the state the case says the chain ends holding.
    *
    * Taken before anything runs, so a post-state that cannot be held in a world
    * is a case this runner did not read rather than one it ran and disagreed
    * with.
    */
  private def rootOf(accounts: Map[Address, FixtureAccount]): Step[Hash] =
    val trie = VmFixtureRunner.freshTrie()
    FixtureValues
      .seed(new StateTrieWorldState(trie), accounts)
      .left
      .map(error => BlockchainVerdict.Skipped(SkipReason.Undecodable("post-state: " + error)))
      .map(_ => trie.stateRoot)

  /** The chain as it stands: the state, the head, and every block imported so
    * far by number.
    */
  final private class Chain(val trie: StateTrie, val world: StateTrieWorldState, genesis: Resolved):
    private val hashes: mutable.HashMap[BigInt, Hash] =
      mutable.HashMap(genesis.header.number.toBigInt -> genesis.header.hash)
    private var current: Resolved = genesis

    def head: Resolved = current

    def extend(next: Resolved): Unit =
      hashes.update(next.header.number.toBigInt, next.header.hash)
      current = next

    /** The hash of an imported block by its number.
      *
      * `BLOCKHASH` asks only for a number inside its window below the block
      * being run, and every such number on a linear chain is an imported block,
      * so a number missing here is a harness defect rather than an answer.
      */
    def hashAt(number: BigInt): Hash =
      hashes.getOrElse(
        number,
        throw new IllegalStateException(
          "BLOCKHASH asked for block " + number.toString + ", which this chain has not imported"
        )
      )

  /** The genesis the case states, verified rather than assumed.
    *
    * Its encoding must decode to a header hashing to the stated hash, and the
    * case's pre-state must seed a world whose root is the one that header
    * commits to. Both are this build's codec, hash and trie, so a mismatch is a
    * divergence.
    */
  private def genesisOf(fixture: BlockchainFixture, network: FixtureNetwork): Step[Chain] =
    val trie = VmFixtureRunner.freshTrie()
    val world = new StateTrieWorldState(trie)
    for
      block <- decoded(fixture.genesisRlp).left.map(error => diverged("the genesis does not decode: " + error))
      _ <- Either.cond(
        block.hash == fixture.genesisHash,
        (),
        diverged("the genesis hashes to " + block.hash.toString + ", the case states " + fixture.genesisHash.toString)
      )
      _ <- FixtureValues
        .seed(world, fixture.pre)
        .left
        .map(error => BlockchainVerdict.Skipped(SkipReason.Undecodable("pre-state: " + error)))
      _ <- Either.cond(
        trie.stateRoot == block.header.stateRoot,
        (),
        diverged(
          "the pre-state seeds root " + trie.stateRoot.toString + ", the genesis commits to " +
            block.header.stateRoot.toString
        )
      )
    yield
      val rules = network.schedule.at(block.header.number, block.header.timestamp)
      new Chain(trie, world, Resolved(block.header, rules))

  private def decoded(rlp: Bytes): Either[String, Block] =
    RlpCodec.decodeFrom[Block](rlp.toIArray).left.map(_.toString)

  private def acceptEveryValidBlock(
      fixture: BlockchainFixture,
      network: FixtureNetwork,
      chain: Chain,
      progress: Progress
  ): Step[Unit] =
    fixture.blocks.zipWithIndex.foldLeft[Step[Unit]](Right(())) { case (carried, (stated, index)) =>
      carried.flatMap { _ =>
        stated match
          case StatedBlock.Valid(rlp, hash) => accept(rlp, hash, index, network, chain, progress)
          case StatedBlock.Invalid(_, _, _) => Right(())
      }
    }

  private def accept(
      rlp: Bytes,
      statedHash: Hash,
      index: Int,
      network: FixtureNetwork,
      chain: Chain,
      progress: Progress
  ): Step[Unit] =
    val where = "block " + index.toString
    for
      block <- decoded(rlp).left.map(error => diverged(where + " does not decode: " + error))
      _ <- Either.cond(
        block.hash == statedHash,
        (),
        diverged(where + " hashes to " + block.hash.toString + ", the case states " + statedHash.toString)
      )
      _ <- imported(block, index, network, chain, progress)
    yield ()

  /** A block the case states valid, validated and, where it is, made the head. */
  private def imported(
      block: Block,
      index: Int,
      network: FixtureNetwork,
      chain: Chain,
      progress: Progress
  ): Step[Unit] =
    val rules = network.schedule.at(block.header.number, block.header.timestamp)
    validated(block, rules, network, chain) match
      case BlockVerdict.Valid(_) =>
        chain.extend(Resolved(block.header, rules))
        progress.accept()
        Right(())
      case BlockVerdict.Invalid(fault) =>
        Left(diverged("block " + index.toString + ", which the case states valid, was refused: " + fault.toString))
      case BlockVerdict.Undecided(rule) =>
        Left(BlockchainVerdict.Undecided(index, rule))

  private def validated(block: Block, rules: UpgradeRules, network: FixtureNetwork, chain: Chain): BlockVerdict =
    BlockValidator.validate(
      block = block,
      rules = rules,
      parent = chain.head,
      engine = network.engine,
      world = chain.world,
      destroyAccount = chain.trie.destroyAccount,
      stateRoot = () => chain.trie.stateRoot,
      blockHashAt = chain.hashAt,
      chainId = network.chainId
    )

  /** The head against the case's last block hash, and the world against the
    * state the case says it ends holding.
    *
    * ==The accounts, and the whole state==
    *
    * The accounts the case lists are compared field by field, which is what a
    * reader acts on. **The listing alone is not the whole state**: an account
    * this build created and the case does not list would pass it. So the root of
    * the listed state is compared with the world's too, which is the
    * specification's whole-state equality (`ethereum/execution-specs` @
    * `0cc100eb1`, `tests/json_loader/helpers/load_blockchain_tests.py:199-202`)
    * reached through the commitment rather than an enumeration a trie cannot
    * give.
    *
    * ==What the root adds, since every accepted block's own root is compared==
    *
    * The validator already refuses a block whose header commits to some other
    * state, as a client's own import does -- `ethereum/go-ethereum` @ `02872e9ef`
    * compares listed accounts in its runner (`tests/block_test_util.go:361-385`)
    * after its production import has refused any block whose state root is not
    * the one it reached (`core/block_validator.go:205-206`), and this runner's
    * genesis and validator compare the head's own root the same way. What the
    * root here adds is the other half: that the state the case LISTS is the
    * whole of the state the head commits to, so a listing missing an account is
    * a divergence rather than a comparison over fewer accounts.
    */
  private def endsWhereStated(fixture: BlockchainFixture, chain: Chain, expectedRoot: Hash): Step[Unit] =
    val head = chain.head.header.hash
    val headReason =
      Option.when(head != fixture.lastBlockHash)(
        "the chain's head hashes to " + head.toString + ", the case's last block hash is " +
          fixture.lastBlockHash.toString
      )
    val slots = (address: Address) => fixture.pre.get(address).fold(Set.empty[BigInt])(_.storage.keySet)
    val accounts = FixtureValues.divergences(chain.world, fixture.postState, slots)
    val gone = fixture.pre.keySet.diff(fixture.postState.keySet).toVector.sortBy(_.toHex).flatMap { address =>
      Option.when(chain.world.accountExists(address))(
        address.toString + " still exists, where the case ends without it"
      )
    }
    val root = chain.trie.stateRoot
    val rootReason =
      Option.when(root != expectedRoot)(
        "the world's root is " + root.toString + ", the root of the post-state the case states is " +
          expectedRoot.toString
      )
    val reasons = headReason.toVector ++ accounts ++ gone ++ rootReason.toVector
    if reasons.isEmpty then Right(()) else Left(BlockchainVerdict.Diverged(reasons))

  /** The block the case states invalid, read as the case says it reads and
    * refused under a name the case states.
    *
    * ==Its encoding is held to the case's decoded form before anything runs==
    *
    * Where the case publishes the refused block's decoded header, the bytes must
    * decode to a header hashing to it, exactly as a block the case accepts must;
    * otherwise a refusal could agree about a block this build read differently.
    * Where the case publishes none, it is stating the block does not decode, so
    * this build decoding it is a disagreement before any rule is asked.
    */
  private def refusesTheInvalidBlock(
      fixture: BlockchainFixture,
      network: FixtureNetwork,
      chain: Chain,
      progress: Progress
  ): Step[Unit] =
    fixture.blocks.lastOption match
      case Some(StatedBlock.Invalid(rlp, expected, decodedHash)) =>
        val index = fixture.blocks.length - 1
        val where = "block " + index.toString
        decoded(rlp) match
          case Left(error) =>
            Left(
              diverged(where + " does not decode (" + error + "), where the case refuses it as " + expected.describe)
            )
          case Right(block) =>
            decodedHash match
              case None =>
                Left(diverged("the case publishes no decoded form of " + where + ", which this build decodes"))
              case Some(stated) if stated != block.hash =>
                Left(diverged(where + " hashes to " + block.hash.toString + ", the case states " + stated.toString))
              case Some(_) =>
                refused(block, index, expected, network, chain, progress)
      case _ => Right(())

  private def refused(
      block: Block,
      index: Int,
      expected: ExpectedRejection,
      network: FixtureNetwork,
      chain: Chain,
      progress: Progress
  ): Step[Unit] =
    val where = "block " + index.toString
    val rules = network.schedule.at(block.header.number, block.header.timestamp)
    validated(block, rules, network, chain) match
      case BlockVerdict.Valid(_) =>
        Left(diverged(where + " was accepted, where the case refuses it as " + expected.describe))
      case BlockVerdict.Invalid(fault) =>
        if BlockRefusalVocabulary.satisfies(expected, fault) then
          progress.refuse()
          Right(())
        else Left(diverged(refusedOtherwise(where, fault.toString, expected)))
      case BlockVerdict.Undecided(rule) =>
        Left(BlockchainVerdict.Undecided(index, rule))

  private def refusedOtherwise(where: String, fault: String, expected: ExpectedRejection): String =
    val unmapped = BlockRefusalVocabulary.unmapped(expected)
    val note =
      if unmapped.isEmpty then ""
      else "; this build's vocabulary names none of " + unmapped.toVector.sorted.mkString(", ")
    where + " was refused as " + fault + ", where the case refuses it as " + expected.describe + note
