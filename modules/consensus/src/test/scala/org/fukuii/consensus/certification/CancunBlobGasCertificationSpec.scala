package org.fukuii.consensus.certification

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import org.fukuii.bytes.{Bytes, Hash, UInt256, UInt64}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.proposals.eip.Eip4844
import org.fukuii.consensus.{HeaderFault, HeaderValidator, Resolved}
import org.fukuii.evm.EvmFixtures
import org.fukuii.evm.fixtures.FixtureCorpus
import org.fukuii.types.{BaseFeeTail, BlobGasTail, BlockHeader, BlockNonce, Bloom, Seal, WithdrawalsTail}
import org.scalatest.flatspec.AnyFlatSpec

/** The blob-gas excess a header states, certified against a published corpus
  * rather than against this build's own reading of the specification.
  *
  * ==Why this tier exists, and why the state tier cannot substitute==
  *
  * A state fixture STATES an excess in its environment rather than deriving one,
  * so it agrees with any derivation whatsoever -- the same limit the fee
  * market's charge has, recorded in [[LondonHeaderCertificationSpec]]. A wrong
  * derivation here is a header every node on the network rejects, and no state
  * corpus can report it.
  *
  * ==Unlike the fee market's tier, the obvious corpus is NOT vacuous==
  *
  * `ethereum/execution-specs-fixtures` `tests-v20.0.1`'s `blockchain_tests`
  * under `for_cancun/cancun/eip4844_blobs/excess_blob_gas` carry 213 cases
  * across 12 files, built to move the excess in both directions and to state
  * blocks that must be refused for exactly this rule. The counts below are
  * asserted rather than described, because they are this tier's whole
  * discriminating power and a corpus refresh could quietly take them to zero.
  *
  * ==Both directions, which the fee market's tier reaches only by perturbing a
  * value itself==
  *
  * That tier had to construct its own negative control, because the corpus it
  * reads carries no rejected block whose charge is wrong. This one does not: 124
  * of the 173 blocks here are published with
  * `BlockException.INCORRECT_EXCESS_BLOB_GAS`, so the refusals are the corpus's
  * own rather than this spec's.
  *
  * ==What this tier does NOT reach, stated as a figure rather than as a caveat==
  *
  * The other 49 blocks are published as
  * `BlockException.INCORRECT_BLOB_GAS_USED`. **Seven of them are now refused
  * here and 42 are not**, and the split is exactly the one the layer boundary
  * predicts: the seven state a spend outside a bound a header alone settles,
  * and the 42 state a well-formed spend -- every one a whole number of blobs
  * between zero and the fork's maximum -- which is wrong only against a body
  * nothing at this layer has.
  *
  * **So the 42 is not a residue to be whittled down.** It is the commitment
  * itself, and no header rule can move it; what closes it is the comparison
  * against what the block's transactions actually carried.
  *
  * ==The corpus cannot tell the two header bounds apart, which is why two
  * controls below are constructed==
  *
  * All seven state the same spend -- 18,446,744,073,709,551,615, the largest a
  * header can hold -- so each fails BOTH bounds at once, and the directory holds
  * no block failing one alone. A build implementing either bound by itself
  * passes every refusal this corpus publishes. **That is a corpus that cannot
  * discriminate the behavior under discussion**, so the two bounds are
  * separated by perturbation instead, exactly as the excess is.
  *
  * **One direction the corpus does pin, and it is the off-by-one.** Sixteen
  * accepted steps spend exactly the maximum, so a maximum written as `>=`
  * rather than `>` refuses a block the corpus accepts and fails the agreement
  * test above.
  *
  * **Every figure here is asserted below rather than left in prose.** A tier
  * whose blind spot is described and not measured is one nobody notices
  * closing, or widening.
  */
