package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}
import org.fukuii.chainspec.{Activation, UpgradeRules}
import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus}

/** Die Hard's rule set against Ethereum Classic's own state fixtures.
  *
  * ==What these expectations rest on==
  *
  * `fukuii-project/fukuii-tests` @ `c3a77f8e415e02ed9c523a2d981118680fee7200`,
  * `networks/ethereumclassic/mainnet/state`. Every file states an oracle: a
  * modernized `core-geth` build, run through that client's `t8n` at each
  * upgrade's rules, and re-run through that client's own `statetest` runner as
  * a second pass. That is an implementation this project does not maintain,
  * answering independently of it.
  *
  * **The second pass does not reach the cases asserting a refusal**, which the
  * corpus records rather than leaves to be discovered: a geth-family state-test
  * runner builds its message from the stated sender and never validates a
  * signature, so it cannot express a transaction being refused at all. Those
  * cases rest on the first oracle alone.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled. A cancelled test is counted by nothing, so a
  * build whose corpus vanished reports the same executed total as one that ran
  * it.
  *
  * ==The figures are literals, so a corpus that shrank is a failure==
  *
  * Every count below is stated rather than derived from the run. A tier that
  * lost a file would otherwise report a smaller green, which is the one shape a
  * certification harness must not have. The tree is under active authorship, so
  * a figure here moving is expected to be a deliberate act with a reason.
  */
