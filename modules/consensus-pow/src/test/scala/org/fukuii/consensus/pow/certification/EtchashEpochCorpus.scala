package org.fukuii.consensus.pow.certification

import io.circe.Json

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.Hash
import org.fukuii.consensus.pow.{Ethash, ProofOfWorkEngine}

/** One height, and the epoch-derived quantities a seal at it is checked
  * against.
  *
  * @param epochStartBlock
  *   the epoch's first block. Derivable from the other two, and carried so the
  *   corpus's `epoch * length + 1` convention is compared against this build's
  *   rather than assumed to match -- the offset is what the seed's iteration
  *   count is taken over, so a build disagreeing about it produces a
  *   well-formed seed for the wrong epoch.
  */
final case class EtchashEpochVector(
    block: BigInt,
    epochLength: BigInt,
    epoch: BigInt,
    epochStartBlock: BigInt,
    seedHash: Hash,
    cacheSizeBytes: Long,
    datasetSizeBytes: Long
)

/** The identity that makes the seed chain continue across the activation.
  *
  * @param equivalentLegacyEpoch
  *   the epoch under the original length whose seed this one takes. It is twice
  *   the post-fork epoch, and the corpus records that this is not a collision:
  *   every such legacy epoch begins at or after the activation, so no legacy
  *   twin is ever reached on this chain.
  */
final case class SeedContinuityVector(
    postForkEpoch: BigInt,
    postForkEpochStartBlock: BigInt,
    equivalentLegacyEpoch: BigInt,
    seedHash: Hash
)

/** The seed a build dividing by the wrong length produces, published beside the
  * right one.
  *
  * The corpus states this so that a consumer landing on the wrong value knows
  * which mistake it made rather than only that it was wrong. It is the negative
  * control this tier needs and could not otherwise construct: the wrong seed is
  * a real seed already used earlier on the same chain, so the mistake generates
  * a valid dataset for the wrong epoch instead of failing.
  */
final case class WrongSeedVector(postForkEpoch: BigInt, wrongSeedHash: Hash, correctSeedHash: Hash)

object EtchashEpochVector:

  private val Root: String = "etchashEpochSchedule"

  def decodeFile(
      path: String,
      contents: String
  ): Either[String, (Vector[EtchashEpochVector], Vector[SeedContinuityVector], Vector[WrongSeedVector])] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap { json =>
        val root = json.hcursor.downField(Root)
        for
          schedule <- each(path, root.downField("vectors").focus, decodeVector)
          continuity <- each(path, root.downField("seedContinuity").downField("vectors").focus, decodeContinuity)
          wrong <- each(
            path,
            root.downField("seedContinuity").downField("wrongImplementation").downField("vectors").focus,
            decodeWrong
          )
        yield (schedule, continuity, wrong)
      }

  private def each[A](
      path: String,
      json: Option[Json],
      decode: (String, Json) => Either[String, A]
  ): Either[String, Vector[A]] =
    json.flatMap(_.asArray).toRight(path + ": expected an array of vectors").flatMap { entries =>
      entries.zipWithIndex.foldLeft(Right(Vector.empty): Either[String, Vector[A]]) {
        case (Left(error), _)            => Left(error)
        case (Right(sofar), (entry, at)) => decode(path + " vector " + at.toString, entry).map(sofar :+ _)
      }
    }

  private def decodeVector(name: String, json: Json): Either[String, EtchashEpochVector] =
    for
      block <- FixtureValues.quantityAt(json, "block").left.map(name + ": " + _)
      length <- FixtureValues.quantityAt(json, "epochLength").left.map(name + ": " + _)
      epoch <- FixtureValues.quantityAt(json, "epoch").left.map(name + ": " + _)
      start <- FixtureValues.quantityAt(json, "epochStartBlock").left.map(name + ": " + _)
      seed <- seedAt(name, json, "seedHash")
      cache <- FixtureValues.quantityAt(json, "cacheSizeBytes").left.map(name + ": " + _)
      dataset <- FixtureValues.quantityAt(json, "datasetSizeBytes").left.map(name + ": " + _)
    yield EtchashEpochVector(block, length, epoch, start, seed, cache.toLong, dataset.toLong)

  private def decodeContinuity(name: String, json: Json): Either[String, SeedContinuityVector] =
    for
      epoch <- FixtureValues.quantityAt(json, "postForkEpoch").left.map(name + ": " + _)
      start <- FixtureValues.quantityAt(json, "postForkEpochStartBlock").left.map(name + ": " + _)
      legacy <- FixtureValues.quantityAt(json, "equivalentLegacyEpoch").left.map(name + ": " + _)
      seed <- seedAt(name, json, "seedHash")
    yield SeedContinuityVector(epoch, start, legacy, seed)

  private def decodeWrong(name: String, json: Json): Either[String, WrongSeedVector] =
    for
      epoch <- FixtureValues.quantityAt(json, "postForkEpoch").left.map(name + ": " + _)
      wrong <- seedAt(name, json, "wrongSeedHash")
      right <- seedAt(name, json, "correctSeedHash")
    yield WrongSeedVector(epoch, wrong, right)

  private def seedAt(name: String, json: Json, field: String): Either[String, Hash] =
    FixtureValues
      .stringAt(json, field)
      .flatMap(FixtureValues.hashOf)
      .left
      .map(name + " " + field + ": " + _)

