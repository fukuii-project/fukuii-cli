package org.fukuii.consensus.pow.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.ConsensusRules
import org.fukuii.consensus.pow.EthashEngine

/** The difficulty rule against the published `BasicTests` cases that sit near
  * the floor.
  *
  * ==Why a second published tier, when `DifficultyTests` is already read==
  *
  * [[DifficultyCorpus]] reads 18,598 cases whose smallest parent difficulty is
  * 99,191,501,402,103, with none below twice the 131,072 floor. Every rule that
  * only takes effect near the floor is therefore invisible to it -- not
  * under-tested but unreachable, and a tier that could not have disagreed is not
  * evidence that it agrees. This tier is where the floor is reachable:
  * `ethereum/tests` @ `v17.2` states 120 cases in
  * `BasicTests/difficultyCustomHomestead.json`, 90 of which state a parent
  * difficulty below the floor while the exponential term is still growing. Nine
  * of those 90 are refused before evaluation, for the reason given below, so 81
  * is what this tier separates the two orders on.
  *
  * **That is the one thing this tier is for.** It adds 108 answered cases against
  * the other tier's 18,598, and its value is not the count: it is that
  * [[org.fukuii.consensus.pow.EthashEngine.difficulty]] takes the floor over the
  * sum rather than over the adjustment, which no case in the other tier can tell
  * apart.
  *
  * ==Four files in the same directory are not read, and each for a stated
  * reason==
  *
  * `BasicTests` holds five difficulty files. The four left out were measured
  * before being left out, so the reason is a finding rather than caution:
  *
  *   - `difficultyMainNetwork.json` (2,254 cases) and `difficultyRopsten.json`
  *     (2,254) state no parent difficulty below the floor at all, so they
  *     separate the two orders on nothing. Both also span this chain's
  *     graduated-rule and ommer-aware activations, and `difficultyRopsten.json`
  *     is a second network's schedule besides.
  *   - `difficulty.json` (14 cases) likewise states none below the floor, and
  *     spans an activation.
  *   - `difficultyCustomMainNetwork.json` (360 cases) does reach the floor, on
  *     243 of them. It is the one worth wiring next, and it cannot be wired the
  *     way this one is: its cases run from block 4,270,000 to 4,470,000, which
  *     straddles the height the ommer-aware rule activates at, so a runner must
  *     resolve rules per block rather than per file. [[rulesFor]] answers a fork
  *     name and a file's name is all this tier states, so that is a different
  *     harness shape and not another row.
  *
  * **Which files exist is asserted rather than assumed**, through
  * [[difficultyFilesPresent]], so a file appearing upstream is a failure here
  * instead of a silent omission.
  *
  * ==A case whose block does not follow its parent in time is skipped, not
  * diverged==
  *
  * Twelve of the 120 state a block whose timestamp equals its parent's.
  * [[org.fukuii.consensus.pow.EthashEngine.difficulty]] is stated only for a
  * block that follows its parent, and refuses one that does not; upholding that
  * is the caller's job, and this is the caller. So the twelve are declined here
  * with [[org.fukuii.evm.fixtures.SkipReason.InputRefused]] rather than run and
  * counted as a rule that threw -- a divergence there would report a defect
  * where the engine is behaving as documented.
  */
object BasicTestsDifficultyCorpus:

  /** Where the tier sits under the corpus root. */
  val Corpus: String = "ethereum/tests BasicTests difficulty"

  private def directory(under: Path): Path = under.resolve("ethereum/tests/BasicTests")

  /** Which rules this harness believes each file it reads states its cases
    * under.
    *
    * The file names no fork and neither does its directory -- unlike
    * `DifficultyTests`, where the fork is a key inside the file and a component
    * of the path. So the belief is stated here, per file, and
    * [[DifficultyCorpus.rulesFor]] is what turns the name into rules: one table
    * for what a fork name settles, read by both published tiers, rather than a
    * second transcription that can drift from the first.
    */
  private[certification] val Wired: Map[String, String] = Map("difficultyCustomHomestead.json" -> "Homestead")

  /** The engine under certification.
    *
    * No era ladder and no epoch activation, for the reason [[DifficultyCorpus]]
    * gives: difficulty reads neither, and an engine that cannot carry them is
    * what keeps that independence testable rather than assumed.
    */
  private val engine: EthashEngine = EthashEngine()

  /** What a fork name settles, which is [[DifficultyCorpus]]'s table unchanged. */
  private[certification] def rulesFor(fork: String): Option[ConsensusRules] = DifficultyCorpus.rulesFor(fork)

  /** Every report, or nothing at all when the corpus cannot be located.
    *
    * `None` rather than an empty report, for the reason [[DifficultyCorpus]]
    * gives: a harness answering with empty reports cannot be told from one that
    * found nothing wrong.
    */
  lazy val report: Option[CorpusReport] = FixtureCorpus.root.map(_ => assemble(rulesFor))

  private def assemble(resolve: String => Option[ConsensusRules]): CorpusReport =
    CorpusReport(Corpus, Wired.size, fixtures.getOrElse(Vector.empty).map(outcomeOf(_, resolve)))

  /** Every case the wired files state, as the runner sees them before any
    * verdict.
    *
    * Read by the controls, which need the same inputs the run used and must not
    * re-derive them from a second walk of the tree.
    */
  private[certification] lazy val fixtures: Option[Vector[DifficultyFixture]] =
    FixtureCorpus.root.map { root =>
      Wired.toVector.sortBy(_._1).flatMap { (file, fork) =>
        FixtureCorpus
          .read(directory(root).resolve(file))
          .flatMap(DifficultyFixture.decodeFlatFile(file, fork, _))
          .getOrElse(Vector.empty[DifficultyFixture])
      }
    }

  /** Every difficulty file the directory holds, wired or not.
    *
    * The declined four are declined on what they were measured to contain, so a
    * fifth arriving upstream -- or one of these being renamed -- must be visible
    * rather than absorbed. A harness that reads only the files it names cannot
    * notice either on its own.
    */
  private[certification] lazy val difficultyFilesPresent: Option[Vector[String]] =
    FixtureCorpus.root.map { root =>
      FixtureCorpus
        .jsonFilesUnder(directory(root))
        .map(_.getFileName.toString)
        .filter(_.startsWith("difficulty"))
        .sorted
    }

  /** What running one case established.
    *
    * @param resolve
    *   how a fork name becomes a rule set. A parameter for the reason
    *   [[ClassicDifficultyCorpus.outcomeOf]] takes one: a harness whose only
    *   ever input is the answer it expects has no reachable failing state, and
    *   reports agreement it never tested for.
    */
  private[certification] def outcomeOf(
      fixture: DifficultyFixture,
      resolve: String => Option[ConsensusRules]
  ): CaseOutcome =
    val verdict =
      if fixture.timestamp <= fixture.parentTimestamp then
        Verdict.Skipped(
          SkipReason.InputRefused(
            "block " + fixture.blockNumber.toString + " is stated at " + fixture.timestamp.toString +
              ", which does not follow its parent at " + fixture.parentTimestamp.toString
          )
        )
      else
        try
          resolve(fixture.fork) match
            case None        => Verdict.Diverged(Vector("no rules are stated for fork " + fixture.fork))
            case Some(rules) =>
              val answered =
                engine.difficulty(
                  rules,
                  DifficultyCorpus.parentOf(fixture),
                  fixture.parentHasOmmers,
                  timestampOf(fixture)
                )
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