class ClassicDieHardStateCertificationSpec extends AnyFlatSpec:

  /** Files the tier states its cases in. */
  private val Files: Int = 45

  /** Runnable combinations across those files, at this label. */
  private val Cases: Int = 54

  /** Cases this build answers, which is every one the tier states here. */
  private val Certified: Int = 54

  /** Cases that answer differently once EIP-155 is taken out of the fork.
    *
    * ==Reading the corpus predicts 53 and the run says 51, and the two that
    * differ are the interesting part==
    *
    * Each post entry documents how it was signed, and 53 of these 54 say they
    * carry a chain identifier -- so a census of that field predicts 53. Two of
    * them do not move, because they are typed envelopes: this fork predates
    * EIP-2718, admission refuses a format before it ever reads a signature, and
    * a case already refused for its format is refused identically with or
    * without EIP-155. The third unmoved case is the legacy-signed control that
    * the replay-protection fixture carries for exactly this purpose.
    *
    * So the documentation field measures how the corpus was FILLED and this
    * measures what it can DECIDE, and the gap between them is an ordering fact
    * about admission rather than a defect in either.
    */
  private val MovedWithoutChainIdSignatures: Int = 51

  /** Cases that answer differently once the exponent repricing is undone.
    *
    * One, and its identity is the durable fact rather than its count: a corpus
    * that lost this case and gained an unrelated one would report the same
    * number. Asserted by name below.
    */
  private val MovedWithoutTheExpReprice: Int = 1

  /** The case that alone decides the repricing. */
  private val ExpRepriceCase: String = "exp_exponent_byte_cost_across_upgrades[d0g0v0]"

  /** The one case here signed the way every transaction was signed before this
    * fork, which the replay-protection fixture carries as its own control.
    *
    * The rule permits the later signing scheme rather than requiring it, so a
    * legacy signature stays valid forever -- and a build that lost this case
    * along with the other fifty-one would have implemented a replacement rather
    * than an addition.
    */
  private val LegacySignedControl: String = "replayProtectionAcrossUpgrades[d0g0v0]"

  /** Cases that answer differently when this tier is resolved one fork lower.
    *
    * The control the whole registration depends on. A tier whose expectations
    * are satisfied by the rules of the fork below it is not evidence about the
    * fork it is named for, however many cases it holds -- and twelve labels
    * over one transaction is exactly the shape that can look like broad
    * coverage and decide nothing.
    *
    * It equals the EIP-155 figure because ECIP-1010 writes no state rule and the
    * one case the repricing decides is already among the 51.
    */
  private val MovedAtTheForkBelow: Int = 51

  /** Cases that disagree when Die Hard's rules are asked the expectations the
    * fork BELOW files, and the fork above the group this one sits in.
    *
    * ==Which labels this tier can separate, and which it provably cannot==
    *
    * A file here states an expectation under each of twelve upgrade names, and
    * twelve labels over one transaction is exactly the shape that can look like
    * broad coverage and decide one thing. So what the tier separates is
    * measured rather than assumed, and it separates Die Hard's rules from the
    * fork below by two cases and from the fork above the group by twenty-two.
    */
  private val DivergingAtTheLabelBelow: Int = 2
  private val DivergingAtTheLabelAboveTheGroup: Int = 22

  /** The labels whose expectations Die Hard's rules satisfy exactly.
    *
    * ==This is a property of the chain, not a gap in the corpus==
    *
    * Gotham adopts ECIP-1017 and ECIP-1039 and Defuse adopts ECIP-1041; the
    * first two settle an emission and the third the exponential difficulty
    * term, and a state fixture settles one transaction against a block it is
    * handed. None of the three is observable to it. So no state corpus at any
    * scale can tell these three labels apart, and reading this tier under
    * either later name would agree everywhere.
    *
    * Stated as an assertion rather than left implicit, because the equality is
    * the interesting claim: a build that wrote a state rule into either later
    * upgrade would break it.
    */
  private val LabelsDieHardAlsoSatisfies: Vector[String] =
    Vector("ETC_Gotham", "ETC_DefuseDifficultyBomb")

  /** The label of the fork below this one, whose expectations Die Hard's rules
    * must NOT satisfy.
    */
  private val LabelBelow: String = "ETC_GasReprice"

  /** The label of the fork above the group Die Hard's rules answer for. */
  private val LabelAboveTheGroup: String = "ETC_Atlantis"

  /** Files read when the label names an upgrade this corpus files nothing
    * under, and the cases each of them then declines to answer.
    *
    * The reader's own control: it dispatches on the post key, so a label the
    * corpus does not carry must report every file as stating no expectation
    * rather than silently matching something. A harness that answered here
    * would be agreeing with a question it never asked.
    */
  private val UnfilledLabel: String = "ETC_Thanos"

  private val report: CorpusReport =
    ClassicStateCorpus.dieHard.getOrElse(
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
      .reportAt("control", ClassicStateCorpus.DieHardFork, height, change)
      .getOrElse(fail("assembled once and not the second time"))

  /** Which cases answer differently under an altered run, by name.
    *
    * ==The pairing is checked before it is relied on==
    *
    * `zip` truncates to the shorter side rather than complaining, so a control
    * yielding fewer outcomes would leave the tail of the baseline compared
    * against nothing and report a LOW count -- which reads as a corpus that
    * decides less, not as a control that went wrong. The names are compared as
    * sequences rather than the two lengths, because two runs of the same length
    * over different cases pair each verdict with a stranger's.
    */
  private def moved(altered: CorpusReport): Vector[String] =
    if report.outcomes.map(_.name) != altered.outcomes.map(_.name) then
      fail(
        "the control did not answer for the same cases in the same order: " +
          report.casesFound.toString + " outcomes first and " + altered.casesFound.toString + " on the control"
      )
    else report.outcomes.zip(altered.outcomes).collect { case (before, after) if before != after => before.name }

  /** The chain identifier stopped being readable out of a signature. */
  private val withoutChainIdSignatures: UpgradeRules => UpgradeRules =
    rules => rules.copy(admission = rules.admission.copy(signatureMayCarryChainId = false))

  /** Exponentiation back at the price the previous fork charged.
    *
    * The base charge is left where it is. `ethereum/EIPs` @ `15f61ed0f`,
    * `EIPS/eip-160.md` moves only the per-byte part, so a control moving both
    * would be testing a fork nothing ever shipped.
    */
  private val withoutTheExpReprice: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(schedule = rules.evm.schedule.copy(expPerByte = BigInt(10))))

  "this chain's state tier at Die Hard" should "be read in full" in
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
      schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(ClassicStateCorpus.DieHardStarts))),
      "resolving a corpus through a height no fork begins at certifies a neighboring fork's rules under this " +
        "one's name: " + schedule.forkPoints.toString
    )
  }

  "the harness" should "leave every case answered when nothing is altered" in
    assert(
      moved(under(ClassicStateCorpus.DieHardStarts, identity)).isEmpty,
      "the control path must not perturb the run, or every count below measures the control: " +
        under(ClassicStateCorpus.DieHardStarts, identity).describe
    )

  it should "answer nothing at a label this corpus files no expectation under" in {
    val unfilled = ClassicStateCorpus
      .reportAt("control", UnfilledLabel, ClassicStateCorpus.DieHardStarts, identity)
      .getOrElse(fail("assembled once and not the second time"))
    assert(
      unfilled.filesRead == report.filesRead && unfilled.agreed.isEmpty &&
        unfilled.skipped.length == unfilled.casesFound,
      "the reader dispatches on the post key, so an unfilled label must report every file as stating no " +
        "expectation rather than matching something: " + unfilled.describe
    )
  }

  "a build without EIP-155" should "lose the cases whose signature names this chain" in
    assert(
      moved(under(ClassicStateCorpus.DieHardStarts, withoutChainIdSignatures)).length == MovedWithoutChainIdSignatures,
      "nearly every case here is signed under the rule, so a build refusing those signatures must lose them: " +
        under(ClassicStateCorpus.DieHardStarts, withoutChainIdSignatures).describe
    )

  it should "leave the legacy-signed control and the typed envelopes where they are" in {
    val kept = report.outcomes.map(_.name).toSet -- moved(
      under(ClassicStateCorpus.DieHardStarts, withoutChainIdSignatures)
    ).toSet
    assert(
      kept.contains(LegacySignedControl) && kept.size == Cases - MovedWithoutChainIdSignatures,
      "a legacy signature stays valid forever, and a format this fork predates is refused before any signature " +
        "is read -- so these are the cases the rule cannot reach: " + kept.toVector.sorted.mkString(", ")
    )
  }

  "a build without EIP-160" should "lose the case that prices an exponent's bytes" in
    assert(
      moved(under(ClassicStateCorpus.DieHardStarts, withoutTheExpReprice)).length == MovedWithoutTheExpReprice,
      "the repricing is the only change this fork makes to what an instruction costs: " +
        under(ClassicStateCorpus.DieHardStarts, withoutTheExpReprice).describe
    )

  it should "lose that case and no other, named rather than counted" in
    assert(
      moved(under(ClassicStateCorpus.DieHardStarts, withoutTheExpReprice)) == Vector(ExpRepriceCase),
      "a corpus that lost this case and gained an unrelated one would report the same count: " +
        moved(under(ClassicStateCorpus.DieHardStarts, withoutTheExpReprice)).mkString(", ")
    )

  "this tier resolved at the fork below" should "lose the cases that make it evidence about Die Hard" in
    assert(
      moved(under(ClassicStateCorpus.GasRepriceStarts, identity)).length == MovedAtTheForkBelow,
      "a tier satisfied by the rules of the fork below it says nothing about the fork it is named for: " +
        under(ClassicStateCorpus.GasRepriceStarts, identity).describe
    )

  "this tier read under the label below" should "disagree with Die Hard's rules" in {
    val below = ClassicStateCorpus
      .reportAt("control", LabelBelow, ClassicStateCorpus.DieHardStarts, identity)
      .getOrElse(fail("assembled once and not the second time"))
    assert(
      below.diverged.length == DivergingAtTheLabelBelow,
      "the label is what the reader dispatches on, so a tier agreeing with the fork below's expectations under " +
        "this fork's rules would not be evidence about either: " + below.describe
    )
  }

  "this tier read under the label above the group" should "disagree with Die Hard's rules" in {
    val above = ClassicStateCorpus
      .reportAt("control", LabelAboveTheGroup, ClassicStateCorpus.DieHardStarts, identity)
      .getOrElse(fail("assembled once and not the second time"))
    assert(
      above.diverged.length == DivergingAtTheLabelAboveTheGroup,
      "the rules this tier is run under must stop being right somewhere above it: " + above.describe
    )
  }

  "the two upgrades after this one" should "state the same state transition, which is why no state tier separates them" in {
    val equal = LabelsDieHardAlsoSatisfies.map { label =>
      ClassicStateCorpus
        .reportAt("control", label, ClassicStateCorpus.DieHardStarts, identity)
        .getOrElse(fail("assembled once and not the second time"))
    }
    assert(
      equal.forall(r => r.diverged.isEmpty && r.agreed.length == Certified),
      "both adopt proposals a state fixture cannot observe, so their expectations must equal this fork's -- and " +
        "a build that wrote a state rule into either would break this: " + equal.map(_.describe).mkString(" || ")
    )
  }
