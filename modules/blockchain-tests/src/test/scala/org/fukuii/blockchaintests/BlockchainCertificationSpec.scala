package org.fukuii.blockchaintests

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.Hash
import org.fukuii.evm.EvmFixtures
import org.fukuii.evm.fixtures.{ExpectedRejection, FixtureCorpus}

/** The published `blockchain_tests` tier, run through this build's block
  * validator and pinned as counts.
  *
  * ==Every count is asserted, in both directions==
  *
  * A harness that narrowed its own input agrees with everything left, and every
  * figure it reports stays plausible. So the files read, the cases found, the
  * blocks the corpus states, the blocks this build accepted, the refusals it
  * agreed with, and every case it did not agree with -- diverged, undecided or
  * skipped -- are each pinned exactly, and a change that moves any of them fails
  * an ordinary run whichever way it moves.
  *
  * ==The stated counts are held to counts this reader did not produce==
  *
  * Files, cases, blocks the corpus states valid and blocks it states refused
  * were also counted over every file of the label by a second reader sharing no
  * code with this one, with its own JSON parser, and it reports the same four
  * figures. **Another tier in this build corroborates them from the other
  * side**: the same release's engine form of this label is pinned by
  * `EnginePayloadCertificationSpec` at 150 files and 4,997 payloads, one payload
  * per block -- and 4,709 accepted plus 288 refused is 4,997.
  *
  * ==Each count is bound before it is asserted==
  *
  * An assertion over an expression renders the values inside it when it fails,
  * and a report's outcomes run to thousands of cases. So each figure is taken
  * into a local first, and the failure carries the report's own description,
  * which is bounded.
  *
  * **A missing corpus fails rather than cancels**, and every accessor below is a
  * `def` for the reason `EnginePayloadCertificationSpec` records: a `fail` run
  * from a `val` aborts the suite, which reads as tests removed.
  */
