package org.fukuii.consensus.pow.certification

import io.circe.Json

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.Address
import org.fukuii.consensus.pow.EthashEngine
import org.fukuii.evm.EvmFixtures

/** One height, and every figure the emission pays at it.
  *
  * ==Stated as issuance rather than as a formula==
  *
  * A vector gives what a block of each shape credits in total, so a harness
  * settles a block and adds up what it wrote. The alternative -- checking the
  * reward against a reimplementation of the ladder -- would compare this build's
  * arithmetic with a second copy of the same arithmetic and pass whenever the
  * two were wrong together.
  *
  * @param uncleRewardDependsOnDistance
  *   whether an ommer's own miner is paid differently at different distances.
  *   The proposal's first era pays on recency and every era after it pays a flat
  *   share, so this is the one field that says which of the two rules is in
  *   force, and it is derived from the six figures rather than trusted.
  */
final case class EmissionVector(
    block: BigInt,
    winnerBaseReward: BigInt,
    includerBonusPerUncle: BigInt,
    uncleMinerRewardByDistance: Map[Int, BigInt],
    noUncles: BigInt,
    oneUncleByDistance: Map[Int, BigInt],
    twoUnclesBothAtDistance: Map[Int, BigInt],
    maxIssuance: BigInt,
    uncleRewardDependsOnDistance: Boolean
)

object EmissionVector:

  private val Root: String = "eraEmissionSchedule"

  def decodeFile(path: String, contents: String): Either[String, Vector[EmissionVector]] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap { json =>
        json.hcursor.downField(Root).downField("vectors").focus match
          case None         => Left(path + ": no " + Root + ".vectors")
          case Some(vector) =>
            vector.asArray.toRight(path + ": " + Root + ".vectors is not an array").flatMap { entries =>
              entries.zipWithIndex.foldLeft(Right(Vector.empty): Either[String, Vector[EmissionVector]]) {
                case (Left(error), _)            => Left(error)
                case (Right(sofar), (entry, at)) =>
                  decodeVector(path + " vector " + at.toString, entry).map(sofar :+ _)
              }
            }
      }

  private def decodeVector(name: String, json: Json): Either[String, EmissionVector] =
    for
      block <- FixtureValues.quantityAt(json, "block").left.map(name + ": " + _)
      winner <- FixtureValues.quantityAt(json, "winnerBaseReward").left.map(name + ": " + _)
      bonus <- FixtureValues.quantityAt(json, "includerBonusPerUncle").left.map(name + ": " + _)
      byDistance <- distances(name, json.hcursor.downField("uncleMinerRewardByDistance").focus)
      issuance <- json.hcursor.downField("totalIssuance").focus.toRight(name + ": no totalIssuance")
      alone <- FixtureValues.quantityAt(issuance, "noUncles").left.map(name + ": " + _)
      one <- distances(name, issuance.hcursor.downField("oneUncleByDistance").focus)
      two <- distances(name, issuance.hcursor.downField("twoUnclesBothAtDistance").focus)
      most <- FixtureValues.quantityAt(json, "maxIssuance").left.map(name + ": " + _)
      varies <- json.hcursor
        .downField("uncleRewardDependsOnDistance")
        .as[Boolean]
        .left
        .map(_ => name + ": uncleRewardDependsOnDistance is not a boolean")
    yield EmissionVector(block, winner, bonus, byDistance, alone, one, two, most, varies)

  /** A map keyed by ommer distance, whose keys are decimal strings. */
  private def distances(name: String, json: Option[Json]): Either[String, Map[Int, BigInt]] =
    json.flatMap(_.asObject).toRight(name + ": expected an object keyed by distance").flatMap { entries =>
      entries.toVector.foldLeft(Right(Map.empty): Either[String, Map[Int, BigInt]]) {
        case (Left(error), _)             => Left(error)
        case (Right(sofar), (key, value)) =>
          for
            distance <- key.toIntOption.toRight(name + ": " + key + " is not a distance")
            amount <- FixtureValues
              .quantity(value.asString.getOrElse(""))
              .left
              .map(name + " distance " + key + ": " + _)
          yield sofar.updated(distance, amount)
      }
    }

