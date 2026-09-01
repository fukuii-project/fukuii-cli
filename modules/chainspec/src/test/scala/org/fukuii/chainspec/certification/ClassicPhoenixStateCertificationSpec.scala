package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}
import org.fukuii.chainspec.{Activation, Component, UpgradeRules}
import org.fukuii.chainspec.proposals.eip.{Eip1108, Eip1344, Eip152, Eip1884, Eip2028, Eip2200}
import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus}

/** Phoenix's rule set against Ethereum Classic's own state fixtures.
  *
  * ==This tree is uniform across labels, so every claim here is differential==
  *
  * The forty-five files state fifty-four expectations at this label and at
  * eight others, so the number of cases says nothing about this upgrade. What
  * says something is which of them answer DIFFERENTLY once a proposal is taken
  * away, and every count below is that measurement rather than a size.
  *
  * ==All six are covered, and the largest figure is the least specific==
  *
  * | proposal | cases whose verdict moves without it |
  * |---|---|
  * | EIP-152 | 1 |
  * | EIP-1108 | 3 |
  * | EIP-1344 | 1 |
  * | EIP-1884 | 8 |
  * | EIP-2028 | 14 |
  * | EIP-2200 | 6 |
  *
  * **No proposal reads zero**, which is the result that had to be measured
  * rather than argued: what a corpus MENTIONS and what it can DECIDE are
  * different claims, and a fixture naming an operation it does not price
  * decides nothing about the document that repriced it.
  *
  * **EIP-2028's fourteen is a fee-arithmetic sensitivity and not calldata
  * coverage.** That document takes the per-byte charge on non-zero call data
  * from 68 to 16, which every transaction carrying such a byte pays before it
  * executes anything -- so the fee changes, the coinbase balance the fee
  * creates changes, and the published root moves whatever the case was written
  * to test. Measured independently of the harness, by reading each entry's own
  * transaction payload out of the tree: **the fourteen that move are exactly
  * the fourteen entries whose selected `data` index holds at least one non-zero
  * byte**, with no entry moving without one and no entry carrying one and
  * staying put. So the widest column above is the one that says least about the
  * proposal it is named for, and a reader ranking the six by these figures
  * would rank them by call-data volume.
  *
  * ==The six do NOT partition these cases, which is where this tier differs
  * from the one below it==
  *
  * [[ClassicAghartaStateCertificationSpec]] measures three disjoint pairs and
  * asserts the partition. Here the six sets total thirty-three and their union
  * is twenty-seven, so **six cases are decided by two proposals apiece** and the
  * covering is asserted as sets rather than as a total. Three of the six
  * overlaps are EIP-2028 charging a fee on a case another proposal also prices;
  * the fourth and fifth are EIP-2028 and EIP-2200 over one contract-creation
  * pair; the sixth is EIP-1884 and EIP-2200 both moving what a code-hash read
  * costs. **A reader carrying the partition claim across from that file would
  * be carrying a property this fork does not have.**
  *
  * ==The removal is a recomposition, because adoption has no inverse==
  *
  * `org.fukuii.chainspec.UpgradeRules.adopting` takes arbitrary functions, so
  * nothing can undo one. Each differential below therefore rebuilds the rule
  * set from [[ethereumclassic.Upgrades.agharta]] with five of the six, and the
  * registration that makes those five-member builds mean anything is the one
  * asserting that the same recomposition with all SIX is the value the schedule
  * itself resolves at this height. Without it, six hand-built rule sets would be
  * measuring against a seventh nobody checked.
  *
  * ==What the fork-below control is worth, and where it is not independent==
  *
  * Twenty-seven of fifty-four move when this tree is resolved one upgrade
  * lower, so the tier is evidence about Phoenix rather than about rules Agharta
  * already satisfied. **The comparable figures are twenty-two at Atlantis and
  * six at Agharta, and NOT the fifty-one at Die Hard**, which measures something
  * else: this corpus re-signs each case per label, so a tier straddling that
  * fork's replay-protection boundary moves cases the rules never reached. These
  * three labels all sit above it and carry byte-identical transaction bytes at
  * the two heights, so what differs is what the rules do with them.
  *
  * **It is the same measurement [[ClassicAghartaStateCertificationSpec]] states
  * from the other side** as its own label-above control, so the two figures
  * agreeing is arithmetic and not corroboration. What is not shared is the
  * second route to it: removing all six proposals at this fork's own height
  * reaches the same twenty-seven cases, which holds because nothing sits between
  * the two upgrades on this schedule and would stop holding if anything did.
  *
  * ==What these expectations rest on, and where the chain stops==
  *
  * `fukuii-project/fukuii-tests`, branch `main`,
  * `networks/ethereumclassic/mainnet/state`, **whose tree object is
  * `fe6fec1d956c8bc870b0e4c8705855c0a3e79c62`** -- read at commit
  * `f9b67caad915fb4da58039da0de5ebf5cb812bef`. The tree is cited beside the
  * commit for the reason [[ClassicAtlantisStateCertificationSpec]] gives, and
  * this fork is where that reasoning earns itself: the commit the three lower
  * tiers name is four commits behind that one and its tree for this path is the
  * same object, so all four tiers demonstrably read the same forty-five files.
  *
  * [[ClassicStateCorpus]] states the oracle and its limits, which are this
  * tree's rather than this fork's and are not restated. **A pass here
  * establishes that this build and that one agree at 10,500,839. It does not
  * establish that either is right.**
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled, for the reason the Atlantis tier gives: a
  * cancelled test is counted by nothing, so a build whose corpus vanished
  * reports the same executed total as one that ran it.
  *
  * ==The figures are literals, so a corpus that shrank is a failure==
  *
  * Every count below is stated rather than derived from the run.
  */
