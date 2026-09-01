package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}
import org.fukuii.chainspec.proposals.eip.{Eip1108, Eip1344, Eip152, Eip1884, Eip2028, Eip2200}
import org.fukuii.chainspec.{Activation, Component, UpgradeRules}
import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus, SkipReason, Verdict}

/** Phoenix's rule set against Ethereum Classic's own published state tier.
  *
  * ==What this tier adds that the authored one cannot==
  *
  * [[ClassicPhoenixStateCertificationSpec]] reads forty-five files uniform
  * across nine labels, so every claim it makes is differential and its scale is
  * fixed by how many cases were written. This one reads a published tree and is
  * the other shape: deep at this label and shallow below it, so its claim is
  * agreement at scale rather than attribution per proposal.
  *
  * **The two are near-complements and neither subsumes the other.** The
  * authored tier attributes cases to individual proposals; this one runs two
  * orders of magnitude more entries against an oracle that predates this build,
  * where the authored tier's oracle is a client this project has worked on.
  * [[ClassicPublishedStateCorpus]] states that oracle chain and its limits.
  *
  * ==Why the differentials here are two runs and not seven==
  *
  * Every differential costs a full pass over the tier, and those passes are the
  * whole of what this file adds to a default run. The authored tier buys six
  * per-proposal attributions for six passes over fifty-four cases; the same six
  * here would cost six passes over two and a half thousand entries to answer a
  * question that tier already answers. **So this file asserts one proposal by
  * name and the upgrade as a whole by number**, which is a cost decision rather
  * than a coverage finding -- the six are separable here, and separating them is
  * what a later phase can spend the runs on.
  *
  * The one asserted by name is the chain identifier, because it is the only
  * proposal whose evidence this tier holds and no other corpus in this build
  * can: every entry read here is signed under EIP-155 naming chain 61, so the
  * assertion that this network's own identifier is what `CHAINID` pushes rests
  * on transactions no other network could have submitted.
  *
  * ==Five entries diverge, and they are stated rather than excluded==
  *
  * A tier that passes because its failures were removed from it is worth less
  * than no tier, so the five are run, named, and asserted. **They are one
  * finding rather than five**: each is a creation whose target account carries
  * storage while holding no code and a zero count, which
  * `org.fukuii.evm.Interpreter.deployableAt` refuses and this tree's filling
  * client did not.
  *
  * The attribution is measured rather than inferred. Exactly three cases in the
  * registered directories hold such an account in their pre-state; the two whose
  * account is a creation target are the two that diverge, and the third --
  * `extcodehashEmpty`, whose account is only ever read -- agrees. So the shape
  * predicts the divergence and being a creation target is what selects it.
  *
  * **It is not an artifact of this tree's relabelling, which is the first thing
  * to rule out and the cheapest to get wrong.** Both entries do sit on
  * relabelled fillers rather than hand-written ones. But a relabelled filler is
  * still filled by an Ethereum Classic client at Ethereum Classic's rules, so
  * what it records is what that client answered -- and the rule at issue is one
  * that postdates the filling, not one the other network had and this one did
  * not. The discriminator is the filling client's vintage rather than the label.
  *
  * **The divergence traces to the corpus rather than to this build, and a
  * corpus this build already certifies is what establishes that.**
  * `ethereum/legacytests` carries its own `stSStoreTest/InitCollision.json` and
  * `stExtCodeHash/dynamicAccountOverwriteEmpty.json` covering the same scenario,
  * refilled by a later client, and both expect the refusal this build performs;
  * both are read by [[CertificationCorpora]] and both agree. This tree's entries
  * were filled by a client predating that rule. So the two published corpora
  * disagree with each other here, and this build is on the side of the newer
  * one -- which is a reason to record the five rather than to chase them.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled: a cancelled test is counted by nothing, so a
  * build whose corpus vanished reports the same executed total as one that ran
  * it.
  *
  * ==The figures are literals, so a tier that shrank is a failure==
  */
