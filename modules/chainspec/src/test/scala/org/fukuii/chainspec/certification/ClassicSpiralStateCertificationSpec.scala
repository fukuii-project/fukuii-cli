package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}
import org.fukuii.chainspec.proposals.eip.{Eip3651, Eip3855, Eip3860}
import org.fukuii.chainspec.{Activation, Component, UpgradeRules}
import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus}

/** Spiral's rule set against Ethereum Classic's own state fixtures.
  *
  * ==This tree is uniform across labels, so every claim here is differential==
  *
  * The forty-five files state fifty-four expectations at this label and at
  * eleven others -- every one of the forty-five carries all twelve, measured
  * across the tree -- so the number of cases says nothing about this upgrade.
  * What says something is which of them answer DIFFERENTLY once a proposal is
  * taken away, and every count below is that measurement rather than a size.
  *
  * ==All three are covered, and NINE of the fifty-four decide them==
  *
  * | proposal | cases whose verdict moves without it |
  * |---|---|
  * | EIP-3651 | 1 |
  * | EIP-3855 | 1 |
  * | EIP-3860 | 7 |
  *
  * **No proposal reads zero**, which is the result that had to be measured
  * rather than argued: what a corpus MENTIONS and what it can DECIDE are
  * different claims, and a fixture naming an operation it does not price
  * decides nothing about the document that repriced it.
  *
  * **Nine of fifty-four is the whole of what this tier decides.** The same
  * measurement over the same forty-five files reads six at
  * [[ClassicAghartaStateCertificationSpec]], twenty-two at
  * [[ClassicAtlantisStateCertificationSpec]], twenty-seven at
  * [[ClassicPhoenixStateCertificationSpec]] and fifty-one at
  * [[ClassicDieHardStateCertificationSpec]] -- so this is the second narrowest
  * of the five and not the narrowest, and the Die Hard figure is not a fifth
  * term in the series for the reason that tier gives. Forty-five of the
  * fifty-four cases here are satisfied by the rules of the fork below and are
  * not evidence about this upgrade; they are carried because the tree states one
  * body per label, not because this fork is certified by them.
  *
  * **The Agharta tier is the one to compare against**, being the other
  * three-proposal upgrade on this tree: six cases for its three against nine for
  * these three.
  *
  * **Two of the three rest on a single case each**, which is the narrowest base
  * a proposal can have here while still being decided at all --
  * [[ClassicPhoenixStateCertificationSpec]] carries two of its own, so the shape
  * is not peculiar to this fork. Each of those cases is nonetheless a probe
  * built for the rule rather than a case that happens to be sensitive to it:
  * the EIP-3651 case reads `BALANCE(COINBASE)` and asserts the gas that read
  * costs at every upgrade whose rules the generator can select, and the
  * EIP-3855 case runs `PUSH0`, discards its result and stores a non-zero marker,
  * so an empty slot is a claim of absence rather than a gap. **A single case is
  * still a single case**, and a corpus that lost either would report this tier
  * covering two proposals rather than three.
  *
  * ==The three PARTITION the nine, and neither tier below settles that for it==
  *
  * [[ClassicPhoenixStateCertificationSpec]] measures six sets totalling
  * thirty-three over a union of twenty-seven and asserts a covering. Here the
  * three sets total nine and their union is nine, so **no case is decided by
  * two proposals** and the partition is asserted as a total as well as a set.
  *
  * **The two nearest tiers below disagree about this, so neither shape carries
  * across on its own.** [[ClassicAghartaStateCertificationSpec]] asserts a
  * partition over three proposals; [[ClassicPhoenixStateCertificationSpec]]
  * asserts a covering over six and says in terms that a reader carrying the
  * partition across would be carrying a property that fork does not have. This
  * fork has it, and it is measured here rather than inherited from either.
  *
  * ==EIP-3860's seven are bounded by creation, and discriminate within it==
  *
  * That document charges two gas for each thirty-two-byte word of initcode and
  * caps initcode at 49,152 bytes, so only a case that creates a contract can
  * feel it. Measured independently of the harness, by disassembling every
  * account's pre-state code in the tree and skipping `PUSH` data rather than
  * matching bytes: **exactly five cases, carrying ten entries at this label,
  * execute `CREATE` or `CREATE2`, and seven of those ten move.**
  *
  * **The three that do not move are the rejected halves of three control
  * pairs** -- `codeDepositGasRuleAcrossUpgrades[d1g0v0]`,
  * `codeSizeLimitAcrossUpgrades[d1g0v0]` and
  * `reservedCodePrefixAcrossUpgrades[d1g0v0]` -- each already refused under both
  * rule sets for a reason older than this proposal. So the figure is narrower
  * than "every creation in the tree" rather than wider than the rule, which is
  * the opposite of the reading [[ClassicPhoenixStateCertificationSpec]] records
  * for its own widest column.
  *
  * **It is also the only one of the three whose two facets are both decided.**
  * `org.fukuii.chainspec.proposals.eip.Eip3860` carries `initcodeBound` and
  * `initcodeMetering`, and `accounts/initcode_limit_and_metering.json` moves at
  * both of its entries between the two labels: index 0 creates from a span of
  * 49,152 bytes and index 1 from one byte more, and each states a different root
  * at this label than at the one below.
  *
  * **What that file says about the SIZE of the charge is its own prose and not
  * something this tier checks.** Its `_info.comment` states 43,251 gas below
  * against 46,323 here, a difference of 3,072 that is two gas for each of the
  * span's 1,536 words. A `post` entry carries a state root and no gas figure, so
  * the harness decides that the two roots differ and never that they differ by
  * that amount. The arithmetic is recorded because it is what makes the fixture
  * checkable by hand; it is the corpus's claim, carried as one.
  *
  * ==The removal is a recomposition, because adoption has no inverse==
  *
  * `org.fukuii.chainspec.UpgradeRules.adopting` takes arbitrary functions, so
  * nothing can undo one. Each differential below therefore rebuilds the rule set
  * from [[ethereumclassic.Upgrades.mystique]] with two of the three, and the
  * registration that makes those two-member builds mean anything is the one
  * asserting that the same recomposition with all THREE is the value the
  * schedule itself resolves at this height. Without it, three hand-built rule
  * sets would be measuring against a fourth nobody checked.
  *
  * ==What the fork-below control is worth==
  *
  * Nine of fifty-four move when this tree is resolved one upgrade lower, so the
  * tier is evidence about Spiral rather than about rules Mystique already
  * satisfied -- and nine is the whole of that evidence. **The second route
  * reaches the same nine**: withdrawing all three proposals at this fork's own
  * height, which holds because nothing sits between the two upgrades on this
  * schedule and would stop holding if anything did.
  *
  * ==There is no label above this one, and the substitute is stated as one==
  *
  * Spiral is the last entry on this network's schedule, so the label-above
  * control the tiers below carry does not exist here. What stands in its place
  * is a second label BELOW -- `ETC_Magneto`, two upgrades down, whose
  * expectations diverge in fifteen cases against the adjacent label's nine.
  * **That is a weaker control and is recorded as one**: it establishes that the
  * reader dispatches on the label and that divergence grows with distance, and
  * it cannot establish that this fork's rules are separable from a fork above,
  * because none is scheduled.
  *
  * ==What these expectations rest on, and where the chain stops==
  *
  * `fukuii-project/fukuii-tests`, branch `main`,
  * `networks/ethereumclassic/mainnet/state`, **whose tree object is
  * `38617c8dc4aaac854a3eb51ad0a575080a976d72`** -- read at commit
  * `067ab0adf39e7bcc51a029eb5eb8365137af8546`. The tree is cited beside the
  * commit for the reason [[ClassicAtlantisStateCertificationSpec]] gives. **It
  * is not the tree object the four tiers below read**, and the difference is
  * one file and no expectation: `git diff` between the two commits over this
  * path reports a single insertion and a single deletion, both the
  * `_info.rejection-label` prose of `accounts/typed_transaction_access_list.json`.
  * So all five tiers read the same fifty-four expectations, and this tier says
  * so from a measurement rather than from the tree objects matching.
  *
  * [[ClassicStateCorpus]] states the oracle and its limits, which are this
  * tree's rather than this fork's and are not restated -- including that eight
  * of the forty-five are filled through the same runner that would otherwise be
  * their second opinion. **A pass here establishes that this build and that one
  * agree at 19,250,000. It does not establish that either is right.**
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than canceled, for the reason the Atlantis tier gives: a
  * canceled test is counted by nothing, so a build whose corpus vanished
  * reports the same executed total as one that ran it.
  *
  * ==The figures are literals, so a corpus that shrank is a failure==
  *
  * Every count below is stated rather than derived from the run.
  */