/** ECIP-1099's epoch schedule, which no published corpus states at all.
  *
  * ==This tier has no published counterpart, which is why it exists==
  *
  * `PoWTests/ethash_tests.json` is two cases at epoch zero and is byte-identical
  * across every corpus that carries it, so it is blind to the seed chain
  * iterating, to every epoch boundary, and to this proposal in every respect --
  * the fixture predates it. These vectors are the only statement of the schedule
  * available to this build.
  *
  * ==What these expectations rest on==
  *
  * The corpus names ECIP-1099 and the ethash specification as its oracle, with
  * the algorithm cross-checked against `ethereumclassic/core-geth`
  * v1.12.21-unstable @ `4185df450`. That is a weaker grounding than a tier
  * confirmed against the chain: it is a derivation from the proposal this build
  * was also written from, checked against one client. **The one thing that
  * raises it above a second copy of one computation is the wrong-seed control**,
  * which states the value a plausible misreading produces, so agreement here is
  * agreement against a stated alternative rather than against nothing.
  */
object EtchashEpochCorpus:

  val Corpus: String = "fukuii-tests ethereumclassic/mainnet pow/etchash_epoch_schedule"

  private def file(under: Path): Path =
    NetworkFixtureCorpus.classicMainnet(under).resolve("pow/etchash_epoch_schedule.json")

  /** The height this chain doubles its epoch length at.
    *
    * `ECIP1099FBlock` in `ethereumclassic/core-geth` @ `4185df450`'s
    * `ClassicChainConfig`, and the height ECIP-1099 states for this network.
    * Stated here rather than read off a schedule, so a disagreement is between
    * this file and the chain rather than inside one loop.
    */
  val Activation: BigInt = BigInt(11700000)

  private val engine: ProofOfWorkEngine = ProofOfWorkEngine(ecip1099Activation = Some(Activation))

  /** An engine that never doubles its epoch length, which is what a network
    * declining the proposal runs.
    */
  private val withoutProposal: ProofOfWorkEngine = ProofOfWorkEngine()

  /** How many figures each height pins. */
  private val FiguresPerVector: Int = 6

  lazy val report: Option[CorpusReport] = NetworkFixtureCorpus.root.map(root => assemble(file(root), engine))

  /** The same heights under an engine that never adopts the proposal. */
  lazy val withoutActivation: Option[CorpusReport] =
    NetworkFixtureCorpus.root.map(root => assemble(file(root), withoutProposal))

  private lazy val decoded
      : Option[Either[String, (Vector[EtchashEpochVector], Vector[SeedContinuityVector], Vector[WrongSeedVector])]] =
    NetworkFixtureCorpus.root.map { root =>
      val path = file(root)
      FixtureCorpus.read(path).flatMap(EtchashEpochVector.decodeFile(path.getFileName.toString, _))
    }

  private[certification] lazy val continuity: Vector[SeedContinuityVector] =
    decoded.flatMap(_.toOption).map(_._2).getOrElse(Vector.empty)

  private[certification] lazy val wrongSeeds: Vector[WrongSeedVector] =
    decoded.flatMap(_.toOption).map(_._3).getOrElse(Vector.empty)

  private[certification] lazy val heights: Vector[EtchashEpochVector] =
    decoded.flatMap(_.toOption).map(_._1).getOrElse(Vector.empty)

  /** Figures pinned across the schedule half of the tier. */
  private[certification] def figuresAsserted: Int = heights.length * FiguresPerVector

  private def assemble(path: Path, settling: ProofOfWorkEngine): CorpusReport =
    decoded match
      case None              => CorpusReport(Corpus, 0, Vector.empty)
      case Some(Left(error)) =>
        CorpusReport(
          Corpus,
          0,
          Vector(CaseOutcome(path.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        )
      case Some(Right((schedule, _, _))) => CorpusReport(Corpus, 1, schedule.map(outcomeOf(_, settling)))

  private[certification] def outcomeOf(vector: EtchashEpochVector, settling: ProofOfWorkEngine): CaseOutcome =
    val verdict =
      try
        val length = Ethash.epochLengthAt(vector.block, settling.ecip1099Activation)
        val epoch = settling.epochOf(vector.block)
        val reasons =
          Vector(
            disagreement("epochLength", length, vector.epochLength),
            disagreement("epoch", epoch, vector.epoch),
            disagreement("epochStartBlock", epoch * length + 1, vector.epochStartBlock),
            disagreement("cacheSizeBytes", BigInt(Ethash.cacheSize(epoch)), BigInt(vector.cacheSizeBytes)),
            disagreement("datasetSizeBytes", BigInt(Ethash.datasetSize(epoch)), BigInt(vector.datasetSizeBytes)),
            if Ethash.seedFor(epoch, length) == vector.seedHash then None
            else
              Some(
                "seedHash: answered " + Ethash.seedFor(epoch, length).toHex + " rather than " + vector.seedHash.toHex
              )
          ).flatten
        if reasons.isEmpty then Verdict.Agreed else Verdict.Diverged(reasons)
      catch
        case NonFatal(cause) => Verdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    CaseOutcome("block " + vector.block.toString, verdict)

  /** The seed this build answers for a post-activation epoch. */
  private[certification] def seedAtPostForkEpoch(epoch: BigInt): Hash =
    Ethash.seedFor(epoch, Ethash.Ecip1099EpochLength)

  /** The seed the original epoch length gives for the same epoch number.
    *
    * This is what a build dividing the epoch's first block by the length in
    * force computes, and it is a seed already used earlier on this chain. It is
    * derived here rather than taken from the corpus so that the comparison is
    * between two answers this build can produce, not between the corpus and
    * itself.
    */
  private[certification] def seedAtLegacyEpoch(epoch: BigInt): Hash = Ethash.seedFor(epoch, Ethash.EpochLength)

  private def disagreement(field: String, answered: BigInt, stated: BigInt): Option[String] =
    if answered == stated then None
    else Some(field + ": answered " + answered.toString + " rather than " + stated.toString)