class BlockchainCertificationSpec extends AnyFlatSpec:

  /** Files under each label. */
  private val Files: Map[String, Int] = Map("for_paris" -> 150)

  /** Cases under each label, which is not the number of blocks: a case states
    * a chain, and most state one block.
    */
  private val Cases: Map[String, Int] = Map("for_paris" -> 3510)

  /** Blocks the corpus states each case accepts, summed over the label. */
  private val BlocksStatedValid: Map[String, Int] = Map("for_paris" -> 4709)

  /** Blocks the corpus states each case refuses. In this label every one is its
    * case's last and no case states more than one, which is the precondition
    * the runner checks per case rather than a property of the release.
    */
  private val RefusalsStated: Map[String, Int] = Map("for_paris" -> 288)

  /** Blocks this build accepted. */
  private val BlocksAccepted: Map[String, Int] = Map("for_paris" -> 4709)

  /** Cases this build agreed with in full. */
  private val Agreed: Map[String, Int] = Map("for_paris" -> 3510)

  /** Blocks this build refused under a name their case states, counted where
    * each refusal happened.
    */
  private val RefusalsAgreed: Map[String, Int] = Map("for_paris" -> 288)

  /** Cases this build disagreed with. */
  private val Diverged: Map[String, Int] = Map("for_paris" -> 0)

  /** Cases a block left undecided, by the rule it reached. */
  private val Undecided: Map[String, Map[String, Int]] = Map("for_paris" -> Map.empty)

  /** Cases not run, by reason. */
  private val Skipped: Map[String, Map[String, Int]] = Map("for_paris" -> Map.empty)

  private def reports: Vector[BlockchainReport] =
    BlockchainCorpus.reports.getOrElse(
      fail(
        "the published corpus was not found: set " + FixtureCorpus.RootVariable + " or write " +
          FixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  private def reportFor(label: String): BlockchainReport =
    reports.find(_.corpus == label).getOrElse(fail("no report for " + label + ": " + reports.map(_.corpus).toString))

  private def paris: BlockchainReport = reportFor("for_paris")

  /** A published case from a named file under the label, as the corpus reader
    * decodes it.
    */
  private def fromCorpus(relative: String, name: String): BlockchainFixture =
    val root = FixtureCorpus.root.getOrElse(fail("the published corpus was not found"))
    BlockchainCorpus
      .casesIn(root, "for_paris", relative)
      .fold(error => fail(error), identity)
      .fixtures
      .find(_.name == name)
      .getOrElse(fail("no case " + name + " in " + relative))

  private val Wrong: Hash = EvmFixtures.hash(0x5a)

  private val UnpublishedName: ExpectedRejection = ExpectedRejection(Set("BlockException.NOT_A_PUBLISHED_NAME"))

  /** The refusal the gas-limit case states, with its names replaced. */
  private def restated(refusal: BlockchainFixture, names: ExpectedRejection): BlockchainFixture =
    refusal.copy(blocks = refusal.blocks.map {
      case StatedBlock.Invalid(rlp, _, decoded) => StatedBlock.Invalid(rlp, names, decoded)
      case valid @ StatedBlock.Valid(_, _)      => valid
    })

  /** Eight published cases read from the label, each with one defect seeded, run
    * through the runner and reported through the report that pins the label.
    */
  private def controls: BlockchainReport =
    val chain = fromCorpus("frontier/opcodes/blockhash/genesis_hash_available.json", PublishedParisCases.BlockHashCase)
    val refusal =
      fromCorpus("frontier/validation/header/block_gas_limit_below_minimum.json", PublishedParisCases.GasLimitCase)
    val (listed, account) = chain.postState.toVector.minBy(_._1.toHex)
    val (firstAccepted, firstHash) =
      chain.blocks.collectFirst { case StatedBlock.Valid(rlp, hash) => (rlp, hash) }.getOrElse(fail("no block"))
    val config = chain.config.getOrElse(fail("the case states no config"))
    val seeded = Vector(
      "a wrong last block hash" -> chain.copy(lastBlockHash = Wrong),
      "a wrong post-state account" ->
        chain.copy(postState = chain.postState.updated(listed, account.copy(balance = account.balance + 1))),
      "a refusal under a name no vocabulary holds" -> restated(refusal, UnpublishedName),
      "a refusal under a known name for another rule" ->
        restated(refusal, ExpectedRejection(Set("BlockException.INVALID_BASEFEE_PER_GAS"))),
      "a refused block that is not last" -> refusal.copy(blocks = refusal.blocks ++ refusal.blocks),
      "a block the case refuses, which this build accepts" ->
        chain.copy(
          blocks = Vector(
            StatedBlock
              .Invalid(firstAccepted, ExpectedRejection(Set("BlockException.INVALID_GASLIMIT")), Some(firstHash))
          ),
          lastBlockHash = chain.genesisHash,
          postState = chain.pre
        ),
      "a refused block whose decoded form hashes otherwise" ->
        refusal.copy(blocks = refusal.blocks.map {
          case StatedBlock.Invalid(rlp, names, _) => StatedBlock.Invalid(rlp, names, Some(Wrong))
          case valid @ StatedBlock.Valid(_, _)    => valid
        }),
      "a config key this runner does not compare" ->
        chain.copy(config = Some(config.copy(otherKeys = Vector("blobSchedule"))))
    )
    BlockchainReport(
      "controls",
      0,
      seeded.map((name, fixture) => BlockchainOutcome(name, StatedShape.of(fixture), BlockchainRunner.run(fixture)))
    )

  "the published block tier" should "report one corpus per label" in {
    val labels = reports.map(_.corpus)
    assert(labels == BlockchainCorpus.Labels, labels.toString)
  }

  it should "read every file under Paris" in {
    val read = paris.filesRead
    assert(read == Files("for_paris"), paris.describe)
  }

  it should "find every case under Paris" in {
    val found = paris.cases
    assert(found == Cases("for_paris"), paris.describe)
  }

  it should "read the blocks the Paris corpus states, as the independent census counts them" in {
    val valid = paris.blocksStatedValid
    val refused = paris.refusalsStated
    assert(valid == BlocksStatedValid("for_paris") && refused == RefusalsStated("for_paris"), paris.describe)
  }

  it should "accept exactly the pinned number of Paris blocks" in {
    val accepted = paris.blocksAccepted
    assert(accepted == BlocksAccepted("for_paris"), paris.describe)
  }

  it should "agree with exactly the pinned number of Paris cases" in {
    val agreed = paris.agreed.length
    assert(agreed == Agreed("for_paris"), paris.describe)
  }

  it should "refuse exactly the pinned number of Paris blocks under a name their case states" in {
    val refused = paris.refusalsAgreed
    assert(refused == RefusalsAgreed("for_paris"), paris.describe)
  }

  it should "diverge on exactly the pinned number of Paris cases" in {
    val diverged = paris.diverged.length
    assert(diverged == Diverged("for_paris"), paris.describe)
  }

  it should "leave exactly the pinned Paris cases undecided, by rule" in {
    val undecided = paris.undecidedByRule
    assert(undecided == Undecided("for_paris"), paris.describe)
  }

  it should "skip exactly the pinned Paris cases, by reason" in {
    val skipped = paris.skippedByReason
    assert(skipped == Skipped("for_paris"), paris.describe)
  }

  it should "account for every Paris case under exactly one outcome" in {
    val report = paris
    val accounted =
      report.agreed.length + report.diverged.length + report.undecidedByRule.values.sum +
        report.skippedByReason.values.sum
    assert(accounted == report.cases, report.describe)
  }

  /** ==The calibration, run rather than recorded==
    *
    * Every assertion above is satisfied by a runner that agrees, and a runner
    * that had stopped comparing would report the same counts. So eight published
    * cases, read from the label by the same reader, are each run with one defect
    * seeded, each in a different comparison the runner makes, and the same
    * report is required to count every one as a divergence and none as anything
    * else.
    * `BlockchainRunnerSpec` holds the same controls with the reason each must
    * report, beside the outcomes no published label reaches yet.
    */
  "the tier's calibration" should "count a divergence for every seeded defect in a published Paris case" in {
    val report = controls
    val seeded = report.cases
    val diverged = report.diverged.length
    val agreed = report.agreed.length
    assert(seeded == 8 && diverged == 8 && agreed == 0, report.describe)
  }
