package org.fukuii.consensus.certification

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import org.fukuii.bytes.{Bytes, UInt256, UInt64}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.consensus.{HeaderFault, HeaderValidator}
import org.fukuii.evm.EvmFixtures
import org.fukuii.evm.fixtures.FixtureCorpus
import org.fukuii.types.{BaseFeeTail, BlockHeader, BlockNonce, Bloom, Seal}
import org.scalatest.flatspec.AnyFlatSpec

/** The charge a block sets, certified against a published corpus rather than
  * against this build's own reading of the specification.
  *
  * ==Why this tier exists at all, and what it answers==
  *
  * A wrong base-fee derivation produces a wrong burn on every block carrying a
  * transaction, and **no state-test tier can report it**: a state fixture states
  * the charge in its environment rather than deriving one, so it agrees with any
  * derivation whatsoever. The question this tier settles is whether ANY corpus
  * reachable here could disagree with a wrong derivation, and the answer turned
  * out to depend entirely on which corpus is read.
  *
  * ==The obvious corpus is vacuous, which is the finding worth recording==
  *
  * `ethereum/execution-specs-fixtures` `tests-v20.0.1`'s `blockchain_tests`
  * under `for_london` carry thousands of blocks and **one distinct base-fee
  * value across all of them**. Every parent-to-child step is therefore a fixed
  * point, and a derivation that returned its parent's charge unchanged --
  * ignoring gas used, the target, and both bounded arms -- would agree with the
  * entire tier. **A tier that cannot disagree is not evidence**, so it is not
  * what is read here.
  *
  * ==What IS read, and why it discriminates==
  *
  * `ethereum/legacytests` @ `1f581b8c`,
  * `Cancun/BlockchainTests/ValidBlocks/bcEIP1559`, whose London-labelled cases
  * carry chains built to move the charge: blocks over target, under target and
  * at it, so all three arms of the derivation are exercised by blocks a
  * conformant client produced. The count of steps where the charge actually
  * MOVES is asserted below rather than described, because that count is the
  * whole of this tier's discriminating power and a corpus refresh could quietly
  * take it to zero.
  *
  * ==What this tier does NOT reach==
  *
  * The transition block, whose gas-limit rule is a different corpus and is
  * asserted from that corpus's own figures in `HeaderValidatorSpec`. And
  * EIP-3554's difficulty delay, which no published tier reaches at all -- the
  * neighbouring upgrades either side of London both have difficulty vectors and
  * London has none, so the delay this build states is sourced and uncertified.
  */
