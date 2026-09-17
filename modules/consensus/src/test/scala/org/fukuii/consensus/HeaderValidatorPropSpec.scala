package org.fukuii.consensus

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.bytes.{Bytes, Hash, UInt256, UInt64}
import org.fukuii.chainspec.{FeeMarket, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{
  BaseFeeTail,
  BeaconRootTail,
  BlobGasTail,
  BlockHeader,
  BlockNonce,
  Bloom,
  Seal,
  WithdrawalsTail
}

/** [[HeaderValidator.faults]] against [[HeaderValidator.validate]], over
  * headers breaking each suffix of the rules every mechanism shares, and each
  * rule alone.
  *
  * ==Why suffixes==
  *
  * A header breaking one rule and every rule after it has that rule as its
  * first fault, so one row per rule pins the whole order: two rules run in a
  * different order give some row a different first fault, and a rule missing
  * gives some row a different list.
  *
  * ==Why each rule alone as well==
  *
  * A suffix row breaks every rule after its first, so it cannot show that a
  * later rule stays quiet where only an earlier one is broken. A row breaking
  * one rule with every other intact does: a rule that also refused on another
  * rule's condition would add its fault to that row's list. The last rule's
  * suffix row is already that rule alone.
  *
  * ==Why a first block under a fee market, over a parent below every proposal
  * the block's rules add==
  *
  * Over such a parent the two rules that derive a value derive a constant the
  * fork states instead: the market's opening charge, and an excess of zero. So
  * no expected value below is the output of a derivation.
  *
  * `HeaderValidatorSpec` holds the examples, each rule's boundaries among them.
  */
class HeaderValidatorPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val ParentRules: UpgradeRules = ethereum.Upgrades.berlin

  private val ChildRules: UpgradeRules = ethereum.Upgrades.cancun

  /** Rules requiring a withdrawals commitment and no blob-gas account, so the
    * commitment can be missing on its own.
    */
  private val WithdrawalsRules: UpgradeRules = ethereum.Upgrades.shanghai

  private def marketOf(rules: UpgradeRules): FeeMarket =
    rules.header.feeMarket.getOrElse(throw new IllegalStateException("the child's rules state no fee market"))

  private val Market: FeeMarket = marketOf(ChildRules)

  private def count(n: BigInt): UInt64 =
    UInt64.fromBigInt(n).getOrElse(throw new IllegalStateException("wider than a header can count: " + n.toString))

  private def word(n: BigInt): UInt256 =
    UInt256.fromBigInt(n).getOrElse(throw new IllegalStateException("wider than a header can state: " + n.toString))

  private val One: UInt256 = word(BigInt(1))

  private val ParentLimit: BigInt = BigInt(3141592)

  /** The parent's limit as the first block under a market is compared with it. */
  private val Scaled: BigInt = ParentLimit * Market.elasticityMultiplier

  private val AboveMaximum: BigInt = HeaderValidator.MaxGasLimit + 1

  private val SomeRoot: Hash = EvmFixtures.hash(0x31)

  private def headerOf(
      number: Long,
      timestamp: Long,
      gasLimit: BigInt,
      gasUsed: BigInt,
      difficulty: UInt256,
      tail: Option[BaseFeeTail]
  ): BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = HeaderValidator.EmptyOmmersHash,
      beneficiary = EvmFixtures.address(0),
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = difficulty,
      number = count(BigInt(number)),
      gasLimit = count(gasLimit),
      gasUsed = count(gasUsed),
      timestamp = count(BigInt(timestamp)),
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(EvmFixtures.hash(0), BlockNonce.Zero),
      tail = tail
    )

  private def parentAt(gasLimit: BigInt): Resolved =
    Resolved(headerOf(1, 12, gasLimit, BigInt(0), UInt256.Zero, None), ParentRules)

  private val parent: Resolved = parentAt(ParentLimit)

  /** A parent whose limit, scaled, is exactly one above the maximum, so a child
    * stating that is inside the bound and above the maximum.
    */
  private val parentBelowTheMaximum: Resolved = parentAt(AboveMaximum / Market.elasticityMultiplier)

  private def child(
      timestamp: Long,
      gasLimit: BigInt,
      gasUsed: BigInt,
      difficulty: UInt256,
      tail: Option[BaseFeeTail]
  ): Resolved =
    Resolved(headerOf(2, timestamp, gasLimit, gasUsed, difficulty, tail), ChildRules)

  private def tailWith(charge: UInt256, blobGas: BlobGasTail): BaseFeeTail =
    BaseFeeTail(charge, Some(WithdrawalsTail(SomeRoot, Some(blobGas))))

  private val WholeTail: BaseFeeTail =
    tailWith(Market.initialBaseFee, BlobGasTail(UInt64.Zero, UInt64.Zero, Some(BeaconRootTail(SomeRoot))))

  private val NoBeaconRoot: BaseFeeTail = tailWith(Market.initialBaseFee, BlobGasTail(UInt64.Zero, UInt64.Zero))

  private val NoBlobGas: BaseFeeTail = BaseFeeTail(Market.initialBaseFee, Some(WithdrawalsTail(SomeRoot)))

  private val NoWithdrawals: BaseFeeTail = BaseFeeTail(Market.initialBaseFee)

  private val WrongCharge: UInt256 = word(Market.initialBaseFee.toBigInt + 1)

  private val WrongExcess: UInt64 = UInt64.fromBits(1L)

  private val fromBeaconRoot: Vector[HeaderFault] = Vector(HeaderFault.ParentBeaconBlockRootMissing)

  private val fromBlobGas: Vector[HeaderFault] = HeaderFault.BlobGasMissing +: fromBeaconRoot

  private val fromWithdrawals: Vector[HeaderFault] = HeaderFault.WithdrawalsRootMissing +: fromBlobGas

  private val fromConstants: Vector[HeaderFault] = HeaderFault.DifficultyNotFixed(One) +: fromWithdrawals

  private val fromBaseFee: Vector[HeaderFault] = HeaderFault.BaseFeeMissing +: fromConstants

  private val fromBound: Vector[HeaderFault] = HeaderFault.GasLimitOutOfBounds(Scaled * 2, Scaled) +: fromBaseFee

  private val fromMaximum: Vector[HeaderFault] =
    HeaderFault.GasLimitAboveMaximum(AboveMaximum, HeaderValidator.MaxGasLimit) +:
      HeaderFault.GasLimitOutOfBounds(AboveMaximum, Scaled) +: fromBaseFee

  private val fromGasUsed: Vector[HeaderFault] =
    HeaderFault.GasUsedAboveLimit(AboveMaximum + 1, AboveMaximum) +: fromMaximum

  private val fromSuccession: Vector[HeaderFault] =
    HeaderFault.TimestampNotAfterParent(BigInt(12), BigInt(12)) +: fromGasUsed

  private val Rows = Table(
    ("rules broken", "parent", "header", "faults"),
    ("none", parent, child(24, Scaled, BigInt(0), UInt256.Zero, Some(WholeTail)), Vector.empty[HeaderFault]),
    // Each suffix of the rules.
    ("the beacon root onward", parent, child(24, Scaled, BigInt(0), UInt256.Zero, Some(NoBeaconRoot)), fromBeaconRoot),
    ("the blob-gas account onward", parent, child(24, Scaled, BigInt(0), UInt256.Zero, Some(NoBlobGas)), fromBlobGas),
    (
      "the withdrawals root onward",
      parent,
      child(24, Scaled, BigInt(0), UInt256.Zero, Some(NoWithdrawals)),
      fromWithdrawals
    ),
    ("the constants onward", parent, child(24, Scaled, BigInt(0), One, Some(NoWithdrawals)), fromConstants),
    ("the charge onward", parent, child(24, Scaled, BigInt(0), One, None), fromBaseFee),
    ("the bound onward", parent, child(24, Scaled * 2, BigInt(0), One, None), fromBound),
    ("the maximum onward", parent, child(24, AboveMaximum, BigInt(0), One, None), fromMaximum),
    ("the gas figure onward", parent, child(24, AboveMaximum, AboveMaximum + 1, One, None), fromGasUsed),
    ("succession onward", parent, child(12, AboveMaximum, AboveMaximum + 1, One, None), fromSuccession),
    // Each rule alone, every other intact.
    (
      "succession alone",
      parent,
      child(12, Scaled, BigInt(0), UInt256.Zero, Some(WholeTail)),
      Vector(HeaderFault.TimestampNotAfterParent(BigInt(12), BigInt(12)))
    ),
    (
      "the gas figure alone",
      parent,
      child(24, Scaled, Scaled + 1, UInt256.Zero, Some(WholeTail)),
      Vector(HeaderFault.GasUsedAboveLimit(Scaled + 1, Scaled))
    ),
    (
      "the maximum alone",
      parentBelowTheMaximum,
      child(24, AboveMaximum, BigInt(0), UInt256.Zero, Some(WholeTail)),
      Vector(HeaderFault.GasLimitAboveMaximum(AboveMaximum, HeaderValidator.MaxGasLimit))
    ),
    (
      "the bound alone",
      parent,
      child(24, Scaled * 2, BigInt(0), UInt256.Zero, Some(WholeTail)),
      Vector(HeaderFault.GasLimitOutOfBounds(Scaled * 2, Scaled))
    ),
    (
      "the charge alone",
      parent,
      child(24, Scaled, BigInt(0), UInt256.Zero, Some(WholeTail.copy(baseFeePerGas = WrongCharge))),
      Vector(HeaderFault.BaseFeeMismatch(WrongCharge, Market.initialBaseFee))
    ),
    (
      "the constants alone",
      parent,
      child(24, Scaled, BigInt(0), One, Some(WholeTail)),
      Vector(HeaderFault.DifficultyNotFixed(One))
    ),
    (
      "the withdrawals root alone",
      parent,
      Resolved(
        headerOf(
          2,
          24,
          ParentLimit * marketOf(WithdrawalsRules).elasticityMultiplier,
          BigInt(0),
          UInt256.Zero,
          Some(BaseFeeTail(marketOf(WithdrawalsRules).initialBaseFee))
        ),
        WithdrawalsRules
      ),
      Vector(HeaderFault.WithdrawalsRootMissing)
    ),
    (
      "the blob-gas account alone",
      parent,
      child(
        24,
        Scaled,
        BigInt(0),
        UInt256.Zero,
        Some(tailWith(Market.initialBaseFee, BlobGasTail(UInt64.Zero, WrongExcess, Some(BeaconRootTail(SomeRoot)))))
      ),
      Vector(HeaderFault.ExcessBlobGasMismatch(WrongExcess, BigInt(0)))
    )
  )

  property("faults lists exactly the shared rules a header breaks, in the order they run") {
    forAll(Rows) { (broken: String, above: Resolved, header: Resolved, expected: Vector[HeaderFault]) =>
      val listed = HeaderValidator.faults(header, above)
      assert(listed == expected, broken + ": listed " + listed.toString)
    }
  }

  property("validate refuses with the first fault faults lists, and accepts where it lists none") {
    forAll(Rows) { (broken: String, above: Resolved, header: Resolved, _: Vector[HeaderFault]) =>
      val first = HeaderValidator.faults(header, above).headOption
      val answered = HeaderValidator.validate(header, above)
      assert(
        answered == first.toLeft(()),
        broken + ": validate answered " + answered.toString + ", faults began " + first.toString
      )
    }
  }
