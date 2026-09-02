package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}
import org.fukuii.chainspec.{Activation, UpgradeRules}
import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus, SkipReason, Verdict}
import org.fukuii.types.TransactionType

/** This build's rules for Magneto, against the expectations `etclabscore/tests-etc`
  * files under that label.
  *
  * ==Why this tier is worth running when the one below it already passes==
  *
  * It is the same tree and the same reader as
  * [[ClassicPublishedPhoenixStateCertificationSpec]], one upgrade further on,
  * and it is not a second copy of that run: the entries this label states
  * differ from what the label below states for the great majority of the cases
  * the two share. **The figure is deliberately not written here**, because
  * nothing in this file re-derives it and a count in prose rots silently where
  * an asserted literal fails loudly. What is asserted instead is
  * `AgreeingUnderTheForkBelow`, which pins how much of this tier the fork below
  * satisfies -- the same claim, in the one form that cannot go quietly stale.
  *
  * ==It carries the only typed envelopes any Ethereum Classic tier here has==
  *
  * EIP-2718 and EIP-2930 are the two proposals of this upgrade whose subject is
  * a transaction FORMAT, and the label below carries no admitted instance of
  * one -- it cannot, because no rule set below this one admits the format. This
  * label carries them, every one signed for chain 61, and they are why
  * [[ClassicPublishedStateCorpus.MagnetoDirectories]] registers a directory the
  * upgrade below does not. **The count is not stated here for the reason the
  * one above is not**: it is a property of the corpus that this file does not
  * re-derive. What stands in for it is the mutation below -- withdrawing the
  * admitted format moves this tier's figure, which is the claim a count was
  * standing in for.
  *
  * ==What this tier cannot certify, stated so it is not looked for==
  *
  * This tree publishes `hash`, `logs`, `txbytes` and `indexes` and **no
  * `receipt` and no full post `state`**. So EIP-2718's per-receipt type tag --
  * the rule that a receipt's type must match its transaction's -- has no
  * assertion available here at all, and neither do its transaction-root and
  * receipt-root rules, which nothing in this build computes. That is a coverage
  * gap in the corpus rather than a gap between what is built and what is
  * checked.
  */
