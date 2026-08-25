package org.fukuii.consensus.pow.certification

import io.circe.Json

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.Address
import org.fukuii.consensus.pow.EthashEngine
import org.fukuii.types.BlockHeader

/** One block shape, and every address it credits.
  *
  * ==Totals by address, which is a claim about how a credit is applied==
  *
  * A credit is an addition rather than a write, so an address paid twice by one
  * block holds the sum. The corpus states its expectations keyed by address for
  * exactly that reason and carries a vector where the ommer's miner is also the
  * includer, which is the only shape where the two readings differ.
  *
  * ==The stated intermediates are deliberately not decoded==
  *
  * The corpus states an era, a winner reward and a per-ommer share alongside
  * each vector, and records that a harness reading those instead of deriving
  * them asserts nothing. They are absent from this reader so that they cannot
  * be read by accident.
  *
  * @param ommers
  *   each ommer's height and the address it pays. The corpus also states a
  *   distance, which is the block's height less the ommer's and is therefore
  *   derivable; it is left out for the same reason the intermediates are.
  */
final case class OmmerPaymentVector(
    name: String,
    grounding: String,
    block: BigInt,
    beneficiary: Address,
    ommers: Vector[(BigInt, Address)],
    expectedCredits: Map[Address, BigInt]
)

object OmmerPaymentVector:

  private val Root: String = "ommerPaymentVectors"

  /** A vector confirmed against the chain rather than only computed. */
  val Observed: String = "observed"

  def decodeFile(path: String, contents: String): Either[String, Vector[OmmerPaymentVector]] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap { json =>
        json.hcursor.downField(Root).downField("vectors").focus match
          case None         => Left(path + ": no " + Root + ".vectors")
          case Some(vector) =>
            vector.asArray.toRight(path + ": " + Root + ".vectors is not an array").flatMap { entries =>
              entries.foldLeft(Right(Vector.empty): Either[String, Vector[OmmerPaymentVector]]) {
                case (Left(error), _)      => Left(error)
                case (Right(sofar), entry) => decodeVector(path, entry).map(sofar :+ _)
              }
            }
      }

  private def decodeVector(path: String, json: Json): Either[String, OmmerPaymentVector] =
    for
      name <- FixtureValues.stringAt(json, "name").left.map(path + ": " + _)
      grounding <- FixtureValues.stringAt(json, "grounding").left.map(path + " " + name + ": " + _)
      block <- json.hcursor.downField("block").focus.toRight(path + " " + name + ": no block")
      number <- FixtureValues.quantityAt(block, "number").left.map(path + " " + name + ": " + _)
      beneficiary <- FixtureValues.addressAt(block, "coinbase").left.map(path + " " + name + ": " + _)
      ommers <- decodeOmmers(path + " " + name, json.hcursor.downField("ommers").focus)
      credits <- decodeCredits(path + " " + name, json.hcursor.downField("expectedCredits").focus)
    yield OmmerPaymentVector(name, grounding, number, beneficiary, ommers, credits)

  private def decodeOmmers(name: String, json: Option[Json]): Either[String, Vector[(BigInt, Address)]] =
    json.flatMap(_.asArray).toRight(name + ": ommers is not an array").flatMap { entries =>
      entries.foldLeft(Right(Vector.empty): Either[String, Vector[(BigInt, Address)]]) {
        case (Left(error), _)      => Left(error)
        case (Right(sofar), entry) =>
          for
            number <- FixtureValues.quantityAt(entry, "number").left.map(name + " ommer: " + _)
            miner <- FixtureValues.addressAt(entry, "coinbase").left.map(name + " ommer: " + _)
          yield sofar :+ (number, miner)
      }
    }

  private def decodeCredits(name: String, json: Option[Json]): Either[String, Map[Address, BigInt]] =
    json.flatMap(_.asObject).toRight(name + ": expectedCredits is not an object").flatMap { entries =>
      entries.toVector.foldLeft(Right(Map.empty): Either[String, Map[Address, BigInt]]) {
        case (Left(error), _)             => Left(error)
        case (Right(sofar), (key, value)) =>
          for
            address <- FixtureValues.addressOf(key).left.map(name + ": " + _)
            amount <- FixtureValues
              .quantity(value.asString.getOrElse(""))
              .left
              .map(name + " credit " + key + ": " + _)
          yield sofar.updated(address, amount)
      }
    }