class CancunBlobGasCertificationSpec extends AnyFlatSpec:

  /** The fork below with the blob-gas accounting adopted.
    *
    * Not resolved through a schedule, for the reason every Cancun corpus here
    * names a composition: the fork is being built one document at a time and no
    * activation resolves to a rule set holding some of it. What this needs is a
    * fee market, a withdrawals commitment and a blob schedule, and the fork
    * below supplies the first two.
    */
  private val Rules: UpgradeRules = ethereum.Upgrades.shanghai.adopting(Eip4844.component)

  private def quantityAt(json: Json, field: String): Option[BigInt] =
    json.hcursor.downField(field).as[String].toOption.map { text =>
      val body = if text.startsWith("0x") then text.drop(2) else text
      if body.isEmpty then BigInt(0) else BigInt(body, 16)
    }

  private def hashAt(json: Json, field: String): Option[Hash] =
    json.hcursor
      .downField(field)
      .as[String]
      .toOption
      .flatMap(text => Bytes.fromHex(if text.startsWith("0x") then text.drop(2) else text).toOption)
      .filter(_.length == Hash.Width)
      .map(raw => Hash.fromBytesTruncating(raw.toIArray))

  /** A header carrying only what this layer reads.
    *
    * ==Three fields are transcribed that the fee market's tier leaves at zero==
    *
    * The ommers commitment, the difficulty and the seal's nonce are read from
    * the published header rather than filled in, because the rules this corpus
    * is read under hold all three at constants and a zero commitment would be
    * refused by that rule rather than by the one under test. Transcribing them
    * means the constants rule is exercised by the corpus instead of satisfied by
    * this file.
    *
    * **The beacon root is deliberately NOT carried.** It is a further trailing
    * element behind the blob pair, no rule at this layer reads it, and its
    * presence rule belongs to a document this build has not adopted -- so a
    * header built with it would state something no check here could justify.
    */
  private def headerFrom(json: Json): Option[BlockHeader] =
    for
      number <- quantityAt(json, "number")
      timestamp <- quantityAt(json, "timestamp")
      gasLimit <- quantityAt(json, "gasLimit")
      gasUsed <- quantityAt(json, "gasUsed")
      difficulty <- quantityAt(json, "difficulty")
      ommersHash <- hashAt(json, "uncleHash")
      withdrawalsRoot <- hashAt(json, "withdrawalsRoot")
      baseFee <- quantityAt(json, "baseFeePerGas")
      blobGasUsed <- quantityAt(json, "blobGasUsed")
      excessBlobGas <- quantityAt(json, "excessBlobGas")
      nonce <- json.hcursor.downField("nonce").as[String].toOption.flatMap(BlockNonce.fromHex(_).toOption)
      built <- headerOf(
        number,
        timestamp,
        gasLimit,
        gasUsed,
        difficulty,
        ommersHash,
        nonce,
        withdrawalsRoot,
        baseFee,
        blobGasUsed,
        excessBlobGas
      )
    yield built

  private def headerOf(
      number: BigInt,
      timestamp: BigInt,
      gasLimit: BigInt,
      gasUsed: BigInt,
      difficulty: BigInt,
      ommersHash: Hash,
      nonce: BlockNonce,
      withdrawalsRoot: Hash,
      baseFee: BigInt,
      blobGasUsed: BigInt,
      excessBlobGas: BigInt
  ): Option[BlockHeader] =
    for
      count <- UInt64.fromBigInt(number).toOption
      when <- UInt64.fromBigInt(timestamp).toOption
      limit <- UInt64.fromBigInt(gasLimit).toOption
      used <- UInt64.fromBigInt(gasUsed).toOption
      hardness <- UInt256.fromBigInt(difficulty).toOption
      charge <- UInt256.fromBigInt(baseFee).toOption
      spent <- UInt64.fromBigInt(blobGasUsed).toOption
      excess <- UInt64.fromBigInt(excessBlobGas).toOption
    yield BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = ommersHash,
      beneficiary = EvmFixtures.address(0),
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = hardness,
      number = count,
      gasLimit = limit,
      gasUsed = used,
      timestamp = when,
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(EvmFixtures.hash(0), nonce),
      tail = Some(BaseFeeTail(charge, Some(WithdrawalsTail(withdrawalsRoot, Some(BlobGasTail(spent, excess))))))
    )

  /** One parent-to-child step the corpus published as valid. */
  final private case class Step(parent: BlockHeader, child: BlockHeader)

  /** One block the corpus published as invalid, with the exception names it
    * states.
    */
  final private case class Refused(parent: BlockHeader, child: BlockHeader, expected: String)

  /** What one case yields: the steps it accepts and the blocks it refuses.
    *
    * ==A refused block does not advance the chain, and getting that wrong makes
    * the tier meaningless==
    *
    * 99 of these cases publish an accepted block and then a refused one, so a
    * reader that treated every entry as the next parent would compare the second
    * against a header the network never accepted -- and the refusal would then
    * be right for the wrong reason.
    */
  private def walk(testCase: Json): (Vector[Step], Vector[Refused]) =
    testCase.hcursor.downField("genesisBlockHeader").focus.flatMap(headerFrom) match
      case None          => (Vector.empty, Vector.empty)
      case Some(genesis) =>
        testCase.hcursor
          .downField("blocks")
          .values
          .toVector
          .flatten
          .foldLeft((genesis, Vector.empty[Step], Vector.empty[Refused])) { case ((parent, steps, refused), block) =>
            block.hcursor.downField("expectException").as[String].toOption match
              case Some(expected) =>
                block.hcursor.downField("rlp_decoded").downField("blockHeader").focus.flatMap(headerFrom) match
                  case Some(child) => (parent, steps, refused :+ Refused(parent, child, expected))
                  case None        => (parent, steps, refused)
              case None =>
                block.hcursor.downField("blockHeader").focus.flatMap(headerFrom) match
                  case Some(child) => (child, steps :+ Step(parent, child), refused)
                  case None        => (parent, steps, refused)
          } match
          case (_, steps, refused) => (steps, refused)

  private val corpus: Option[Path] =
    FixtureCorpus.root.map(
      FixtureCorpus.generated(_).resolve("blockchain_tests/for_cancun/cancun/eip4844_blobs/excess_blob_gas")
    )

  private val walked: Vector[(Vector[Step], Vector[Refused])] =
    corpus
      .filter(Files.isDirectory(_))
      .toVector
      .flatMap(FixtureCorpus.jsonFilesUnder)
      .flatMap(file =>
        parse(Files.readString(file)).toOption.toVector
          .flatMap(_.asObject.toVector.flatMap(_.toVector))
          .map((_, testCase) => walk(testCase))
      )

  private val steps: Vector[Step] = walked.flatMap(_._1)

  private val refused: Vector[Refused] = walked.flatMap(_._2)

  /** Steps where the excess actually changed between parent and child.
    *
    * The discriminating subset. A step whose excess is unchanged is satisfied by
    * a derivation that returns its parent's value, so it certifies nothing about
    * the arithmetic.
    */
  private val moving: Vector[Step] =
    steps.filter(step => step.parent.excessBlobGas != step.child.excessBlobGas)

  private def verdict(parent: BlockHeader, child: BlockHeader): Either[HeaderFault, Unit] =
    HeaderValidator.validate(Resolved(child, Rules), Resolved(parent, Rules))

  /** The exception name this build's rule corresponds to. */
  private val ExcessName: String = "INCORRECT_EXCESS_BLOB_GAS"

  /** The exception name the corpus gives a spend it rejects, for any reason. */
  private val SpendName: String = "INCORRECT_BLOB_GAS_USED"

  /** The exception name the corpus adds where the spend is outside the maximum. */
  private val LimitName: String = "BLOB_GAS_USED_ABOVE_LIMIT"

  /** This fork's maximum spend, taken from the rules under test.
    *
    * Read from the schedule rather than restated, so a control below cannot
    * agree with a bound that was changed -- and raised rather than defaulted,
    * because a zero here would make every control pass against a build checking
    * nothing.
    */
  private val MaxBlobGas: BigInt =
    Rules.header.blobSchedule
      .map(_.maxBlobs * HeaderValidator.BlobGasPerBlob)
      .getOrElse(throw new IllegalStateException("the rules under test carry no blob schedule"))

  /** The blocks the corpus refuses for their spend and not for their excess. */
  private val forSpendOnly: Vector[Refused] =
    refused.filter(block => block.expected.contains(SpendName) && !block.expected.contains(ExcessName))

  /** The same header, stating a different spend. */
  private def spending(header: BlockHeader, used: UInt64): BlockHeader =
    header.copy(tail =
      header.tail.map(fee =>
        fee.copy(next =
          fee.next.map(withdrawals => withdrawals.copy(next = withdrawals.next.map(_.copy(blobGasUsed = used))))
        )
      )
    )

  "the blob-gas corpus" should "be present, or this tier certifies nothing" in
    assume(steps.nonEmpty, "no corpus root is configured, so this tier is skipped rather than passed")

  it should "carry the steps and the refusals the directory published" in {
    val _ = assume(steps.nonEmpty)
    // EXACT rather than lower bounds, for the reason the fee-market tier states:
    // a bound tolerates a corpus refresh removing part of the tier without
    // saying so.
    assert(
      steps.length == 175 && refused.length == 173,
      "measured at 175 accepted parent-to-child steps and 173 refused blocks: " +
        steps.length.toString + " and " + refused.length.toString
    )
  }

  it should "carry steps at which the excess MOVES, which is what discriminates" in {
    val _ = assume(steps.nonEmpty)
    // The assertion that keeps this tier honest, and the figure that separates
    // it from the fee market's generated corpus -- which is a tier of fixed
    // points and cannot disagree with a derivation that does no arithmetic.
    assert(
      moving.length == 168,
      "measured at 168 of 175 steps where the excess changes: " + moving.length.toString
    )
  }

  it should "agree with this build's derivation at every published step" in {
    val _ = assume(steps.nonEmpty)
    val disagreed = steps.filter(step => verdict(step.parent, step.child).isLeft)
    assert(
      disagreed.isEmpty,
      "steps this build refused that the corpus accepts: " + disagreed.length.toString + " of " +
        steps.length.toString + ", first reason " +
        disagreed.headOption.map(step => verdict(step.parent, step.child).toString).getOrElse("none")
    )
  }

  it should "reject an excess one unit off at every MOVING step" in {
    val _ = assume(steps.nonEmpty)
    // A second negative control beside the corpus's own refusals, and a wider
    // one: the published refusals are 124 blocks the generator chose, while this
    // perturbs every accepted step. A step that still passes is a step the
    // derivation is not actually reading.
    val accepted = moving.filter { step =>
      step.child.excessBlobGas
        .map(_.toBigInt + 1)
        .flatMap(UInt64.fromBigInt(_).toOption)
        .exists { perturbed =>
          val tail = step.child.tail.flatMap(_.next).flatMap(_.next)
          val moved = step.child.copy(tail =
            step.child.tail.map(fee =>
              fee.copy(next =
                fee.next.map(withdrawals => withdrawals.copy(next = tail.map(_.copy(excessBlobGas = perturbed))))
              )
            )
          )
          verdict(step.parent, moved).isRight
        }
    }
    assert(accepted.isEmpty, "moving steps that accepted an excess one unit off: " + accepted.length.toString)
  }

  "a block the corpus refuses for its excess" should "be refused here, and named as such" in {
    val _ = assume(refused.nonEmpty)
    val forExcess = refused.filter(_.expected.contains(ExcessName))
    val wrong = forExcess.filterNot(block =>
      verdict(block.parent, block.child) match
        case Left(_: HeaderFault.ExcessBlobGasMismatch) => true
        case _                                          => false
    )
    assert(
      forExcess.length == 124 && wrong.isEmpty,
      "measured at 124 blocks published with " + ExcessName + "; refused for some other reason or accepted: " +
        wrong.length.toString + ", first " +
        wrong.headOption.map(block => verdict(block.parent, block.child).toString).getOrElse("none")
    )
  }

  "a block the corpus refuses only for its stated spend" should "be refused where a header bound reaches it" in {
    val _ = assume(refused.nonEmpty)
    val reached = forSpendOnly.filter(block =>
      verdict(block.parent, block.child) match
        case Left(_: HeaderFault.BlobGasUsedNotWholeBlobs) => true
        case Left(_: HeaderFault.BlobGasUsedAboveLimit)    => true
        case _                                             => false
    )
    // Tied to the corpus's own labeling and not only to a count: every block
    // this build now refuses is one the corpus itself marked as outside the
    // limit, so the two agree about WHICH blocks these are rather than merely
    // about how many.
    assert(
      forSpendOnly.length == 49 && reached.length == 7 && reached.forall(_.expected.contains(LimitName)),
      "measured at 49 spend-only refusals of which 7 are reached by a header bound, all so labelled: " +
        forSpendOnly.length.toString + " found, " + reached.length.toString + " refused, " +
        reached.count(_.expected.contains(LimitName)).toString + " labelled"
    )
  }

  it should "be ACCEPTED where only an executed body could reach it" in {
    val _ = assume(refused.nonEmpty)
    val accepted = forSpendOnly.filter(block => verdict(block.parent, block.child).isRight)
    // What remains of this tier's blind spot, and it is the commitment rather
    // than a shortfall in the two bounds: every one of these states a whole
    // number of blobs within the maximum, so no rule reading the header alone
    // can tell it from a block that really did spend that much.
    assert(
      accepted.length == 42 && accepted.forall(block =>
        block.child.blobGasUsed.exists(used =>
          used.toBigInt % HeaderValidator.BlobGasPerBlob == 0 && used.toBigInt <= MaxBlobGas
        )
      ),
      "measured at 42 spend-only refusals this build still accepts, every one well formed: " +
        accepted.length.toString
    )
  }

  "the corpus's own spend refusals" should "each fail BOTH bounds, so neither bound is discriminated" in {
    val _ = assume(refused.nonEmpty)
    // Why the two controls below are constructed rather than read off the
    // corpus. Every block it refuses for a spend outside the bounds states the
    // largest figure a header can hold, which is neither a whole number of blobs
    // nor within the maximum -- so a build implementing one bound alone refuses
    // all seven, and this tier cannot tell it from a build implementing both.
    val outside = forSpendOnly.filter(_.expected.contains(LimitName))
    val failingBoth = outside.filter(block =>
      block.child.blobGasUsed.exists(used =>
        used.toBigInt % HeaderValidator.BlobGasPerBlob != 0 && used.toBigInt > MaxBlobGas
      )
    )
    assert(
      outside.length == 7 && failingBoth.length == 7,
      "of " + outside.length.toString + " blocks outside the limit, " + failingBoth.length.toString +
        " fail both bounds; any difference would be a block that discriminates one bound"
    )
  }

  "a spend one unit off a whole number of blobs" should "be refused at every published step" in {
    val _ = assume(steps.nonEmpty)
    // The control the corpus cannot supply. One unit added to a spend it states
    // leaves a figure that is not a whole number of blobs, and the whole-blob
    // check runs before the maximum, so the fault names that bound at every step
    // including the ones already at the limit.
    val wrong = steps.filterNot { step =>
      step.child.blobGasUsed
        .map(_.toBigInt + 1)
        .flatMap(UInt64.fromBigInt(_).toOption)
        .exists(used =>
          verdict(step.parent, spending(step.child, used)) match
            case Left(_: HeaderFault.BlobGasUsedNotWholeBlobs) => true
            case _                                             => false
        )
    }
    assert(wrong.isEmpty, "steps whose partial-blob spend was not refused as such: " + wrong.length.toString)
  }

  "a spend one blob above the fork's maximum" should "be refused at every published step" in {
    val _ = assume(steps.nonEmpty)
    // The second constructed control, and the one that isolates the maximum: a
    // clean multiple of the per-blob figure, one blob over the limit, passes the
    // whole-blob check and can only be refused by the bound under test. The
    // limit the fault carries is checked too, so a bound reading some other
    // fork's schedule would not pass here.
    val overLimit = MaxBlobGas + HeaderValidator.BlobGasPerBlob
    val wrong = steps.filterNot { step =>
      UInt64
        .fromBigInt(overLimit)
        .toOption
        .exists(used =>
          verdict(step.parent, spending(step.child, used)) match
            case Left(HeaderFault.BlobGasUsedAboveLimit(_, limit)) => limit == MaxBlobGas
            case _                                                 => false
        )
    }
    assert(
      wrong.isEmpty,
      "steps whose over-limit spend was not refused against this fork's limit: " + wrong.length.toString
    )
  }

  "the maximum" should "admit a block spending exactly it" in {
    val _ = assume(steps.nonEmpty)
    // The off-by-one control, and the one direction the corpus does pin: a bound
    // written `>=` refuses these sixteen and the agreement test above fails.
    // Asserted here as well so the cause is named rather than surfacing there as
    // an unexplained divergence.
    val atLimit = steps.filter(_.child.blobGasUsed.exists(_.toBigInt == MaxBlobGas))
    assert(
      atLimit.length == 16 && atLimit.forall(step => verdict(step.parent, step.child).isRight),
      "measured at 16 accepted steps spending exactly the maximum: " + atLimit.length.toString
    )
  }

  "the refusals and the acceptances together" should "account for every published block" in {
    val _ = assume(refused.nonEmpty)
    // The closure property: no block in the directory falls outside the two
    // groups above, so neither figure can be a subset that happened to pass
    // while a third group went unmeasured.
    val named = refused.count(block => block.expected.contains(ExcessName) || block.expected.contains(SpendName))
    assert(
      named == refused.length,
      "blocks whose expected exception names neither rule: " + (refused.length - named).toString
    )
  }