class ClassicSpiralStateCertificationSpec extends AnyFlatSpec:

  /** Files the tier states its cases in. */
  private val Files: Int = 45

  /** Runnable combinations across those files, at this label. */
  private val Cases: Int = 54

  /** Cases this build answers, which is every one the tier states here. */
  private val Certified: Int = 54

  /** The case that answers differently once the block's beneficiary stops
    * starting warm.
    *
    * One, and it is decided by this proposal alone. The body reads
    * `BALANCE(COINBASE)` and stores what that read cost, and it reaches the
    * operand with `PUSH1 0x00` rather than `PUSH0` so that the same body is
    * valid at every upgrade -- which is what keeps this case independent of
    * EIP-3855 and is why the two sets below do not intersect.
    */
  private val WithoutTheWarmBeneficiaryTheseMove: Vector[String] =
    Vector("coinbase_balance_access_cost_across_upgrades[d0g0v0]")

  /** The case that answers differently once the zero push leaves the operation
    * table.
    *
    * One, and it is decided by this proposal alone. The probe runs the opcode,
    * discards its result and stores a non-zero marker, so a written slot means
    * the operation executed and an empty one means it did not exist. Storing
    * the operation's own result would not distinguish the two, since zero into
    * an already-zero slot cannot be told from never storing.
    */
  private val WithoutTheZeroPushTheseMove: Vector[String] =
    Vector("push0_availability[d0g0v0]")

  /** Cases that answer differently once initcode is neither bounded nor
    * metered.
    *
    * Seven, all decided by this proposal alone. The header states what they
    * are: seven of the ten entries in this tree that execute `CREATE` or
    * `CREATE2`, measured against the tree's own pre-state code rather than
    * inferred from these names. **Naming them is what keeps the figure
    * honest** -- a count of seven would survive the corpus swapping one of
    * these for an unrelated case, and the set would not.
    */
  private val WithoutInitcodeBoundAndMeteringTheseMove: Vector[String] =
    Vector(
      "codeDepositGasRuleAcrossUpgrades[d0g0v0]",
      "codeSizeLimitAcrossUpgrades[d0g0v0]",
      "create2AddressDerivationAcrossUpgrades[d0g0v0]",
      "create2AddressDerivationAcrossUpgrades[d1g0v0]",
      "initcodeLimitAndMeteringAcrossUpgrades[d0g0v0]",
      "initcodeLimitAndMeteringAcrossUpgrades[d1g0v0]",
      "reservedCodePrefixAcrossUpgrades[d0g0v0]"
    )

  /** Cases that answer differently once all three proposals leave at once,
    * which is the build that adopted nothing here.
    */
  private val MovedWithoutAllThree: Int = 9

  /** Cases that answer differently when this tier is resolved one fork lower.
    *
    * Nine, where the same measurement at Phoenix reads twenty-seven over the
    * same files. The control the whole registration depends on: a tier whose
    * expectations are satisfied by the rules of the fork below it is not
    * evidence about the fork it is named for.
    */
  private val MovedAtTheForkBelow: Int = 9

  /** Cases that disagree when Spiral's rules are asked the expectations the
    * fork below files.
    */
  private val DivergingAtTheLabelBelow: Int = 9

  /** Cases that disagree when Spiral's rules are asked the expectations two
    * forks below.
    *
    * Fifteen, against nine at the adjacent label. This stands in for the
    * label-above control the tiers below carry and this one cannot have, and
    * the header records that it is the weaker of the two.
    */
  private val DivergingTwoLabelsBelow: Int = 15

  /** The label of the fork below this one. */
  private val LabelBelow: String = "ETC_Mystique"

  /** The label of the fork two below this one. */
  private val LabelTwoBelow: String = "ETC_Magneto"

  /** Files read when the label names an upgrade this corpus files nothing
    * under, and the cases each of them then declines to answer.
    *
    * The reader's own control: it dispatches on the post key, so a label the
    * corpus does not carry must report every file as stating no expectation
    * rather than silently matching something.
    */
  private val UnfilledLabel: String = "ETC_Thanos"

  private val report: CorpusReport =
    ClassicStateCorpus.spiral.getOrElse(
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
      .reportAt("control", ClassicStateCorpus.SpiralFork, height, change)
      .getOrElse(fail("assembled once and not the second time"))

  /** The same tree at another label, resolved at this fork's own height. */
  private def readAs(label: String): CorpusReport =
    ClassicStateCorpus
      .reportAt("control", label, ClassicStateCorpus.SpiralStarts, identity)
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
    moved(under(ClassicStateCorpus.SpiralStarts, change))

  /** The three this upgrade adopts, in the order the composition takes them. */
  private val TheThree: Vector[Component] =
    Vector(Eip3651.component, Eip3855.component, Eip3860.component)

  /** The fork below with two of the three adopted.
    *
    * Adoption has no inverse -- a component is an arbitrary function over the
    * whole rule set -- so a proposal is withdrawn by rebuilding without it
    * rather than by undoing it. The registration asserting that all three
    * recomposed this way ARE the rules the schedule resolves here is what makes
    * each of these a statement about this upgrade.
    */
  private def without(dropped: Component): UpgradeRules => UpgradeRules =
    _ => ethereumclassic.Upgrades.mystique.adopting(TheThree.filterNot(_.id == dropped.id)*)

  private val withoutTheWarmBeneficiary: UpgradeRules => UpgradeRules = without(Eip3651.component)
  private val withoutTheZeroPush: UpgradeRules => UpgradeRules = without(Eip3855.component)
  private val withoutInitcodeBoundAndMetering: UpgradeRules => UpgradeRules = without(Eip3860.component)

  /** All three withdrawn at once, which is the build that adopted nothing
    * here.
    */
  private val withoutAllThree: UpgradeRules => UpgradeRules = _ => ethereumclassic.Upgrades.mystique

  "this chain's state tier at Spiral" should "be read in full" in
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
      schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(ClassicStateCorpus.SpiralStarts))),
      "resolving a corpus through a height no fork begins at certifies a neighboring fork's rules under this " +
        "one's name: " + schedule.forkPoints.toString
    )
  }

  "the harness" should "leave every case answered when nothing is altered" in
    assert(
      movedAtThisFork(identity).isEmpty,
      "the control path must not perturb the run, or every count below measures the control: " +
        under(ClassicStateCorpus.SpiralStarts, identity).describe
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

  "the three recomposed from the fork below" should "be the rules this schedule resolves here" in
    assert(
      ethereumclassic.Upgrades.mystique.adopting(TheThree*) == ethereumclassic.Upgrades.spiral &&
        movedAtThisFork(_ => ethereumclassic.Upgrades.mystique.adopting(TheThree*)).isEmpty,
      "every differential below withdraws a proposal by rebuilding from the fork below, so a recomposition " +
        "that did not reproduce this upgrade's own rules would make all three measurements about some fourth " +
        "rule set: " + under(ClassicStateCorpus.SpiralStarts, _ => ethereumclassic.Upgrades.mystique).describe
    )

  "a build without EIP-3651" should "lose the case that reads the beneficiary's balance, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheWarmBeneficiary).sorted == WithoutTheWarmBeneficiaryTheseMove,
      "one case, and it is the whole of what this tier says about the proposal, so a corpus losing it would " +
        "report this fork covering two proposals rather than three: " +
        movedAtThisFork(withoutTheWarmBeneficiary).mkString(", ")
    )

  "a build without EIP-3855" should "lose the case that runs the zero push, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheZeroPush).sorted == WithoutTheZeroPushTheseMove,
      "one case, and its empty expectation at the upgrades below is a claim of absence rather than a gap, " +
        "which is what makes a single case worth this much here: " +
        movedAtThisFork(withoutTheZeroPush).mkString(", ")
    )

  "a build without EIP-3860" should "lose seven of the ten creating cases, named rather than counted" in
    assert(
      movedAtThisFork(withoutInitcodeBoundAndMetering).sorted == WithoutInitcodeBoundAndMeteringTheseMove,
      "this set is seven of the tree's ten creation entries rather than all of them, the three left being " +
        "halves of control pairs already refused for older reasons: " +
        movedAtThisFork(withoutInitcodeBoundAndMetering).mkString(", ")
    )

  "this upgrade's three proposals" should "partition nine cases, none of them decided twice" in {
    val each = Vector(
      movedAtThisFork(withoutTheWarmBeneficiary).toSet,
      movedAtThisFork(withoutTheZeroPush).toSet,
      movedAtThisFork(withoutInitcodeBoundAndMetering).toSet
    )
    val union = each.foldLeft(Set.empty[String])(_ ++ _)
    assert(
      union == movedAtThisFork(withoutAllThree).toSet && union.size == MovedWithoutAllThree &&
        each.map(_.size).sum == MovedWithoutAllThree && union.forall(name => each.count(_.contains(name)) == 1),
      "the three are a partition here rather than the covering the fork below has, so the disjointness is " +
        "asserted as a total AND as a per-case count: union " + union.toVector.sorted.mkString(", ") +
        "; sizes " + each.map(_.size).mkString(", ")
    )
  }

  "a build that adopted none of the three" should "be refused rather than agreed with" in {
    val nothing = under(ClassicStateCorpus.SpiralStarts, withoutAllThree)
    assert(
      nothing.diverged.length == MovedWithoutAllThree && nothing.agreed.length == Cases - MovedWithoutAllThree,
      "a harness whose only ever input is the answer it expects has no reachable failing state, so the tier " +
        "must refuse the rule set this upgrade replaced: " + nothing.describe
    )
  }

  "the two routes to the rules below this upgrade" should "reach the same cases" in
    assert(
      movedAtThisFork(withoutAllThree).toSet == moved(under(ClassicStateCorpus.MystiqueStarts, identity)).toSet,
      "withdrawing all three at this height and resolving at the height below are the same rules only because " +
        "nothing sits between the two upgrades on this schedule, and an entry inserted between them should " +
        "break this rather than pass quietly: " + under(ClassicStateCorpus.MystiqueStarts, identity).describe
    )

  "this tier resolved at the fork below" should "lose the cases that make it evidence about Spiral" in
    assert(
      moved(under(ClassicStateCorpus.MystiqueStarts, identity)).length == MovedAtTheForkBelow,
      "a tier satisfied by the rules of the fork below it says nothing about the fork it is named for, and " +
        "nine is the whole of this tier's evidence -- the second narrowest of the five over these files, " +
        "against six at Agharta and twenty-seven at Phoenix: " +
        under(ClassicStateCorpus.MystiqueStarts, identity).describe
    )

  "this tier read under the label below" should "disagree with Spiral's rules" in
    assert(
      readAs(LabelBelow).diverged.length == DivergingAtTheLabelBelow,
      "the label is what the reader dispatches on, so a tier agreeing with the fork below's expectations " +
        "under this fork's rules would not be evidence about either: " + readAs(LabelBelow).describe
    )

  "this tier read under the label two below" should "disagree further still" in
    assert(
      readAs(LabelTwoBelow).diverged.length == DivergingTwoLabelsBelow,
      "no fork is scheduled above this one, so a second label BELOW stands in for the label-above control " +
        "the tiers under this one carry, and it is the weaker of the two: " + readAs(LabelTwoBelow).describe
    )