/** What a proof-of-work block credits, and to whom, at this chain's own heights.
  *
  * ==What these expectations rest on==
  *
  * The corpus names its oracle as `ethereumclassic/core-geth` production
  * v1.12.21-unstable @ `4185df450`, read rather than run -- specifically
  * `params/mutations/rewards_classic.go` -- and records that nine of the
  * eighteen vectors carry a real mainnet block whose expected credits were
  * confirmed against archive state as a miner balance delta. Where such a block
  * carries transactions the includer's observed delta exceeds its credit by
  * exactly the fees, and the corpus says which side of each vector is exact.
  *
  * **This is payment and not admissibility.** Whether an ommer may be included
  * at all is block-body validation, needs the chain rather than the state, and
  * is stated by a different tier.
  */
object OmmerPaymentCorpus:

  val Corpus: String = "fukuii-tests ethereumclassic/mainnet blocks/ommer_payment_vectors"

  private def file(under: Path): Path =
    NetworkFixtureCorpus.classicMainnet(under).resolve("blocks/ommer_payment_vectors.json")

  lazy val report: Option[CorpusReport] =
    NetworkFixtureCorpus.root.map(root => assemble(file(root), ClassicRewardHarness.engine, keepOmmers = true))

  /** The same run under an engine that never steps its emission down. */
  lazy val withoutLadder: Option[CorpusReport] =
    NetworkFixtureCorpus.root.map(root => assemble(file(root), ClassicRewardHarness.withoutLadder, keepOmmers = true))

  /** The same run with every block's ommers dropped.
    *
    * The control that shows the ommer payments are actually being asserted
    * rather than swamped by a winner reward that dominates them: a vector
    * stating an ommer must not still agree once the ommer is gone.
    */
  lazy val withoutOmmers: Option[CorpusReport] =
    NetworkFixtureCorpus.root.map(root => assemble(file(root), ClassicRewardHarness.engine, keepOmmers = false))

  private def assemble(path: Path, engine: EthashEngine, keepOmmers: Boolean): CorpusReport =
    FixtureCorpus.read(path).flatMap(OmmerPaymentVector.decodeFile(path.getFileName.toString, _)) match
      case Left(error) =>
        CorpusReport(
          Corpus,
          0,
          Vector(CaseOutcome(path.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        )
      case Right(vectors) => CorpusReport(Corpus, 1, vectors.map(outcomeOf(_, engine, keepOmmers)))

  private[certification] def outcomeOf(
      vector: OmmerPaymentVector,
      engine: EthashEngine,
      keepOmmers: Boolean
  ): CaseOutcome =
    val verdict =
      try
        val included: Seq[BlockHeader] =
          if keepOmmers then vector.ommers.map((number, miner) => ClassicRewardHarness.ommerAt(number, miner))
          else Seq.empty
        val credited = ClassicRewardHarness.credits(engine, vector.beneficiary, vector.block, included)
        if credited == vector.expectedCredits then Verdict.Agreed
        else Verdict.Diverged(differences(credited, vector.expectedCredits))
      catch
        case NonFatal(cause) => Verdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    CaseOutcome(vector.name, verdict)

  /** Every address the two maps disagree about, in both directions.
    *
    * An address credited that should not have been is as much a divergence as
    * one missed, and a comparison reporting only the stated addresses would be
    * blind to a block that paid somebody extra.
    */
  private def differences(credited: Map[Address, BigInt], stated: Map[Address, BigInt]): Vector[String] =
    (credited.keySet ++ stated.keySet).toVector
      .sortBy(_.toHex)
      .flatMap { address =>
        val answered = credited.getOrElse(address, BigInt(0))
        val expected = stated.getOrElse(address, BigInt(0))
        if answered == expected then None
        else Some(address.toHex + ": credited " + answered.toString + " rather than " + expected.toString)
      }

  private[certification] lazy val vectors: Option[Vector[OmmerPaymentVector]] =
    NetworkFixtureCorpus.root.map { root =>
      val path = file(root)
      FixtureCorpus
        .read(path)
        .flatMap(OmmerPaymentVector.decodeFile(path.getFileName.toString, _))
        .getOrElse(Vector.empty[OmmerPaymentVector])
    }
