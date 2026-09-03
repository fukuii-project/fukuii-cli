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
  * two rejected siblings one unit outside the bound in each direction. Using
  * those rather than round numbers is what makes the boundary cases a reading of
  * the corpus rather than a restatement of this file's own implementation.
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
      timestamp = UInt64.Zero,
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
      headerOf(5, childLimit, 0, Some(market.initialBaseFee.toBigInt)),
      headerOf(4, FixtureParentLimit, 0, None),
      under,
      below
    )

  // ── The market's own arithmetic, all three arms ───────────────────────────

  private val Target: BigInt = BigInt(1000000)
  private val Limit: BigInt = Target * 2
  private val ParentFee: BigInt = BigInt(1000)

  private def childUnder(gasUsedByParent: BigInt, statedFee: BigInt): Either[HeaderFault, Unit] =
    HeaderValidator.validate(
      headerOf(2, Limit, 0, Some(statedFee)),
      headerOf(1, Limit, gasUsedByParent, Some(ParentFee)),
      under,
      under
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
        headerOf(2, Limit, 0, None),
        headerOf(1, Limit, Target, Some(ParentFee)),
        under,
        under
      ) == Left(HeaderFault.BaseFeeMissing),
      "a header under a market must state a charge"
    )

  "a block below any fee market" should "be refused for carrying a charge" in
    // The half a naive check drops. Asserting only that a market requires a
    // charge admits a block that states one before any market exists.
    assert(
      HeaderValidator.validate(
        headerOf(2, Limit, 0, Some(ParentFee)),
        headerOf(1, Limit, 0, None),
        below,
        below
      ) == Left(HeaderFault.BaseFeeUnexpected(word(ParentFee))),
      "a header below any market must state none"
    )

  it should "be accepted carrying none" in
    assert(
      HeaderValidator.validate(headerOf(2, Limit, 0, None), headerOf(1, Limit, 0, None), below, below) == Right(()),
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
            headerOf(5, FixtureScaled, 0, Some(BigInt(1))),
            headerOf(4, FixtureParentLimit, 0, None),
            under,
            below
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
        headerOf(3, Limit, 0, Some(ParentFee)),
        headerOf(2, Limit, Target, Some(ParentFee)),
        under,
        under
      ) == Right(()),
      "the scaling belongs to the transition alone"
    )

  it should "be refused for a limit under the floor" in
    assert(
      HeaderValidator
        .validate(
          headerOf(3, BigInt(4999), 0, Some(ParentFee)),
          headerOf(2, BigInt(5000), 0, Some(ParentFee)),
          under,
          under
        )
        .isLeft,
      "no gas limit may fall under the floor, whatever the step"
    )
