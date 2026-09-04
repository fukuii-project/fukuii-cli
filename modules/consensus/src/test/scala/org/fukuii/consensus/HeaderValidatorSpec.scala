package org.fukuii.consensus

import org.fukuii.bytes.{Bytes, UInt256, UInt64}
import org.fukuii.chainspec.{FeeMarket, HeaderRules, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{BaseFeeTail, BlockHeader, BlockNonce, Bloom, Seal}
import org.scalatest.flatspec.AnyFlatSpec

/** What a header must satisfy against its parent, at and around a fee market.
  *
  * ==The figures are the fixture's own, so the arithmetic is checked against
  * something that was not derived here==
  *
  * The transition cases below use the gas limits from
  * `ethereum/legacytests` @ `1f581b8c`,
  * `Cancun/BlockchainTests/TransitionTests/bcBerlinToLondon/BerlinToLondonTransition.json`
  * -- a parent limit of 3,141,592, an accepted child at exactly twice that, and
  * three rejected siblings: two one unit outside the bound in each direction,
  * and one that is refused under a scaled parent and accepted under an unscaled
  * one. Using those rather than round numbers is what makes the boundary cases a
  * reading of the corpus rather than a restatement of this file's own
  * implementation.
  *
  * ==Every field no rule reads is left at its zero==
  *
  * Rather than invented, for the reason the proof-of-work fixtures give: a
  * plausible value in a field nothing reads suggests something stated one.
  */
class HeaderValidatorSpec extends AnyFlatSpec:

  /** A quantity as a header states it, or a failure naming what did not fit. */
  private def word(n: BigInt): UInt256 =
    UInt256.fromBigInt(n).getOrElse(throw new IllegalStateException("wider than a header can state: " + n.toString))

  private def count(n: BigInt): UInt64 =
    UInt64.fromBigInt(n).getOrElse(throw new IllegalStateException("wider than a header can count: " + n.toString))

  private val market: FeeMarket = FeeMarket(
    initialBaseFee = word(BigInt(1000000000)),
    elasticityMultiplier = BigInt(2),
    maxChangeDenominator = BigInt(8)
  )

  private val under: UpgradeRules = ethereum.Upgrades.berlin.copy(header = HeaderRules(Some(market)))

  private val below: UpgradeRules = ethereum.Upgrades.berlin

  private def headerOf(number: Long, gasLimit: BigInt, gasUsed: BigInt, baseFee: Option[BigInt]): BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = EvmFixtures.hash(0),
      beneficiary = EvmFixtures.address(0),
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = UInt256.Zero,
      number = count(BigInt(number)),
      gasLimit = count(gasLimit),
      gasUsed = count(gasUsed),
      // Derived from the number so every pair below satisfies succession by
      // construction. A case about the fee market must not be decided by a rule
      // it is not about.
      timestamp = count(BigInt(number) * 12),
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(EvmFixtures.hash(0), BlockNonce.Zero),
      tail = baseFee.map(f => BaseFeeTail(word(f)))
    )

  // Every val and def sits above the first test registration. Scala 3's
  // initialization checker reads a field declared below one as
  // read-before-init and reports it against the FIRST test in the class,
  // which is a diagnostic that names neither the field nor the ordering.

  private val FixtureParentLimit: BigInt = BigInt(3141592)
  private val FixtureScaled: BigInt = FixtureParentLimit * 2

  private def atTransition(childLimit: BigInt): Either[HeaderFault, Unit] =
    HeaderValidator.validate(
      Resolved(headerOf(5, childLimit, 0, Some(market.initialBaseFee.toBigInt)), under),
      Resolved(headerOf(4, FixtureParentLimit, 0, None), below)
    )

  // ── The market's own arithmetic, all three arms ───────────────────────────

  private val Target: BigInt = BigInt(1000000)
  private val Limit: BigInt = Target * 2
  private val ParentFee: BigInt = BigInt(1000)

  private def childUnder(gasUsedByParent: BigInt, statedFee: BigInt): Either[HeaderFault, Unit] =
    HeaderValidator.validate(
      Resolved(headerOf(2, Limit, 0, Some(statedFee)), under),
      Resolved(headerOf(1, Limit, gasUsedByParent, Some(ParentFee)), under)
    )

  "a block whose parent used exactly its target" should "state the parent's own charge" in
    assert(childUnder(Target, ParentFee) == Right(()), "at target the charge is unchanged")

  it should "be refused for stating anything else" in
    assert(
      childUnder(Target, ParentFee + 1).isLeft,
      "a charge one unit off the required one must be refused"
    )

  "a block whose parent exceeded its target" should "state a higher charge" in {
    // parent used the whole limit, so the delta is 1000 * 1000000 / 1000000 / 8.
    val expected = ParentFee + (ParentFee * (Limit - Target) / Target / market.maxChangeDenominator)
    assert(childUnder(Limit, expected) == Right(()), "above target the charge rises by the bounded fraction")
  }

  it should "rise by at least one where the computed rise rounds to nothing" in
    // One unit over target: the delta is 1000 * 1 / 1000000 / 8, which floors to
    // zero. The specification takes max(1, ...) on this arm alone, so the charge
    // cannot stall while blocks are over target.
    assert(
      childUnder(Target + 1, ParentFee + 1) == Right(()),
      "the rise is floored at one, which is what keeps the charge moving over target"
    )

  "a block whose parent fell short of its target" should "state a lower charge" in {
    val expected = ParentFee - (ParentFee * (Target - Target / 2) / Target / market.maxChangeDenominator)
    assert(childUnder(Target / 2, expected) == Right(()), "below target the charge falls by the bounded fraction")
  }

  it should "fall by nothing where the computed fall rounds to nothing, rather than by one" in
    // The asymmetry with the arm above, and it is deliberate in the
    // specification rather than an oversight to normalize: the fall carries no
    // floor, so one unit under target leaves the charge exactly where it was.
    assert(
      childUnder(Target - 1, ParentFee) == Right(()),
      "the fall has no floor, so a negligible shortfall moves the charge not at all"
    )

  // ── Presence and absence, which are two rules ─────────────────────────────

  "a block under a fee market" should "be refused for carrying no charge" in
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(2, Limit, 0, None), under),
        Resolved(headerOf(1, Limit, Target, Some(ParentFee)), under)
      ) == Left(HeaderFault.BaseFeeMissing),
      "a header under a market must state a charge"
    )

  "a block below any fee market" should "be refused for carrying a charge" in
    // The half a naive check drops. Asserting only that a market requires a
    // charge admits a block that states one before any market exists.
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(2, Limit, 0, Some(ParentFee)), below),
        Resolved(headerOf(1, Limit, 0, None), below)
      ) == Left(HeaderFault.BaseFeeUnexpected(word(ParentFee))),
      "a header below any market must state none"
    )

  it should "be accepted carrying none" in
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(2, Limit, 0, None), below),
        Resolved(headerOf(1, Limit, 0, None), below)
      ) == Right(()),
      "the ordinary pre-market case"
    )

  // ── The transition, on the corpus's own figures ───────────────────────────

  "the first block under a fee market" should "state the market's opening charge" in
    assert(atTransition(FixtureScaled) == Right(()), "there is no parent under the market to derive from")

  it should "be refused for deriving a charge from a parent that had none" in
    assert(
      atTransition(FixtureScaled).isRight &&
        HeaderValidator
          .validate(
            Resolved(headerOf(5, FixtureScaled, 0, Some(BigInt(1))), under),
            Resolved(headerOf(4, FixtureParentLimit, 0, None), below)
          )
          .isLeft,
      "only the opening charge is admissible at the first block"
    )

  it should "have its gas limit compared against a SCALED parent" in
    // The fixture's own accepted block: exactly twice the parent's limit. Under
    // an unscaled parent this is a step of 3,141,592 against a permitted 3,067
    // and would be refused.
    assert(atTransition(FixtureScaled) == Right(()), "the parent's limit is scaled by the elasticity multiplier")

  it should "refuse a limit one unit outside the bound above" in
    // 6,289,320 in the fixture, against a scaled parent of 6,283,184 whose
    // permitted step is 6,135. The comparison is strict, so a delta of exactly
    // 6,136 is refused.
    assert(atTransition(BigInt(6289320)).isLeft, "a step one unit past the bound is refused")

  it should "refuse a limit one unit outside the bound below" in
    assert(atTransition(BigInt(6277048)).isLeft, "and the same one unit under")

  it should "refuse the parent's own unscaled limit" in
    // The reading this build rejects, stated as a case: were the bound compared
    // against an unscaled parent, THIS would be the accepted value and the
    // fixture's accepted block would be refused. The two readings disagree on
    // both, which is what makes the corpus decisive rather than suggestive.
    assert(atTransition(FixtureParentLimit).isLeft, "an unscaled parent is not what the bound is taken against")

  // ── The bound away from a transition ──────────────────────────────────────

  "a block inside a fee market" should "have its gas limit compared against an UNSCALED parent" in
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(3, Limit, 0, Some(ParentFee)), under),
        Resolved(headerOf(2, Limit, Target, Some(ParentFee)), under)
      ) == Right(()),
      "the scaling belongs to the transition alone"
    )

  it should "be refused for a limit under the floor" in
    assert(
      HeaderValidator
        .validate(
          Resolved(headerOf(3, BigInt(4999), 0, Some(ParentFee)), under),

          Resolved(headerOf(2, BigInt(5000), 0, Some(ParentFee)), under)
        )
        .isLeft,
      "no gas limit may fall under the floor, whatever the step"
    )

  it should "refuse the corpus's own near-boundary sibling, which is the case that separates the readings" in
    // 3,144,650 is the sharpest value the fixture publishes and the earlier
    // cases did not use it. The two siblings at 6,289,320 and 6,277,048 are
    // refused under a scaled parent AND under an unscaled one, so they test the
    // bound's strictness rather than which bound is taken. This one is refused
    // only under a scaled parent: against the unscaled 3,141,592 its delta is
    // 3,058 against a permitted 3,067, so an unscaled implementation ACCEPTS it
    // and the corpus requires it rejected.
    assert(
      atTransition(BigInt(3144650)).isLeft,
      "the one published sibling whose verdict differs between the two readings"
    )

  // ── Succession, which needs neither a fork's rules nor an executed block ──

  "a block that is not its parent's successor" should "be refused" in
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(4, Limit, 0, None), below),
        Resolved(headerOf(1, Limit, 0, None), below)
      ) == Left(HeaderFault.NumberNotParentSuccessor(BigInt(4), BigInt(1))),
      "a header three blocks past its parent is not a successor"
    )

  "a block no later than its parent" should "be refused" in
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(2, Limit, 0, None).copy(timestamp = count(BigInt(12))), below),
        Resolved(headerOf(1, Limit, 0, None), below)
      ) == Left(HeaderFault.TimestampNotAfterParent(BigInt(12), BigInt(12))),
      "a block sharing its parent's timestamp is not later than it"
    )

  "a block claiming to have used more gas than it allowed itself" should "be refused" in
    // Two fields of one header. It is NOT the commitment this layer defers: that
    // one compares the figure against what execution produced and needs a run.
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(2, BigInt(30000), BigInt(30001), None), below),
        Resolved(headerOf(1, BigInt(30000), 0, None), below)
      ) == Left(HeaderFault.GasUsedAboveLimit(BigInt(30001), BigInt(30000))),
      "a header may not report using more than its own limit"
    )

  it should "be accepted using exactly its limit" in
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(2, BigInt(30000), BigInt(30000), None), below),
        Resolved(headerOf(1, BigInt(30000), 0, None), below)
      ) == Right(()),
      "the bound is inclusive, so a block using its whole limit is valid"
    )

  "a derivation wider than a header can state" should "be returned rather than raised" in {
    // The parent is not itself validated -- which is exactly the case a peer
    // supplies. Its charge sits near the top of the range and its gas use is at
    // its limit, so the successor's charge is a ninth larger and does not fit.
    // Raising here would let one header pair terminate a validating thread
    // through an API whose whole contract is that it returns a reason.
    val huge = (BigInt(2).pow(256) - 1)
    val parent = headerOf(1, BigInt(30000000), BigInt(30000000), Some(huge))
    val child = headerOf(2, BigInt(30000000), 0, Some(huge))
    assert(
      HeaderValidator.validate(Resolved(child, under), Resolved(parent, under)) match
        case Left(_: HeaderFault.BaseFeeNotRepresentable) => true
        case _                                            => false,
      "an overflowing derivation is a fault, never an exception"
    )
  }

  "a parent whose own limit is under the floor" should "be refused rather than derived from" in
    // Reachable by a narrow margin and the margin is the point: a parent at
    // 4,999 permits a step of 4, so a header at 5,000 satisfies the bound in
    // both directions AND clears the floor itself -- and only then does the
    // derivation see a parent that is not a valid header. Anything smaller is
    // refused by the bound first, which is why this case needed the numbers
    // rather than round ones.
    //
    // It exists so the derivation's totality does not rest on which check runs
    // first. Reordering the rules must not reopen a division by a parent's
    // target.
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(2, BigInt(5000), 0, Some(ParentFee)), under),
        Resolved(headerOf(1, BigInt(4999), 0, Some(ParentFee)), under)
      ) == Left(HeaderFault.ParentGasLimitBelowFloor(BigInt(4999), BigInt(5000))),
      "a parent under the floor is a fault named for the parent, not an arithmetic failure"
    )

  it should "not be refused for that reason when the parent clears the floor" in
    // The control. Same shape one unit up, so the case above cannot be passing
    // because of the bound or the charge.
    assert(
      HeaderValidator.validate(
        Resolved(headerOf(2, BigInt(5000), 0, Some(ParentFee)), under),
        Resolved(headerOf(1, BigInt(5000), 0, Some(ParentFee)), under)
      ) != Left(HeaderFault.ParentGasLimitBelowFloor(BigInt(5000), BigInt(5000))),
      "otherwise the assertion above holds for a reason that is not the floor"
    )