class ClassicPublishedMagnetoStateCertificationSpec extends AnyFlatSpec:

  /** Files the registered directories hold. */
  private val Files: Int = 472

  /** Outcomes across them: one per entry stated at this label, plus one per case
    * stating nothing here.
    */
  private val Outcomes: Int = 3201

  /** Entries this build answers as the tree states them.
    *
    * The three figures reconcile: 3,191 agreeing plus the five divergences
    * named below plus five cases stating nothing at this label is every one of
    * the 3,201 outcomes, so nothing here is unaccounted for.
    */
  private val Certified: Int = 3191

  /** Entries this build answers differently, named rather than counted.
    *
    * Inherited from the label below rather than new here: the same five
    * creation-collision cases diverge at Phoenix, for the same reason and by the
    * same observable. A count would pass a mutation swapping one of them for an
    * unrelated entry, which is why a known divergence is recorded as a set.
    */
  private val KnownDivergences: Vector[String] =
    Vector(
      "InitCollision[d0g0v0]",
      "InitCollision[d1g0v0]",
      "InitCollision[d2g0v0]",
      "InitCollision[d3g0v0]",
      "dynamicAccountOverwriteEmpty[d0g0v0]"
    )

  /** What every one of those divergences is. */
  private val DivergenceReason: String = "state root "

  /** Cases that state no expectation at this label, named rather than counted.
    *
    * The same discipline [[KnownDivergences]] applies two definitions above, and
    * for the same reason: a count passes a mutation that loses one of these and
    * gains an unrelated silent case, which is what a directory mis-registration
    * or a decode regression preserving the totals would look like. Every figure
    * in this file would still reconcile.
    */
  private val StatingNothingHere: Vector[String] =
    Vector(
      "CallEcrecover_Overflow",
      "coinbaseT2",
      "diffPlaces",
      "ecrecoverShortBuff",
      "ecrecoverWeirdV"
    )

  /** Entries the fork below's rules still answer as this label states them.
    *
    * A literal rather than an inequality, so the control states a margin. See
    * the case that reads it.
    */
  private val AgreeingUnderTheForkBelow: Int = 739

  /** A label this network's schedule carries and this tree files nothing under.
    *
    * The reader's own negative control, against the run itself as the positive
    * one: the reader dispatches on the post key, so a label the tree does not
    * use must report every file as stating no expectation rather than silently
    * matching something.
    */
  private val UnfilledLabel: String = "ETC_Thanos"

  private val report: CorpusReport =
    ClassicPublishedStateCorpus.magneto.getOrElse(
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
      .reportAt(
        "control",
        ClassicPublishedStateCorpus.MagnetoFork,
        height,
        ClassicPublishedStateCorpus.MagnetoDirectories,
        change
      )
      .getOrElse(fail("the published corpus was not found for a control run"))

  "the published Ethereum Classic tier at Magneto" should "read the files the registered directories hold" in
    // Asserted rather than cancelled: a directory this tree does not carry
    // contributes no files rather than failing, so a mistyped name would narrow
    // the tier in silence and every count below would still reconcile.
    assert(
      report.filesRead == Files,
      "read " + report.filesRead.toString + " files rather than " + Files.toString + ": " + report.describe
    )

  it should "account for every outcome it found" in
    // The figure that makes the one below non-vacuous. A skipped or undecodable
    // entry is counted by nothing, so a tier whose corpus half-vanished would
    // report the same certified figure as one that ran.
    assert(
      report.casesFound == Outcomes,
      "found " + report.casesFound.toString + " outcomes rather than " + Outcomes.toString + ": " + report.describe
    )

  it should "answer every entry the tree states" in
    assert(
      report.agreed.length == Certified,
      "certified " + report.agreed.length.toString + " rather than " + Certified.toString + ": " + report.describe
    )

  it should "diverge on exactly the entries the fork below diverges on, named rather than counted" in
    assert(
      report.diverged.map(_.name).sorted == KnownDivergences.sorted,
      "diverged at " + report.diverged.map(_.name).sorted.mkString(", ")
    )

  it should "state every one of those divergences as the same observable" in {
    val reasons = report.diverged.map(_.verdict).collect { case Verdict.Diverged(why) => why }.flatten
    assert(
      reasons.length == KnownDivergences.length && reasons.forall(_.startsWith(DivergenceReason)),
      "a refused creation leaves the sender charged and the target untouched, so any other kind of " +
        "disagreement on these entries would be a different finding wearing the same names: " + reasons.mkString("; ")
    )
  }

  it should "skip nothing for a reason other than an absent expectation" in {
    val other = report.skipped.filter(outcome => outcome.verdict != Verdict.Skipped(SkipReason.NoExpectationAtThisFork))
    assert(
      other.isEmpty,
      "an entry was skipped for a reason that is not the tree declining to state one: " +
        other.map(_.name).mkString(", ")
    )
  }

  it should "state nothing on exactly the cases named here, rather than on that many of them" in {
    val silent = report.skipped.collect {
      case outcome if outcome.verdict == Verdict.Skipped(SkipReason.NoExpectationAtThisFork) => outcome.name
    }
    assert(
      silent.sorted == StatingNothingHere.sorted,
      "the cases stating nothing at this label are not the ones named: " + silent.sorted.mkString(", ")
    )
  }

  "the fork below" should "satisfy materially fewer of this label's expectations" in {
    // THE CONTROL THE WHOLE TIER DEPENDS ON. A tier whose expectations the
    // upgrade below already satisfies is not evidence about the upgrade it is
    // named for -- it is a corpus that could not have disagreed, which is the
    // shape this project has been caught by before.
    //
    // The figure is pinned rather than compared, because "materially fewer" is
    // the claim and a bare inequality is satisfied by a margin of one. A change
    // narrowing the real margin to a handful would pass an inequality
    // undetected until the two counts became exactly equal, which is the same
    // failure arriving slowly.
    val below = under(ClassicPublishedStateCorpus.ThanosStarts, identity)
    assert(
      below.agreed.length == AgreeingUnderTheForkBelow,
      "the rules below Magneto answer " + below.agreed.length.toString + " of this label's expectations " +
        "rather than " + AgreeingUnderTheForkBelow.toString + ", against " + report.agreed.length.toString +
        " under Magneto's own"
    )
  }

  "the reader" should "answer nothing at a label this tree files no expectation under" in {
    // The negative control for the reader itself, against the run above as the
    // positive one. It must read the same files and state nothing about them.
    val unfilled = ClassicPublishedStateCorpus
      .reportAt(
        "control",
        UnfilledLabel,
        ClassicPublishedStateCorpus.MagnetoStarts,
        ClassicPublishedStateCorpus.MagnetoDirectories,
        identity
      )
      .getOrElse(fail("the published corpus was not found for a control run"))
    assert(
      unfilled.filesRead == report.filesRead && unfilled.agreed.isEmpty &&
        unfilled.skipped.length == unfilled.casesFound,
      "a label this tree files nothing under matched something, so the reader is not dispatching on the post key"
    )
  }

  "the height this tier is resolved at" should "be an activation on this network's schedule" in {
    // The arm every sibling published tier carries and this one lacked. Slide
    // MagnetoStarts to any value at or above the real one and every other
    // assertion in this file still passes, because the schedule answers
    // Upgrades.magneto for all of them -- so the tier would certify a
    // neighbouring fork's rules under this one's name and report clean.
    //
    // `MainnetPropSpec` pins the SCHEDULE at this height, which is a different
    // claim: it says what the network runs there, not what this tier resolved.
    val resolved = KnownNetworks.registry.toOption
      .flatMap(_.at(ethereumclassic.Mainnet.network.chainId))
      .getOrElse(fail("this network is not in the registry"))
    assert(
      resolved.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(ClassicPublishedStateCorpus.MagnetoStarts))),
      "this tier is resolved through a height no fork begins at"
    )
  }

  it should "answer the composition this tier is named for" in {
    // The second sibling arm: ties the resolved rules to the value under test,
    // so the tier cannot drift onto a neighbour's rule set while still landing
    // on a real fork point.
    val resolved = KnownNetworks.registry.toOption
      .flatMap(_.at(ethereumclassic.Mainnet.network.chainId))
      .getOrElse(fail("this network is not in the registry"))
    assert(
      resolved.at(UInt64.fromBits(ClassicPublishedStateCorpus.MagnetoStarts), UInt64.Zero) eq
        ethereumclassic.Upgrades.magneto,
      "the schedule answers rules other than Magneto's at the height this tier is resolved at"
    )
  }

  "a build admitting only the untagged format" should "be refused by this label's typed entries" in {
    // THE ASSERTION THIS UPGRADE ADDS THAT NO EARLIER ETHEREUM CLASSIC TIER
    // COULD MAKE. Withdrawing the admitted format leaves the access-list charge
    // and the whole state-access repricing in place, so anything that moves
    // here moved because a typed envelope stopped being admissible.
    //
    // What that isolates is EIP-2930's ADMISSION half, and it is worth being
    // exact about the limit: `Eip2718.component` is the identity, so there is
    // no delta to withdraw and no mutation of the rule set can distinguish
    // EIP-2718 adopted from EIP-2718 not adopted. `Eip2718.scala` says the same
    // from its own side. This arm establishes that the tier reaches typed
    // envelopes at all -- which is what makes the envelope's decoding and
    // admission exercised here rather than merely present.
    val legacyOnly = under(
      ClassicPublishedStateCorpus.MagnetoStarts,
      rules => rules.copy(admission = rules.admission.copy(admittedTypes = Set(TransactionType.Legacy)))
    )
    assert(
      legacyOnly.agreed.length < report.agreed.length,
      "withdrawing the declaring transaction format changed no outcome, so this tier certifies neither " +
        "EIP-2718 nor EIP-2930: " + legacyOnly.agreed.length.toString + " against " +
        report.agreed.length.toString
    )
  }

  it should "not be what this network runs there" in
    // The positive half of the pair above: the mutation is what moves the
    // figure, not the tier being unreachable.
    assert(
      ethereumclassic.Upgrades.magneto.admission.admittedTypes ==
        Set(TransactionType.Legacy, TransactionType.AccessList),
      "the composition under test does not admit the format the mutation withdraws"
    )
