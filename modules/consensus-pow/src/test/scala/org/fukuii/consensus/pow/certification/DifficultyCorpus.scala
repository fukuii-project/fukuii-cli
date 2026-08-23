package org.fukuii.consensus.pow.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.{Bytes, UInt256, UInt64}
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment}
import org.fukuii.consensus.pow.ProofOfWorkEngine
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{BlockHeader, BlockNonce, Bloom, Seal}

/** The published `DifficultyTests` corpus, run once and reported as counts.
  *
  * ==What a fork name resolves to here is the harness's belief, not the
  * schedule's==
  *
  * A file states its cases under a fork's name, and [[rulesFor]] states which
  * algorithm, bound divisor and delay this harness believes that name means.
  * Reading those off a network's schedule instead would close the loop --
  * the harness would ask the schedule what a fork resolves to and then check the
  * corpus against the schedule's own answer, which is true of any schedule
  * whatsoever. The same reasoning `CertificationCorpora` gives for stating its
  * heights rather than reading them back.
  *
  * **Only two of the names below correspond to rule sets this build composes.**
  * The corpus reaches five further forks whose proposals `modules/chainspec`
  * does not model, and their parameters are stated here so the algorithm can be
  * certified against every published case rather than against the quarter of
  * them this build's schedules currently reach. Certifying the ALGORITHM and
  * certifying a network's SCHEDULE are different claims, and this file makes
  * only the first.
  *
  * ==Computed once, because running them is the expensive part==
  *
  * Every spec asserting something about coverage asserts it about the same run.
  */
object DifficultyCorpus:

  /** Where the tier sits under the corpus root. */
  val Corpus: String = "ethereum/tests DifficultyTests"

  private def directory(under: Path): Path = under.resolve("ethereum/tests/DifficultyTests")

  /** The engine under certification.
    *
    * The era ladder is absent because difficulty does not read it: ECIP-1017
    * settles an emission and nothing about a target. An engine carrying one
    * would answer identically here, and choosing the one that cannot is what
    * keeps that independence testable rather than assumed.
    */
  private val engine: ProofOfWorkEngine = ProofOfWorkEngine()

  /** What this harness believes each published fork name settles.
    *
    * The delays are read from `ethereum/execution-specs` @ `ccaaaba58`, whose
    * fork modules declare `BOMB_DELAY_BLOCKS` directly, and agree with
    * `besu-eth/besu-etc` @ `eb4248c99`, which states the same six figures one
    * lower as a `*_FAKE_BLOCK_OFFSET` applied to the parent rather than to the
    * block. Two decompositions, one set of values.
    *
    * **`Berlin` is the name the corpus files its EIP-2384 cases under**, and the
    * directory is `dfEIP2384`. The proposal moved the delay to 9,000,000 at Muir
    * Glacier and Berlin inherited it unchanged, so the two names carry one
    * figure and the fork key is what the reader dispatches on.
    */
  private[certification] def rulesFor(fork: String): Option[ConsensusRules] =
    val base = ConsensusRules.Unrewarded
    fork match
      case "Frontier"       => Some(base)
      case "Homestead"      => Some(base.copy(difficultyAdjustment = DifficultyAdjustment.Eip2))
      case "Byzantium"      => Some(eip100(base, 3000000))
      case "Constantinople" => Some(eip100(base, 5000000))
      case "Berlin"         => Some(eip100(base, 9000000))
      case "ArrowGlacier"   => Some(eip100(base, 10700000))
      case "GrayGlacier"    => Some(eip100(base, 11400000))
      case _                => None

  private def eip100(base: ConsensusRules, delay: Long): ConsensusRules =
    base.copy(difficultyAdjustment = DifficultyAdjustment.Eip100, difficultyBombDelay = BigInt(delay))

  /** Every report, or nothing at all when the corpus cannot be located.
    *
    * `None` rather than an empty report, for the reason `CertificationCorpora`
    * gives: a harness answering with empty reports is indistinguishable from one
    * that found nothing wrong, and the first case in
    * [[DifficultyCertificationSpec]] is what catches it loudly.
    */
  lazy val report: Option[CorpusReport] = FixtureCorpus.root.map(root => assemble(directory(root)))

  private def assemble(under: Path): CorpusReport =
    val files = FixtureCorpus.jsonFilesUnder(under)
    val outcomes = files.flatMap { file =>
      FixtureCorpus
        .read(file)
        .flatMap(DifficultyFixture.decodeFile(file.getFileName.toString, _)) match
        case Left(error) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        case Right(fixtures) => fixtures.map(outcomeOf)
    }
    CorpusReport(Corpus, files.length, outcomes)

  /** What running one case established, with a throw recorded as a divergence.
    *
    * A skip means there was nothing here to compare; a throw means the rule
    * broke on something there was. Counting the second as the first would let an
    * engine that threw on every case report as entirely skipped and therefore
    * green -- the boundary `CertificationCorpora.outcomeOf` already draws, drawn
    * again here because the two harnesses share no runner.
    */
  private[certification] def outcomeOf(fixture: DifficultyFixture): CaseOutcome =
    val verdict =
      try
        rulesFor(fixture.fork) match
          case None        => Verdict.Diverged(Vector("no rules are stated for fork " + fixture.fork))
          case Some(rules) =>
            val answered = engine.difficulty(rules, parentOf(fixture), fixture.parentHasOmmers, timestampOf(fixture))
            if answered.toBigInt == fixture.expected then Verdict.Agreed
            else
              Verdict.Diverged(
                Vector("answered " + answered.toBigInt.toString + " rather than " + fixture.expected.toString)
              )
      catch
        case NonFatal(cause) => Verdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    CaseOutcome(fixture.name, verdict)

  private def timestampOf(fixture: DifficultyFixture): UInt64 =
    UInt64
      .fromBigInt(fixture.timestamp)
      .getOrElse(throw new IllegalStateException("a timestamp wider than a header can carry: " + fixture.timestamp))

  /** The parent the fixture describes, as the header the rule reads.
    *
    * ==The fixture names the block and the rule takes its parent==
    *
    * A case states `currentBlockNumber`, which is the block being settled, so
    * the parent sits one below it. That is the same arithmetic every surveyed
    * client performs in the other direction -- `ethereum/go-ethereum-pow` @
    * `v1.10.26` computes `periodCount := parent.Number + 1` -- and getting it
    * backwards moves the exponential term by one whole period at every boundary.
    *
    * **Every field the rule does not read is left at its zero rather than
    * invented.** A header is fourteen fields and this case is six scalars; a
    * plausible value in a field nothing reads would suggest the corpus stated
    * one.
    */
  private def parentOf(fixture: DifficultyFixture): BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = EvmFixtures.hash(0),
      beneficiary = EvmFixtures.address(0),
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = UInt256
        .fromBigInt(fixture.parentDifficulty)
        .getOrElse(throw new IllegalStateException("a parent difficulty wider than a header can carry")),
      number = UInt64
        .fromBigInt(fixture.blockNumber - 1)
        .getOrElse(throw new IllegalStateException("a block number a header cannot carry: " + fixture.blockNumber)),
      gasLimit = UInt64.Zero,
      gasUsed = UInt64.Zero,
      timestamp = UInt64
        .fromBigInt(fixture.parentTimestamp)
        .getOrElse(throw new IllegalStateException("a parent timestamp wider than a header can carry")),
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(mixHash = EvmFixtures.hash(0), nonce = BlockNonce.Zero)
    )
