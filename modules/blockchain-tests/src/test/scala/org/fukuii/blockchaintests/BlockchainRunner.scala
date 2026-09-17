package org.fukuii.blockchaintests

import scala.collection.mutable
import scala.util.control.NonFatal

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.consensus.{
  BlockFault,
  BlockValidator,
  BlockVerdict,
  HeaderFault,
  HeaderValidator,
  Resolved,
  RuleNotRun
}
import org.fukuii.evm.StateTrieWorldState
import org.fukuii.evm.fixtures.{ExpectedRejection, FixtureAccount, FixtureValues, SkipReason, VmFixtureRunner}
import org.fukuii.rlp.{RlpCodec, RlpError}
import org.fukuii.trie.StateTrie
import org.fukuii.types.Block

/** What running one published case established. */
enum BlockchainVerdict:

  /** Every block the case states valid was accepted, the chain ended where the
    * case says, and every block it states invalid was refused under a name it
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
  *   blocks refused under a name the case states -- counted where the refusal
  *   happens rather than read off the verdict, because a runner that never ran
  *   the refused block would reach the same verdict and must not reach the same
  *   count.
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
  * ==A refused block runs against the world its parent left, and this keeps one==
  *
  * The validator writes a block into the world as it runs it, so a block it
  * refuses after running has already moved state, and this runner keeps no copy
  * to put back. Two positions make that sound.
  *
  *   - **A refused block that is the case's last** is refused after the head
  *     and the post-state have been compared, so what it writes is never read.
  *   - **A refused block with blocks after it** is refused in place, and the
  *     case goes on from the block before it. That is sound only where nothing
  *     was written, so the refusal must be one decided before the block runs
  *     and the world's root must be where it was -- both are checked, per
  *     refusal, and a refusal failing either diverges and names which.
  *
  * **A refusal after the run with blocks after it needs the state before it**,
  * which this runner does not keep: a copy of the world taken before each such
  * block, or a replay of the accepted prefix, is what a case of that shape
  * brings.
  *
  * ==Whether the reason is compared is where the field splits==
  *
  * `besu-eth/besu` compares it, by name; `ethereum/go-ethereum` compares only
  * whether the block was accepted (`tests/block_test_util.go:237-246`), as do
  * `paradigmxyz/reth` and `NethermindEth/nethermind`'s block path
  * (`Ethereum.Test.Base/BlockchainTestBase.cs:361-393`). This compares it, by
  * name, through [[BlockRefusalVocabulary]].
  *
  * **A name is satisfied by any shared header rule the block breaks, not only
  * by the one reported first.** A header breaking two rules is refused by
  * whichever runs first, and the order another implementation used can name
  * the other. The one runner read that compares names cannot tell header rules
  * apart at all: `besu-eth/besu` @ `b330564a94` maps each generated header name
  * to one message, `Header validation failed`
  * (`ethereum/referencetests/src/main/resources/block-exception-mapping.json:16`).
  * So where the validator's first fault does not carry the stated name,
  * [[org.fukuii.consensus.HeaderValidator.faults]] is asked for every shared
  * rule the header breaks, and the first it lists must be the validator's own.
  * The mechanism's own rules are not asked past a shared fault, because
  * [[org.fukuii.consensus.ConsensusEngine.validateHeader]] is reached only once
  * every shared rule has accepted the header.
  *
  * ==Past a refused block, this follows the clients and not the specification's
  * loader==
  *
  * `ethereum/go-ethereum` @ `02872e9ef` goes on to the next block after one it
  * refuses (`tests/block_test_util.go:250-269`), and `besu-eth/besu` @
  * `b330564a94` runs every candidate block in turn and appends only those it
  * imports (`BlockchainReferenceTestTools.java:158-220`).
  * `ethereum/execution-specs` @ `0cc100eb1` returns at the first refused block
  * (`tests/json_loader/helpers/load_blockchain_tests.py:187-190`), which leaves
  * every block a case accepts after it unchecked.
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
    * @param filledBy
    *   the tool whose names the case's refusals are stated in, which its label
    *   knows: [[FillingTool.ExecutionSpecs]]'s ids unless a caller names another.
    */
  def run(
      fixture: BlockchainFixture,
      networks: String => Either[String, FixtureNetwork] = FixtureNetworks.named,
      filledBy: FillingTool = FillingTool.ExecutionSpecs
  ): BlockchainResult =
    val progress = new Progress
    val verdict =
      try runCase(fixture, networks, filledBy, progress).fold(identity, _ => BlockchainVerdict.Agreed)
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
      filledBy: FillingTool,
      progress: Progress
  ): Step[Unit] =
    for
      network <- networks(fixture.network).left.map(skippedFor)
      _ <- sealedWithoutProof(fixture)
      _ <- configAgrees(fixture, network)
      _ <- followsOneChain(fixture)
      expectedRoot <- rootOf(fixture.postState)
      chain <- genesisOf(fixture, network)
      _ <- runsEveryBlockBeforeALastRefusal(fixture, network, filledBy, chain, progress)
      _ <- endsWhereStated(fixture, chain, expectedRoot)
      _ <- refusesALastRefusal(fixture, network, filledBy, chain, progress)
    yield ()

  private def skippedFor(reason: String): BlockchainVerdict =
    BlockchainVerdict.Skipped(SkipReason.RuleNotBuilt(reason))

  /** The seal engine the case states, which must be the one every network here
    * is configured for.
    *
    * ==`NoProof` runs, and any other value diverges before a block does==
    *
    * Each network [[FixtureNetworks]] resolves before the merge runs its blocks
    * under `org.fukuii.consensus.pow.EthashEngine` configured with
    * `SealEngine.NoProof`, which states no seal rule -- the configuration
    * `ethereum/go-ethereum-pow` @ `v1.10.26` selects on this same value
    * (`tests/block_test_util.go:119-124`). A case stating another value states a
    * seal this build does not verify, and a case stating none states nothing a
    * configuration can be chosen from. So each diverges by name, the rule a key
    * no comparison reads is held to, and a label this tier runs stating a value
    * other than `NoProof` is what brings seal verification to it.
    */
  private def sealedWithoutProof(fixture: BlockchainFixture): Step[Unit] =
    fixture.sealEngine match
      case Some(FixtureNetworks.NoProof) => Right(())
      case Some(other)                   =>
        Left(diverged("the case states the seal engine " + other + ", and this runner runs " + FixtureNetworks.NoProof))
      case None =>
        Left(diverged("the case states no seal engine, and this runner runs " + FixtureNetworks.NoProof))

  /** What a case's `config` states, against the network it resolved to.
    *
    * ==Every key is compared, or the case does not agree==
    *
    * A chain identifier is compared with the network's, a network name with the
    * case's own, and each fork's blob parameters with that fork's rules. **A key
    * none of those comparisons reads diverges by name** -- beside the others or
    * inside a blob schedule's entry -- the rule a published refusal name this
    * build lacks is held to: a configuration passed over is a rule the case
    * exercises and this runner never looked at, and agreeing with such a case
    * would claim a comparison nobody made.
    *
    * ==A case stating no config at all is a different case==
    *
    * It states no identifier and no schedule, so there is nothing to compare,
    * and the network runs as [[FixtureNetworks.ChainId]] -- which is where that
    * member records the two sources for running such a case as one.
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
        val scheduled = config.blobSchedule.toVector.flatMap(blobScheduleDivergences)
        val reasons = unread.toVector ++ named.toVector ++ identified.toVector ++ scheduled
        if reasons.isEmpty then Right(()) else Left(BlockchainVerdict.Diverged(reasons))

  /** A case whose blocks all follow one chain from the genesis.
    *
    * ==A block naming another chain diverges, by name, until a chain driver
    * exists==
    *
    * The older snapshot names each block's chain, and a block on a chain other
    * than `default` builds on that chain's blocks rather than on the one before
    * it. This runner follows one chain, so running such a block would validate
    * it against the wrong parent -- and a refusal stated for it under
    * `UnknownParent` would then agree through a parent-hash comparison the case
    * does not make. Every block `bcInvalidHeaderTest` states names `default`
    * (`ethereum/legacytests` @ `1f581b8cc`); the snapshot's multi-chain and
    * transition tiers name others.
    */
  private def followsOneChain(fixture: BlockchainFixture): Step[Unit] =
    if fixture.otherChains.isEmpty then Right(())
    else
      Left(
        BlockchainVerdict.Diverged(
          fixture.otherChains.map { (index, chain) =>
            "block " + index.toString + " states the chain " + chain +
              ", and this runner follows one chain from the genesis"
          }
        )
      )

  /** Each fork's blob parameters the case states, against the rules this build
    * resolves at that fork.
    *
    * ==Every entry, and not only the network's own==
    *
    * A transition case states an entry for each blob-carrying fork it runs, and
    * a case whose network never reaches a fork may still state one. Each entry is
    * a claim about its fork's rules, so each is read against that fork's -- and
    * an entry naming a fork this runner resolves no rules for diverges by name
    * rather than being passed over.
    *
    * `baseFeeUpdateFraction` is the machine's member rather than the header's,
    * for the reason `org.fukuii.chainspec.BlobSchedule` gives.
    */
  private def blobScheduleDivergences(stated: Map[String, StatedBlobEntry]): Vector[String] =
    stated.toVector.sortBy(_._1).flatMap { (fork, entry) =>
      val where = "the case's blob schedule for " + fork
      FixtureNetworks.forkRules(fork) match
        case None        => Vector(where + " names a fork this runner resolves no rules for")
        case Some(rules) =>
          (rules.header.blobSchedule, rules.evm.blobBaseFeeUpdateFraction) match
            case (Some(schedule), Some(fraction)) =>
              val compared = Vector(
                ("target", entry.target, schedule.targetBlobs),
                ("max", entry.max, schedule.maxBlobs),
                ("baseFeeUpdateFraction", entry.baseFeeUpdateFraction, fraction)
              ).flatMap { (key, value, resolved) =>
                value match
                  case None => Some(where + " states no " + key + ", where its rules resolve " + resolved)
                  case Some(v) if v != resolved =>
                    Some(where + " states " + key + " " + v.toString + ", where its rules resolve " + resolved)
                  case Some(_) => None
              }
              val unread = Option.when(entry.otherKeys.nonEmpty)(
                where + " states " + entry.otherKeys.mkString(", ") + ", which this runner does not compare"
              )
              compared ++ unread.toVector
            case _ => Vector(where + " names a fork whose rules this build resolves with no blob schedule")
    }

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

  private def decoded(rlp: Bytes): Either[RlpError, Block] =
    RlpCodec.decodeFrom[Block](rlp.toIArray)

  /** Whether the case's last block is one it refuses, which is refused after
    * the chain is compared rather than in place.
    */
  private def endsInRefusal(fixture: BlockchainFixture): Boolean =
    fixture.blocks.lastOption.exists {
      case StatedBlock.Invalid(_, _, _) => true
      case StatedBlock.Valid(_, _)      => false
    }

  private def runsEveryBlockBeforeALastRefusal(
      fixture: BlockchainFixture,
      network: FixtureNetwork,
      filledBy: FillingTool,
      chain: Chain,
      progress: Progress
  ): Step[Unit] =
    val inPlace = if endsInRefusal(fixture) then fixture.blocks.dropRight(1) else fixture.blocks
    inPlace.zipWithIndex.foldLeft[Step[Unit]](Right(())) { case (carried, (stated, index)) =>
      carried.flatMap { _ =>
        stated match
          case StatedBlock.Valid(rlp, hash)                    => accept(rlp, hash, index, network, chain, progress)
          case StatedBlock.Invalid(rlp, expected, decodedHash) =>
            refuseInPlace(rlp, expected, decodedHash, index, network, filledBy, chain, progress)
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

  /** How a block the case refuses was refused. */
  private enum Refused:

    /** Its bytes are not a block, under a name whose rule that is. */
    case Undecodable

    /** The validator refused it for `fault`, under a name whose rule that is. */
    case ByFault(fault: BlockFault)

  /** The block the case refuses as its last, refused after the chain has been
    * compared.
    */
  private def refusesALastRefusal(
      fixture: BlockchainFixture,
      network: FixtureNetwork,
      filledBy: FillingTool,
      chain: Chain,
      progress: Progress
  ): Step[Unit] =
    fixture.blocks.lastOption match
      case Some(StatedBlock.Invalid(rlp, expected, decodedHash)) =>
        refusal(rlp, expected, decodedHash, fixture.blocks.length - 1, network, filledBy, chain)
          .map(_ => progress.refuse())
      case _ => Right(())

  /** A block the case refuses with blocks after it, refused where it stands.
    *
    * ==Sound only where the refusal wrote nothing, so both halves of that are
    * checked==
    *
    * The next block runs on the world as it is afterwards, which is the state
    * this block's parent left only if refusing this one wrote nothing. The
    * validator states that a refusal before the run leaves the world untouched,
    * and this does not rely on it: the fault must be one decided before the
    * block runs, and the world's root must be the one it held before. A refusal
    * failing either diverges, naming which.
    */
  private def refuseInPlace(
      rlp: Bytes,
      expected: ExpectedRejection,
      decodedHash: Option[Hash],
      index: Int,
      network: FixtureNetwork,
      filledBy: FillingTool,
      chain: Chain,
      progress: Progress
  ): Step[Unit] =
    val where = "block " + index.toString
    val before = chain.trie.stateRoot
    refusal(rlp, expected, decodedHash, index, network, filledBy, chain).flatMap { refused =>
      progress.refuse()
      val after = chain.trie.stateRoot
      val ranFirst = refused match
        case Refused.ByFault(fault) if !decidedBeforeRunning(fault) =>
          Some(
            where + ", which the case refuses with blocks after it, was refused by " + fault.toString +
              " after it ran, and the blocks after it need the state before it, which this runner does not keep"
          )
        case _ => None
      val moved = Option.when(after != before)(
        "refusing " + where + " moved the world from root " + before.toString + " to " + after.toString +
          ", and the blocks after it run on the state before it"
      )
      val reasons = ranFirst.toVector ++ moved.toVector
      if reasons.isEmpty then Right(()) else Left(BlockchainVerdict.Diverged(reasons))
    }

  /** Whether `fault` is decided before the block runs, which is the order
    * [[org.fukuii.consensus.BlockValidator]] states for each.
    *
    * Exhaustive, so a fault added there is placed here rather than defaulted
    * into either answer.
    */
  private def decidedBeforeRunning(fault: BlockFault): Boolean = fault match
    case BlockFault.ParentHashMismatch(_, _)       => true
    case BlockFault.Header(_)                      => true
    case BlockFault.OmmersHashMismatch(_, _)       => true
    case BlockFault.TransactionsRootMismatch(_, _) => true
    case BlockFault.WithdrawalsMissing             => true
    case BlockFault.WithdrawalsUnexpected          => true
    case BlockFault.WithdrawalsRootMismatch(_, _)  => true
    case BlockFault.BlobGasUsedMismatch(_, _)      => true
    case BlockFault.TransactionRefused(_)          => false
    case BlockFault.GasUsedMismatch(_, _)          => false
    case BlockFault.LogsBloomMismatch(_, _)        => false
    case BlockFault.ReceiptsRootMismatch(_, _)     => false
    case BlockFault.StateRootMismatch(_, _)        => false

  /** A block the case refuses, read as the case says it reads and refused under
    * a name the case states.
    *
    * ==Its encoding is held to the case's decoded form before anything runs==
    *
    * Where the case publishes the refused block's decoded header, the bytes must
    * decode to a header hashing to it, exactly as a block the case accepts must;
    * otherwise a refusal could agree about a block this build read differently.
    *
    * ==Where it publishes none, there is no hash to check, and the name decides==
    *
    * A block this build decodes is run and refused by name, as any other is. A
    * block it cannot decode is refused only under a stated name whose rule is
    * that the structure does not decode --
    * [[BlockRefusalVocabulary.satisfiedByUndecodable]] states which, and why the
    * name is compared where the field does not compare one.
    */
  private def refusal(
      rlp: Bytes,
      expected: ExpectedRejection,
      decodedHash: Option[Hash],
      index: Int,
      network: FixtureNetwork,
      filledBy: FillingTool,
      chain: Chain
  ): Step[Refused] =
    val where = "block " + index.toString
    decoded(rlp) match
      case Left(error) =>
        val failure = BlockRefusalVocabulary.failureOf(rlp.toIArray, error)
        if BlockRefusalVocabulary.satisfiedByUndecodable(expected, failure, filledBy) then Right(Refused.Undecodable)
        else
          Left(
            diverged(
              where + " does not decode (" + error.toString + "), where the case refuses it as " + expected.describe
            )
          )
      case Right(block) =>
        decodedHash match
          case Some(stated) if stated != block.hash =>
            Left(diverged(where + " hashes to " + block.hash.toString + ", the case states " + stated.toString))
          case _ => refusedByValidator(block, index, expected, network, filledBy, chain)

  private def refusedByValidator(
      block: Block,
      index: Int,
      expected: ExpectedRejection,
      network: FixtureNetwork,
      filledBy: FillingTool,
      chain: Chain
  ): Step[Refused] =
    val where = "block " + index.toString
    val rules = network.schedule.at(block.header.number, block.header.timestamp)
    validated(block, rules, network, chain) match
      case BlockVerdict.Valid(_) =>
        Left(diverged(where + " was accepted, where the case refuses it as " + expected.describe))
      case BlockVerdict.Invalid(fault) =>
        if BlockRefusalVocabulary.satisfies(expected, fault, block.header, filledBy) then Right(Refused.ByFault(fault))
        else
          fault match
            case BlockFault.Header(first) =>
              alsoBroken(block, rules, first, expected, where, network, filledBy, chain)
            case _ => Left(diverged(refusedOtherwise(where, fault.toString, expected, filledBy)))
      case BlockVerdict.Undecided(rule) =>
        Left(BlockchainVerdict.Undecided(index, rule))

  /** A block refused first under a header rule the case does not name, agreeing
    * where it also breaks a shared header rule the case does name.
    *
    * The rules are resolved through the engine exactly as
    * [[org.fukuii.consensus.BlockValidator.validate]] resolves them, and the
    * first fault listed must be the one the validator reported -- or, where no
    * shared rule is broken, the engine's own answer must be. A block failing
    * that has been refused for a reason these rules do not reproduce, and it
    * diverges saying so rather than being compared further.
    */
  private def alsoBroken(
      block: Block,
      rules: UpgradeRules,
      first: HeaderFault,
      expected: ExpectedRejection,
      where: String,
      network: FixtureNetwork,
      filledBy: FillingTool,
      chain: Chain
  ): Step[Refused] =
    val running = Resolved(block.header, network.engine.rulesFrom(rules))
    val parent = chain.head.copy(rules = network.engine.rulesFrom(chain.head.rules))
    val shared = HeaderValidator.faults(running, parent)
    val reproduced =
      if shared.nonEmpty then shared.headOption else network.engine.validateHeader(running, parent).left.toOption
    if !reproduced.contains(first) then
      Left(
        diverged(
          where + " was refused as " + first.toString + ", which the header rules it runs reproduce as " +
            reproduced.fold("no fault")(_.toString)
        )
      )
    else if shared
        .drop(1)
        .exists(also => BlockRefusalVocabulary.satisfies(expected, BlockFault.Header(also), block.header, filledBy))
    then Right(Refused.ByFault(BlockFault.Header(first)))
    else Left(diverged(refusedOtherwise(where, BlockFault.Header(first).toString, expected, filledBy)))

  private def refusedOtherwise(
      where: String,
      fault: String,
      expected: ExpectedRejection,
      filledBy: FillingTool
  ): String =
    val unmapped = BlockRefusalVocabulary.unmapped(expected, filledBy)
    val note =
      if unmapped.isEmpty then ""
      else "; this build's vocabulary names none of " + unmapped.toVector.sorted.mkString(", ")
    where + " was refused as " + fault + ", where the case refuses it as " + expected.describe + note