class ClassicPhoenixStateCertificationSpec extends AnyFlatSpec:

  /** Files the tier states its cases in. */
  private val Files: Int = 45

  /** Runnable combinations across those files, at this label. */
  private val Cases: Int = 54

  /** Cases this build answers, which is every one the tier states here. */
  private val Certified: Int = 54

  /** The case that answers differently once the compression native leaves the
    * precompile set.
    *
    * One, and it is not one this tier can attribute to this proposal alone:
    * the same case moves when EIP-2028 is removed, because its transaction
    * carries call data. **The differential is still an attribution** -- with
    * the other five held at this fork, removing this proposal alone moves it --
    * and the overlap is what stops the six being a partition.
    */
  private val WithoutTheCompressionNativeTheseMove: Vector[String] =
    Vector("blake2fPrecompileAcrossUpgrades[d0g0v0]")

  /** Cases that answer differently once the curve natives keep their old
    * prices.
    *
    * Named rather than counted, for the reason the Agharta tier gives: a corpus
    * that lost one of these and gained an unrelated case would report the same
    * number. Only the pairing case is decided by this proposal alone; the
    * addition and multiplication cases carry call data and so move for EIP-2028
    * as well.
    */
  private val WithoutTheCurveRepriceTheseMove: Vector[String] =
    Vector(
      "bn128AddPrecompileAcrossUpgrades[d0g0v0]",
      "bn128MulPrecompileAcrossUpgrades[d0g0v0]",
      "bn128PairingPrecompileAcrossUpgrades[d0g0v0]"
    )

  /** The case that answers differently once the chain identifier leaves the
    * operation table.
    *
    * One, and it is the only published assertion this tier holds that this
    * network pushes its own identifier rather than the one it parted from.
    * `org.fukuii.chainspec.networks.SharedHistorySpec` asserts the machine
    * facets of the two networks EQUAL at this upgrade, which is only sound
    * because the identifier is carried on `org.fukuii.evm.Environment` rather
    * than in the rule set -- so this case is where the two claims meet.
    */
  private val WithoutTheChainIdentifierTheseMove: Vector[String] =
    Vector("chainid_opcode_across_upgrades[d0g0v0]")

  /** Cases that answer differently once state access keeps its old prices.
    *
    * Eight, seven of them decided by this proposal alone, which is the widest
    * exclusive coverage any of the six earns here. The document moves what a
    * balance read, a code-hash read and a storage read cost and adds the
    * operation that reads the executing account's own balance, and each of
    * those is a separate case below.
    */
  private val WithoutTheStateAccessRepriceTheseMove: Vector[String] =
    Vector(
      "coinbase_balance_access_cost_across_upgrades[d0g0v0]",
      "cold_account_access_cost_across_upgrades[d0g0v0]",
      "extcodehashSemanticsAcrossUpgrades[d0g0v0]",
      "extcodehash_availability[d0g0v0]",
      "revertUndoesWarmingAndRefunds[d0g0v0]",
      "selfbalance_availability[d0g0v0]",
      "sload_cost_across_upgrades[d0g0v0]",
      "staticFrameWarmsTheAccessList[d0g0v0]"
    )

  /** Cases that answer differently once call data keeps its old per-byte
    * charge.
    *
    * Fourteen, and the header states what they are: every entry in this tree
    * whose transaction carries a non-zero call-data byte, measured against the
    * tree's own payloads rather than inferred from these names. **Naming them
    * is what keeps the figure honest** -- a count of fourteen would survive the
    * corpus swapping one of these for an unrelated case, and the set would not.
    */
  private val WithoutTheCalldataRepriceTheseMove: Vector[String] =
    Vector(
      "blake2fPrecompileAcrossUpgrades[d0g0v0]",
      "bn128AddPrecompileAcrossUpgrades[d0g0v0]",
      "bn128MulPrecompileAcrossUpgrades[d0g0v0]",
      "calldataIntrinsicGasAcrossUpgrades[d1g0v0]",
      "codeDepositGasRuleAcrossUpgrades[d0g0v0]",
      "codeDepositGasRuleAcrossUpgrades[d1g0v0]",
      "codeSizeLimitAcrossUpgrades[d0g0v0]",
      "codeSizeLimitAcrossUpgrades[d1g0v0]",
      "create2AddressDerivationAcrossUpgrades[d1g0v0]",
      "initcodeLimitAndMeteringAcrossUpgrades[d0g0v0]",
      "initcodeLimitAndMeteringAcrossUpgrades[d1g0v0]",
      "modexpPrecompileAcrossUpgrades[d0g0v0]",
      "reservedCodePrefixAcrossUpgrades[d0g0v0]",
      "reservedCodePrefixAcrossUpgrades[d1g0v0]"
    )

  /** Cases that answer differently once storage is metered the old way.
    *
    * Six, three of them decided by this proposal alone -- the net-metering
    * case, the branch case over an originally-non-zero slot, and the
    * reentrancy sentry. **This is the composition this upgrade reaches from a
    * state no fork of this network was ever in**, having adopted neither
    * EIP-1283 nor EIP-1716, and these three are the whole of what this tier
    * says about it.
    */
  private val WithoutNetMeteredStorageTheseMove: Vector[String] =
    Vector(
      "codeDepositGasRuleAcrossUpgrades[d1g0v0]",
      "codeSizeLimitAcrossUpgrades[d1g0v0]",
      "extcodehashSemanticsAcrossUpgrades[d0g0v0]",
      "netStorageMeteringAcrossUpgrades[d0g0v0]",
      "sstoreOriginalNonZeroBranches[d0g0v0]",
      "sstoreReentrancySentryAcrossUpgrades[d0g0v0]"
    )

  /** The cases two of the six proposals each decide.
    *
    * Named because the covering is the finding: six sets totalling thirty-three
    * over a union of twenty-seven means exactly these six cases are reached
    * twice, and a count would not distinguish that from one case reached seven
    * times.
    */
  private val DecidedTwice: Vector[String] =
    Vector(
      "blake2fPrecompileAcrossUpgrades[d0g0v0]",
      "bn128AddPrecompileAcrossUpgrades[d0g0v0]",
      "bn128MulPrecompileAcrossUpgrades[d0g0v0]",
      "codeDepositGasRuleAcrossUpgrades[d1g0v0]",
      "codeSizeLimitAcrossUpgrades[d1g0v0]",
      "extcodehashSemanticsAcrossUpgrades[d0g0v0]"
    )

  /** Cases that answer differently once all six proposals leave at once, which
    * is the build that adopted nothing here.
    */
  private val MovedWithoutAllSix: Int = 27

  /** Cases that answer differently when this tier is resolved one fork lower.
    *
    * **Twenty-seven, where the same measurement at Agharta reads six and at
    * Atlantis twenty-two.** [[ClassicDieHardStateCertificationSpec]] reads
    * fifty-one and is not a fourth term in that series -- its boundary is where
    * this corpus starts re-signing each case, so its figure counts transactions
    * the rules never got to. The control the whole registration depends on: a
    * tier whose expectations are satisfied by the rules of the fork below it is
    * not evidence about the fork it is named for.
    */
  private val MovedAtTheForkBelow: Int = 27

  /** Cases that disagree when Phoenix's rules are asked the expectations the
    * fork below files.
    */
  private val DivergingAtTheLabelBelow: Int = 27

  /** Cases that disagree when Phoenix's rules are asked the expectations the
    * fork above files.
    *
    * Fifty-two of fifty-four, against twenty-seven at the fork below. The
    * upgrade above reprices state access for every account and slot a
    * transaction touches, so almost nothing in this tree survives being read
    * under its label -- which makes this boundary the easiest of the two to
    * separate and the less informative for it.
    */
  private val DivergingAtTheLabelAbove: Int = 52

  /** The label of the fork below this one. */
  private val LabelBelow: String = "ETC_Agharta"

  /** The label of the fork above this one. */
  private val LabelAbove: String = "ETC_Magneto"

  /** Files read when the label names an upgrade this corpus files nothing
    * under, and the cases each of them then declines to answer.
    *
    * The reader's own control: it dispatches on the post key, so a label the
    * corpus does not carry must report every file as stating no expectation
    * rather than silently matching something.
    */
  private val UnfilledLabel: String = "ETC_Thanos"

  private val report: CorpusReport =
    ClassicStateCorpus.phoenix.getOrElse(
      fail(
        "the network corpus was not found: set " + NetworkFixtureCorpus.RootVariable + " or write " +
          NetworkFixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  /** The same tree and label, resolved at `height` with `change` applied to
    * whatever the schedule answered there.
    */
  private def under(height: Long, change: UpgradeRules => UpgradeRules): CorpusReport =
    ClassicStateCorpus
      .reportAt("control", ClassicStateCorpus.PhoenixFork, height, change)
      .getOrElse(fail("assembled once and not the second time"))

  /** The same tree at another label, resolved at this fork's own height. */
  private def readAs(label: String): CorpusReport =
    ClassicStateCorpus
      .reportAt("control", label, ClassicStateCorpus.PhoenixStarts, identity)
      .getOrElse(fail("assembled once and not the second time"))

  /** Which cases answer differently under an altered run, by name.
    *
    * The pairing is checked before it is relied on, for the reason the Die Hard
    * tier states: `zip` truncates to the shorter side rather than complaining,
    * so a control yielding fewer outcomes would report a LOW count -- which
    * reads as a corpus that decides less rather than as a control that went
    * wrong.
    */
  private def moved(altered: CorpusReport): Vector[String] =
    if report.outcomes.map(_.name) != altered.outcomes.map(_.name) then
      fail(
        "the control did not answer for the same cases in the same order: " +
          report.casesFound.toString + " outcomes first and " + altered.casesFound.toString + " on the control"
      )
    else report.outcomes.zip(altered.outcomes).collect { case (before, after) if before != after => before.name }

  /** The one case in this build where a mutation is applied at this fork's own
    * height, which is where every differential below is read.
    */
  private def movedAtThisFork(change: UpgradeRules => UpgradeRules): Vector[String] =
    moved(under(ClassicStateCorpus.PhoenixStarts, change))

  /** The six this upgrade adopts, in the order the composition takes them. */
  private val TheSix: Vector[Component] =
    Vector(
      Eip152.component,
      Eip1108.component,
      Eip1344.component,
      Eip1884.component,
      Eip2028.component,
      Eip2200.component
    )

  /** The fork below with five of the six adopted.
    *
    * Adoption has no inverse -- a component is an arbitrary function over the
    * whole rule set -- so a proposal is withdrawn by rebuilding without it
    * rather than by undoing it. The registration asserting that all six
    * recomposed this way ARE the rules the schedule resolves here is what makes
    * each of these a statement about this upgrade.
    */
  private def without(dropped: Component): UpgradeRules => UpgradeRules =
    _ => ethereumclassic.Upgrades.agharta.adopting(TheSix.filterNot(_.id == dropped.id)*)

  private val withoutTheCompressionNative: UpgradeRules => UpgradeRules = without(Eip152.component)
  private val withoutTheCurveReprice: UpgradeRules => UpgradeRules = without(Eip1108.component)
  private val withoutTheChainIdentifier: UpgradeRules => UpgradeRules = without(Eip1344.component)
  private val withoutTheStateAccessReprice: UpgradeRules => UpgradeRules = without(Eip1884.component)
  private val withoutTheCalldataReprice: UpgradeRules => UpgradeRules = without(Eip2028.component)
  private val withoutNetMeteredStorage: UpgradeRules => UpgradeRules = without(Eip2200.component)

  /** All six withdrawn at once, which is the build that adopted nothing here. */
  private val withoutAllSix: UpgradeRules => UpgradeRules = _ => ethereumclassic.Upgrades.agharta

  "this chain's state tier at Phoenix" should "be read in full" in
    assert(
      report.filesRead == Files,
      "read " + report.filesRead.toString + " files rather than " + Files.toString + ": " + report.describe
    )

  it should "yield every case the tier states at this label" in
    assert(
      report.casesFound == Cases,
      "found " + report.casesFound.toString + " cases rather than " + Cases.toString + ": " + report.describe
    )

  it should "agree with every case it answers" in
    assert(report.diverged.isEmpty, report.describe)

  it should "answer the stated number of them" in
    assert(
      report.agreed.length == Certified,
      "certified " + report.agreed.length.toString + " rather than " + Certified.toString + ": " + report.describe
    )

  it should "skip nothing at all" in
    assert(
      report.skipped.isEmpty,
      "every file carries an expectation at this label, so a skip is a reader fault rather than a gap in the " +
        "corpus: " + report.describe
    )

  "the height this tier is resolved at" should "be an activation on this network's schedule" in {
    val schedule = KnownNetworks.registry.toOption
      .flatMap(_.at(ethereumclassic.Mainnet.network.chainId))
      .getOrElse(fail("this network is not in the registry"))
    assert(
      schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(ClassicStateCorpus.PhoenixStarts))),
      "resolving a corpus through a height no fork begins at certifies a neighboring fork's rules under this " +
        "one's name: " + schedule.forkPoints.toString
    )
  }

  "the harness" should "leave every case answered when nothing is altered" in
    assert(
      movedAtThisFork(identity).isEmpty,
      "the control path must not perturb the run, or every count below measures the control: " +
        under(ClassicStateCorpus.PhoenixStarts, identity).describe
    )

  it should "answer nothing at a label this corpus files no expectation under" in {
    val unfilled = readAs(UnfilledLabel)
    assert(
      unfilled.filesRead == report.filesRead && unfilled.agreed.isEmpty &&
        unfilled.skipped.length == unfilled.casesFound,
      "the reader dispatches on the post key, so an unfilled label must report every file as stating no " +
        "expectation rather than matching something: " + unfilled.describe
    )
  }

  "the six recomposed from the fork below" should "be the rules this schedule resolves here" in
    assert(
      ethereumclassic.Upgrades.agharta.adopting(TheSix*) == ethereumclassic.Upgrades.phoenix &&
        movedAtThisFork(_ => ethereumclassic.Upgrades.agharta.adopting(TheSix*)).isEmpty,
      "every differential below withdraws a proposal by rebuilding from the fork below, so a recomposition " +
        "that did not reproduce this upgrade's own rules would make all six measurements about some seventh " +
        "rule set: " + under(ClassicStateCorpus.PhoenixStarts, _ => ethereumclassic.Upgrades.agharta).describe
    )

  "a build without EIP-152" should "lose the case that calls the compression native, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheCompressionNative).sorted == WithoutTheCompressionNativeTheseMove,
      "one case, and it is also moved by the call-data reprice, so the tier detects this proposal missing " +
        "without being able to attribute that case to it alone: " +
        movedAtThisFork(withoutTheCompressionNative).mkString(", ")
    )

  "a build without EIP-1108" should "lose all three curve natives, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheCurveReprice).sorted == WithoutTheCurveRepriceTheseMove,
      "the pairing case is the only one this proposal decides alone, so a corpus losing it and keeping the " +
        "other two would report the same count over strictly weaker evidence: " +
        movedAtThisFork(withoutTheCurveReprice).mkString(", ")
    )

  "a build without EIP-1344" should "lose the case that reads the chain identifier, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheChainIdentifier).sorted == WithoutTheChainIdentifierTheseMove,
      "this is the only published assertion this tier holds that the identifier is this network's own: " +
        movedAtThisFork(withoutTheChainIdentifier).mkString(", ")
    )

  "a build without EIP-1884" should "lose all eight state-access cases, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheStateAccessReprice).sorted == WithoutTheStateAccessRepriceTheseMove,
      "seven of these are decided by this proposal alone, which is the widest exclusive coverage any of the " +
        "six earns here: " + movedAtThisFork(withoutTheStateAccessReprice).mkString(", ")
    )

  "a build without EIP-2028" should "lose every case whose transaction carries call data, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheCalldataReprice).sorted == WithoutTheCalldataRepriceTheseMove,
      "this set is the tree's non-zero-call-data entries rather than its call-data-semantics cases, so it is " +
        "the widest figure here and the least specific: " + movedAtThisFork(withoutTheCalldataReprice).mkString(", ")
    )

  "a build without EIP-2200" should "lose all six net-metering cases, named rather than counted" in
    assert(
      movedAtThisFork(withoutNetMeteredStorage).sorted == WithoutNetMeteredStorageTheseMove,
      "three of these are decided by this proposal alone, and they are the whole of what this tier says about " +
        "a metering this network reaches without ever having run its predecessor: " +
        movedAtThisFork(withoutNetMeteredStorage).mkString(", ")
    )

  "this upgrade's six proposals" should "cover twenty-seven cases, six of them twice" in {
    val each = Vector(
      movedAtThisFork(withoutTheCompressionNative).toSet,
      movedAtThisFork(withoutTheCurveReprice).toSet,
      movedAtThisFork(withoutTheChainIdentifier).toSet,
      movedAtThisFork(withoutTheStateAccessReprice).toSet,
      movedAtThisFork(withoutTheCalldataReprice).toSet,
      movedAtThisFork(withoutNetMeteredStorage).toSet
    )
    val union = each.foldLeft(Set.empty[String])(_ ++ _)
    val twice = union.filter(name => each.count(_.contains(name)) == 2)
    assert(
      union == movedAtThisFork(withoutAllSix).toSet && union.size == MovedWithoutAllSix &&
        twice.toVector.sorted == DecidedTwice && union.forall(name => each.count(_.contains(name)) <= 2),
      "the six are a covering rather than a partition here, so the overlap is asserted as a SET: union " +
        union.toVector.sorted.mkString(", ") + "; twice " + twice.toVector.sorted.mkString(", ")
    )
  }

  "a build that adopted none of the six" should "be refused rather than agreed with" in {
    val nothing = under(ClassicStateCorpus.PhoenixStarts, withoutAllSix)
    assert(
      nothing.diverged.length == MovedWithoutAllSix && nothing.agreed.length == Cases - MovedWithoutAllSix,
      "a harness whose only ever input is the answer it expects has no reachable failing state, so the tier " +
        "must refuse the rule set this upgrade replaced: " + nothing.describe
    )
  }

  "the two routes to the rules below this upgrade" should "reach the same cases" in
    assert(
      movedAtThisFork(withoutAllSix).toSet == moved(under(ClassicStateCorpus.AghartaStarts, identity)).toSet,
      "withdrawing all six at this height and resolving at the height below are the same rules only because " +
        "nothing sits between the two upgrades on this schedule, and an entry inserted between them should " +
        "break this rather than pass quietly: " + under(ClassicStateCorpus.AghartaStarts, identity).describe
    )

  "this tier resolved at the fork below" should "lose the cases that make it evidence about Phoenix" in
    assert(
      moved(under(ClassicStateCorpus.AghartaStarts, identity)).length == MovedAtTheForkBelow,
      "a tier satisfied by the rules of the fork below it says nothing about the fork it is named for, and " +
        "twenty-seven is the strongest such control among the labels that sit above the replay-protection " +
        "boundary this corpus re-signs at: " +
        under(ClassicStateCorpus.AghartaStarts, identity).describe
    )

  "this tier read under the label below" should "disagree with Phoenix's rules" in
    assert(
      readAs(LabelBelow).diverged.length == DivergingAtTheLabelBelow,
      "the label is what the reader dispatches on, so a tier agreeing with the fork below's expectations " +
        "under this fork's rules would not be evidence about either: " + readAs(LabelBelow).describe
    )

  "this tier read under the label above" should "disagree with Phoenix's rules" in
    assert(
      readAs(LabelAbove).diverged.length == DivergingAtTheLabelAbove,
      "the upgrade above reprices every account and slot a transaction touches, so this boundary is the " +
        "easier of the two to separate: " + readAs(LabelAbove).describe
    )