/** The emission ladder against every height this chain's own corpus states.
  *
  * ==What these expectations rest on==
  *
  * The fixture's oracle is ECIP-1017 computed independently and cross-checked
  * against `core-geth`'s own reward vectors at eleven heights for the winner and
  * eleven for the ommer, and it records that its own values were confirmed
  * against mainnet -- forty-two zero-transaction zero-ommer blocks read as miner
  * balance deltas from archive state. So the strong part of this tier is
  * observed rather than merely derived.
  *
  * **One figure in it is unobservable and stated deliberately.** The fixture
  * records that a single truncating division and an era-by-era reduction agree
  * through era 20 and part from era 21, which begins at block 105,000,001 -- a
  * height no chain has reached. That case is pinned here because nothing running
  * can settle it, which is the opposite of a case that does not matter.
  */
object EmissionCorpus:

  val Corpus: String = "fukuii-tests ethereumclassic/mainnet blocks/era_emission_schedule"

  private def file(under: Path): Path =
    NetworkFixtureCorpus.classicMainnet(under).resolve("blocks/era_emission_schedule.json")

  /** The distances an ommer may be included at, which the fixture states and
    * every vector keys its figures by.
    */
  private val Distances: Seq[Int] = 1 to 6

  private val beneficiary: Address = EvmFixtures.address(0xa1)
  private val firstOmmerMiner: Address = EvmFixtures.address(0xb1)
  private val secondOmmerMiner: Address = EvmFixtures.address(0xb2)

  lazy val report: Option[CorpusReport] =
    NetworkFixtureCorpus.root.map(root => assemble(file(root), ClassicRewardHarness.engine))

  /** The same run under an engine that never steps its emission down. */
  lazy val withoutLadder: Option[CorpusReport] =
    NetworkFixtureCorpus.root.map(root => assemble(file(root), ClassicRewardHarness.withoutLadder))

  private def assemble(path: Path, engine: EthashEngine): CorpusReport =
    FixtureCorpus.read(path).flatMap(EmissionVector.decodeFile(path.getFileName.toString, _)) match
      case Left(error) =>
        CorpusReport(
          Corpus,
          0,
          Vector(CaseOutcome(path.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        )
      case Right(vectors) => CorpusReport(Corpus, 1, vectors.map(outcomeOf(_, engine)))

  /** Every figure one vector states, settled as a real block and read back.
    *
    * A vector is one case and its reasons are collected rather than short-cut,
    * so a divergence names every figure that moved instead of the first.
    */
  private[certification] def outcomeOf(vector: EmissionVector, engine: EthashEngine): CaseOutcome =
    val verdict =
      try
        val reachable = realizableDistances(vector.block)
        val alone = ClassicRewardHarness.credits(engine, beneficiary, vector.block, Seq.empty)
        val one = reachable.map(distance => distance -> creditsWithOne(engine, vector.block, distance)).toMap
        val two = reachable.map(distance => distance -> creditsWithTwo(engine, vector.block, distance)).toMap
        val ommerShare = one.view.mapValues(_.getOrElse(firstOmmerMiner, BigInt(0))).toMap
        val acrossEveryDistance =
          if reachable.length < Distances.length then Vector.empty
          else
            Vector(
              disagreement("maxIssuance", two.values.map(total).max, vector.maxIssuance),
              disagreement(
                "uncleRewardDependsOnDistance",
                ommerShare.values.toSet.size > 1,
                vector.uncleRewardDependsOnDistance
              )
            ).flatten
        val reasons =
          Vector(
            disagreement("winnerBaseReward", alone.getOrElse(beneficiary, BigInt(0)), vector.winnerBaseReward),
            disagreement("totalIssuance.noUncles", total(alone), vector.noUncles)
          ).flatten ++
            one
              .get(1)
              .flatMap(credits =>
                disagreement(
                  "includerBonusPerUncle",
                  credits.getOrElse(beneficiary, BigInt(0)) - vector.winnerBaseReward,
                  vector.includerBonusPerUncle
                )
              ) ++
            acrossEveryDistance ++
            reachable.flatMap(distance =>
              Vector(
                disagreement(
                  "uncleMinerRewardByDistance." + distance.toString,
                  ommerShare(distance),
                  vector.uncleMinerRewardByDistance(distance)
                ),
                disagreement(
                  "totalIssuance.oneUncleByDistance." + distance.toString,
                  total(one(distance)),
                  vector.oneUncleByDistance(distance)
                ),
                disagreement(
                  "totalIssuance.twoUnclesBothAtDistance." + distance.toString,
                  total(two(distance)),
                  vector.twoUnclesBothAtDistance(distance)
                )
              ).flatten
            )
        if reasons.isEmpty then Verdict.Agreed else Verdict.Diverged(reasons)
      catch
        case NonFatal(cause) => Verdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    CaseOutcome("block " + vector.block.toString, verdict)

  /** The distances a block at this height could actually have included an ommer
    * at.
    *
    * ==A vector states an era's schedule; a settled block is a stronger claim==
    *
    * The corpus is explicit that its figures are emission values rather than
    * block-level transitions, so it states all six distances at every height
    * including the first. Reading them back off a settled block is the stronger
    * instrument and buys that strength with one limit: an ommer below genesis
    * is not a block, so a height under the horizon realizes only the distances
    * that reach a block which could exist.
    *
    * **The figures this excludes are certified elsewhere in the same tier
    * rather than lost.** The schedule is a function of the era alone, so every
    * distance of the first era is pinned at the other heights inside it, and
    * what the first block uniquely pins -- that the era index counts from one
    * and not from zero -- is carried by figures that need no ommer at all.
    */
  private def realizableDistances(block: BigInt): Seq[Int] = Distances.filter(distance => block - distance >= 0)

  /** How many figures a vector at this height pins.
    *
    * Stated so the tier can assert the size of what it measured: a case count
    * alone cannot show a harness that quietly stopped asserting a figure.
    */
  private[certification] def figuresAt(block: BigInt): Int =
    val reachable = realizableDistances(block).length
    val everywhere = if reachable == Distances.length then 2 else 0
    val includerBonus = if reachable >= 1 then 1 else 0
    2 + includerBonus + everywhere + 3 * reachable

  /** Figures pinned across the whole tier. */
  private[certification] def figuresAsserted: Int =
    vectors.getOrElse(Vector.empty).map(vector => figuresAt(vector.block)).sum

  private[certification] lazy val vectors: Option[Vector[EmissionVector]] =
    NetworkFixtureCorpus.root.map { root =>
      val path = file(root)
      FixtureCorpus
        .read(path)
        .flatMap(EmissionVector.decodeFile(path.getFileName.toString, _))
        .getOrElse(Vector.empty[EmissionVector])
    }

  private def creditsWithOne(engine: EthashEngine, block: BigInt, distance: Int): Map[Address, BigInt] =
    ClassicRewardHarness.credits(
      engine,
      beneficiary,
      block,
      Seq(ClassicRewardHarness.ommerAt(block - distance, firstOmmerMiner))
    )

  /** Two ommers at one distance, credited to different miners.
    *
    * Distinct miners because the total is the same either way and the
    * per-ommer figure is not: two payments to one address would report as one
    * doubled credit, which is a different claim and belongs to the tier that
    * states it.
    */
  private def creditsWithTwo(engine: EthashEngine, block: BigInt, distance: Int): Map[Address, BigInt] =
    ClassicRewardHarness.credits(
      engine,
      beneficiary,
      block,
      Seq(
        ClassicRewardHarness.ommerAt(block - distance, firstOmmerMiner),
        ClassicRewardHarness.ommerAt(block - distance, secondOmmerMiner)
      )
    )

  private def total(credits: Map[Address, BigInt]): BigInt = credits.values.sum

  private def disagreement(field: String, answered: BigInt, stated: BigInt): Option[String] =
    if answered == stated then None
    else Some(field + ": answered " + answered.toString + " rather than " + stated.toString)

  private def disagreement(field: String, answered: Boolean, stated: Boolean): Option[String] =
    if answered == stated then None
    else Some(field + ": answered " + answered.toString + " rather than " + stated.toString)