class LondonHeaderCertificationSpec extends AnyFlatSpec:

  private val London: UpgradeRules = ethereum.Upgrades.london

  private def quantityAt(json: Json, field: String): Option[BigInt] =
    json.hcursor.downField(field).as[String].toOption.map { text =>
      val body = if text.startsWith("0x") then text.drop(2) else text
      if body.isEmpty then BigInt(0) else BigInt(body, 16)
    }

  /** A header carrying only what this layer reads.
    *
    * Every other field is left at its zero rather than transcribed: a value in
    * a field no rule reads would suggest something depended on it.
    */
  private def headerOf(gasLimit: BigInt, gasUsed: BigInt, baseFee: Option[BigInt]): Option[BlockHeader] =
    for
      limit <- UInt64.fromBigInt(gasLimit).toOption
      used <- UInt64.fromBigInt(gasUsed).toOption
      tail <- baseFee match
        case None    => Some(None)
        case Some(f) => UInt256.fromBigInt(f).toOption.map(w => Some(BaseFeeTail(w)))
    yield BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = EvmFixtures.hash(0),
      beneficiary = EvmFixtures.address(0),
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = UInt256.Zero,
      number = UInt64.Zero,
      gasLimit = limit,
      gasUsed = used,
      timestamp = UInt64.Zero,
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(EvmFixtures.hash(0), BlockNonce.Zero),
      tail = tail
    )

  private def headerFrom(json: Json): Option[BlockHeader] =
    for
      limit <- quantityAt(json, "gasLimit")
      used <- quantityAt(json, "gasUsed")
      header <- headerOf(limit, used, quantityAt(json, "baseFeePerGas"))
    yield header

  /** One parent-to-child step the corpus published. */
  final private case class Step(parent: BlockHeader, child: BlockHeader)

  private def stepsIn(file: Path): Vector[Step] =
    parse(Files.readString(file)).toOption.toVector.flatMap { doc =>
      doc.asObject.toVector.flatMap(_.toVector).flatMap { (_, testCase) =>
        val london = testCase.hcursor.downField("network").as[String].toOption.contains("London")
        if !london then Vector.empty
        else
          val genesis = testCase.hcursor.downField("genesisBlockHeader").focus.flatMap(headerFrom)
          val blocks = testCase.hcursor
            .downField("blocks")
            .values
            .toVector
            .flatten
            .flatMap(_.hcursor.downField("blockHeader").focus)
            .flatMap(headerFrom)
          (genesis.toVector ++ blocks).sliding(2).collect { case Vector(p, c) => Step(p, c) }.toVector
      }
    }

  private val corpus: Option[Path] =
    FixtureCorpus.root.map(_.resolve("ethereum/legacytests/Cancun/BlockchainTests/ValidBlocks/bcEIP1559"))

  private val steps: Vector[Step] =
    corpus.filter(Files.isDirectory(_)).toVector.flatMap(FixtureCorpus.jsonFilesUnder).flatMap(stepsIn)

  /** Steps where the charge actually changed between parent and child.
    *
    * The discriminating subset. A step whose charge is unchanged is satisfied by
    * a derivation that returns its parent's value, so it certifies nothing about
    * the arithmetic.
    */
  private val moving: Vector[Step] =
    steps.filter(s => s.parent.baseFeePerGas != s.child.baseFeePerGas)

  "the fee-market corpus" should "be present, or this tier certifies nothing" in
    assume(steps.nonEmpty, "no corpus root is configured, so this tier is skipped rather than passed")

  it should "carry enough steps to be worth reading" in {
    val _ = assume(steps.nonEmpty)
    assert(steps.length >= 100, "measured at 116 parent-to-child steps across nine London cases: " + steps.length)
  }

  it should "carry steps at which the charge MOVES, which is what discriminates" in {
    val _ = assume(steps.nonEmpty)
    // The assertion that keeps this tier honest. Were this to fall to zero --
    // by a corpus refresh, or by reading the wrong tree -- every case below
    // would still pass while certifying nothing, which is precisely the state
    // the generated corpus is in.
    assert(
      moving.length >= 50,
      "measured at 84 steps where the charge changes; a tier of fixed points cannot disagree: " + moving.length
    )
  }

  it should "agree with this build's derivation at every published step" in {
    val _ = assume(steps.nonEmpty)
    val disagreed = steps.filter(s => HeaderValidator.validate(s.child, s.parent, London, London).isLeft)
    assert(
      disagreed.isEmpty,
      "steps where the derivation or the gas-limit bound disagreed with the corpus: " + disagreed.length +
        " of " + steps.length
    )
  }

  it should "reject a charge one unit off at every MOVING step" in {
    val _ = assume(steps.nonEmpty)
    // The negative control, and it is what separates a real tier from one whose
    // cases would pass against any implementation. Perturbing the child's stated
    // charge by one must be refused at every step; a step that still passes is a
    // step the derivation is not actually reading.
    val accepted = moving.filter { s =>
      val perturbed = s.child.baseFeePerGas
        .map(_.toBigInt + 1)
        .flatMap(f => headerOf(s.child.gasLimit.toBigInt, s.child.gasUsed.toBigInt, Some(f)))
      perturbed.exists(h => HeaderValidator.validate(h, s.parent, London, London).isRight)
    }
    assert(accepted.isEmpty, "moving steps that accepted a charge one unit off: " + accepted.length)
  }

  it should "name the mismatch rather than refusing for some other reason" in {
    val _ = assume(moving.nonEmpty)
    val step = moving.head
    val perturbed = step.child.baseFeePerGas
      .map(_.toBigInt + 1)
      .flatMap(f => headerOf(step.child.gasLimit.toBigInt, step.child.gasUsed.toBigInt, Some(f)))
    assert(
      perturbed.map(h => HeaderValidator.validate(h, step.parent, London, London)).exists {
        case Left(_: HeaderFault.BaseFeeMismatch) => true
        case _                                    => false
      },
      "a refusal for the gas limit would make the control above pass for the wrong reason"
    )
  }