class ClassicPublishedPhoenixStateCertificationSpec extends AnyFlatSpec:

  /** Files the registered directories hold. */
  private val Files: Int = 465

  /** Outcomes across them: one per entry stated at this label, plus one per case
    * stating nothing here.
    */
  private val Outcomes: Int = 2524

  /** Entries this build answers as the tree states them. */
  private val Certified: Int = 2510

  /** Cases that state no expectation at this label, named rather than counted.
    *
    * Nine, and naming them is what makes the skip count evidence: a tier that
    * lost one of these and gained an unrelated silent case would report the same
    * number. Each of these files states expectations only at labels above this
    * one, or -- in one case -- only at the earliest label of all.
    */
  private val StatingNothingHere: Vector[String] =
    Vector(
      "CallEcrecover_Overflow",
      "diffPlaces",
      "ecrecoverShortBuff",
      "ecrecoverWeirdV",
      "modexpTests",
      "precompsEIP2929",
      "refundFF",
      "refundMax",
      "refundSSTORE"
    )

  /** The entries this build answers differently from the tree, named rather
    * than counted.
    *
    * All five are one rule read two ways, and the header states which. A count
    * would pass a mutation that swapped one of these for an unrelated entry,
    * which is the whole reason a known divergence is recorded as a SET.
    */
  private val KnownDivergences: Vector[String] =
    Vector(
      "InitCollision[d0g0v0]",
      "InitCollision[d1g0v0]",
      "InitCollision[d2g0v0]",
      "InitCollision[d3g0v0]",
      "dynamicAccountOverwriteEmpty[d0g0v0]"
    )

  /** What every one of those divergences is, which is as much a part of the
    * finding as which entries they are.
    *
    * A creation refused leaves the sender charged and the target untouched, so
    * the disagreement surfaces as a post-state root and not as a receipt or a
    * halt. If one of these ever diverged for another reason the finding would
    * have changed underneath this file, and a set of names alone would not say
    * so.
    */
  private val DivergenceReason: String = "state root "

  /** The entries that answer differently once the chain identifier leaves the
    * operation table, named rather than counted.
    *
    * Two, and they are the only published assertion available to this build
    * that this network pushes its own identifier rather than the one it parted
    * from. Every entry in this tier is signed for chain 61, so no other corpus
    * here could stand in for them.
    */
  private val WithoutTheChainIdentifierTheseMove: Vector[String] =
    Vector("chainIdGasCost[d0g0v0]", "chainId[d0g0v0]").sorted

  /** Entries the tree's expectations still satisfy once all six proposals are
    * withdrawn.
    *
    * ==The one figure here that is a number rather than a set, and why==
    *
    * Withdrawing all six moves most of the tier, so neither the moved set nor
    * its complement is small enough to name and a count is what is left. It is
    * recorded as a count knowingly: a mutation swapping one moved entry for
    * another would pass it, and the chain-identifier differential above is the
    * assertion that would not.
    *
    * What the pair of figures is for is the control the whole tier depends on --
    * a tier whose expectations the fork below already satisfies is not evidence
    * about the fork it is named for. Here the fork below satisfies fewer than a
    * third of them.
    */
  private val AgreeingWithoutAllSix: Int = 756

  /** Entries the fork below's rules are refused by. */
  private val RefusedWithoutAllSix: Int = 1759

  /** A label this network's schedule carries and this tree files nothing under.
    *
    * The reader's own negative control, against the run itself as the positive
    * one: the reader dispatches on the post key, so a label the tree does not
    * use must report every file as stating no expectation rather than silently
    * matching something.
    */
  private val UnfilledLabel: String = "ETC_Thanos"

  private val report: CorpusReport =
    ClassicPublishedStateCorpus.phoenix.getOrElse(
      fail(
        "the published corpus was not found: set " + FixtureCorpus.RootVariable + " or write " +
          FixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  /** The same directories and label, resolved at `height` with `change` applied
    * to whatever the schedule answered there.
    */
  private def under(height: Long, change: UpgradeRules => UpgradeRules): CorpusReport =
    ClassicPublishedStateCorpus
      .reportAt("control", ClassicPublishedStateCorpus.PhoenixFork, height, change)
      .getOrElse(fail("assembled once and not the second time"))

  /** The same directories at another label, resolved at this fork's own height.
    *
    * Decode-only where the label is one the tree does not carry, which is why
    * the unfilled control costs nothing beside a differential.
    */
  private def readAs(label: String): CorpusReport =
    ClassicPublishedStateCorpus
      .reportAt("control", label, ClassicPublishedStateCorpus.PhoenixStarts, identity)
      .getOrElse(fail("assembled once and not the second time"))

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
    * recomposed this way are the rules the schedule resolves here is what makes
    * this a statement about this upgrade.
    */
  private def without(dropped: Component): UpgradeRules => UpgradeRules =
    _ => ethereumclassic.Upgrades.agharta.adopting(TheSix.filterNot(_.id == dropped.id)*)

  private val withoutTheChainIdentifier: UpgradeRules => UpgradeRules = without(Eip1344.component)

  /** The rules this upgrade replaced, which is the build that adopted none of
    * the six.
    */
  private val withoutAllSix: UpgradeRules => UpgradeRules = _ => ethereumclassic.Upgrades.agharta

  /** The two differentials, computed once each because a pass over this tier is
    * the whole of what this file costs.
    */
  private lazy val adoptingNothing: CorpusReport =
    under(ClassicPublishedStateCorpus.PhoenixStarts, withoutAllSix)

  private lazy val withoutChainIdentifier: CorpusReport =
    under(ClassicPublishedStateCorpus.PhoenixStarts, withoutTheChainIdentifier)

  /** Which entries answer differently under an altered run, by name.
    *
    * The pairing is checked before it is relied on: `zip` truncates to the
    * shorter side rather than complaining, so a control yielding fewer outcomes
    * would report a LOW count -- which reads as a tier that decides less rather
    * than as a control that went wrong.
    */
  private def moved(altered: CorpusReport): Vector[String] =
    if report.outcomes.map(_.name) != altered.outcomes.map(_.name) then
      fail(
        "the control did not answer for the same entries in the same order: " +
          report.casesFound.toString + " outcomes first and " + altered.casesFound.toString + " on the control"
      )
    else report.outcomes.zip(altered.outcomes).collect { case (before, after) if before != after => before.name }

  "this chain's published state tier at Phoenix" should "be read in full" in
    assert(
      report.filesRead == Files,
      "read " + report.filesRead.toString + " files rather than " + Files.toString + ": " + report.describe
    )

  it should "yield every outcome the tier states at this label" in
    assert(
      report.casesFound == Outcomes,
      "found " + report.casesFound.toString + " outcomes rather than " + Outcomes.toString + ": " + report.describe
    )

  it should "answer the stated number of entries as the tree states them" in
    assert(
      report.agreed.length == Certified,
      "certified " + report.agreed.length.toString + " rather than " + Certified.toString + ": " + report.describe
    )

  it should "diverge on the known five and on nothing else, named rather than counted" in
    assert(
      report.diverged.map(_.name).sorted == KnownDivergences,
      "a divergence outside this set is a finding rather than a figure to raise, and this set is the one rule " +
        "the header names read two ways: " + report.describe
    )

  it should "diverge on all five for the same stated reason" in {
    val reasons = report.diverged.map(_.verdict).collect { case Verdict.Diverged(why) => why }.flatten
    assert(
      reasons.length == KnownDivergences.length && reasons.forall(_.startsWith(DivergenceReason)),
      "a refused creation leaves the sender charged and the target untouched, so any other kind of disagreement " +
        "on these entries would be a different finding wearing the same names: " + reasons.mkString("; ")
    )
  }

  it should "skip only the cases that state nothing here, named rather than counted" in {
    val silent = report.skipped.collect {
      case outcome if outcome.verdict == Verdict.Skipped(SkipReason.NoExpectationAtThisFork) => outcome.name
    }
    assert(
      silent.sorted == StatingNothingHere && silent.length == report.skipped.length,
      "a skip for any other reason is a reader fault rather than a gap in the tree, and the set is named so that " +
        "losing one of these and gaining another is a failure: " + report.describe
    )
  }

  "the height this tier is resolved at" should "be an activation on this network's schedule" in {
    val schedule = KnownNetworks.registry.toOption
      .flatMap(_.at(ethereumclassic.Mainnet.network.chainId))
      .getOrElse(fail("this network is not in the registry"))
    assert(
      schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(ClassicPublishedStateCorpus.PhoenixStarts))),
      "resolving a tier through a height no fork begins at certifies a neighboring fork's rules under this one's " +
        "name: " + schedule.forkPoints.toString
    )
  }

  "the six recomposed from the fork below" should "be the rules this schedule resolves here" in {
    val schedule = KnownNetworks.registry.toOption
      .flatMap(_.at(ethereumclassic.Mainnet.network.chainId))
      .getOrElse(fail("this network is not in the registry"))
    val resolved = schedule.at(UInt64.fromBits(ClassicPublishedStateCorpus.PhoenixStarts), UInt64.Zero)
    assert(
      ethereumclassic.Upgrades.agharta.adopting(TheSix*) == resolved,
      "both differentials below withdraw from that recomposition, so a schedule answering with anything else " +
        "would make each of them a measurement about some other rule set"
    )
  }

  "the reader" should "answer nothing at a label this tree files no expectation under" in {
    val unfilled = readAs(UnfilledLabel)
    assert(
      unfilled.filesRead == report.filesRead && unfilled.agreed.isEmpty &&
        unfilled.skipped.length == unfilled.casesFound,
      "the run itself is the positive control for the post key and this is the negative one, so a label the tree " +
        "does not carry must report every file as stating no expectation: " + unfilled.describe
    )
  }

  "a build without EIP-1344" should "lose the entries that read the chain identifier, named rather than counted" in
    assert(
      moved(withoutChainIdentifier).sorted == WithoutTheChainIdentifierTheseMove,
      "these are the only published entries available to this build asserting that this network's own identifier " +
        "is what the operation pushes, and every entry here is signed for that chain: " +
        moved(withoutChainIdentifier).mkString(", ")
    )

  "a build that adopted none of the six" should "be refused by more than two thirds of the tier" in
    assert(
      adoptingNothing.agreed.length == AgreeingWithoutAllSix &&
        adoptingNothing.diverged.length == RefusedWithoutAllSix,
      "a harness whose only ever input is the answer it expects has no reachable failing state, and a tier the " +
        "fork below already satisfies is not evidence about the fork it is named for: " + adoptingNothing.describe
    )
