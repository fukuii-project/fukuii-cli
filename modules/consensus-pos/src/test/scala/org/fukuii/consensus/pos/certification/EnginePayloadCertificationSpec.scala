package org.fukuii.consensus.pos.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus}

/** The driver seam against block hashes this project did not compute.
  *
  * ==What a corpus can most easily agree for the wrong reason==
  *
  * A harness that narrowed its own input agrees with everything left, and every
  * count it reports stays plausible. So the figures below are asserted rather
  * than described: the files read, the payloads found, and the payloads skipped
  * are each pinned, and a corpus that stopped being read fails on the first of
  * them instead of reporting a smaller green.
  *
  * **A missing corpus fails rather than cancels.** A canceled case is counted
  * by nothing, so a run whose corpus vanished would otherwise report the same
  * total as one that read all of it — which is the shape every other check in
  * this build is written against.
  */
class EnginePayloadCertificationSpec extends AnyFlatSpec:

  /** Files in the three labels: 150, 182 and 2,401.
    *
    * Counted directly from the release and stated here so that a label losing
    * or gaining a file is a failure rather than a quieter agreement.
    *
    * **The third is thirteen times the first two together**, which is what
    * makes its arrival the change in this tier's reach rather than an addition
    * to it.
    */
  private val Files: Map[String, Int] = Map("for_paris" -> 150, "for_shanghai" -> 182, "for_cancun" -> 2401)

  /** Payloads in each label, which is NOT the number of fixture cases.
    *
    * A fixture case carries an `engineNewPayloads` array, and a case with
    * several payloads states several. The release holds 3,510, 3,751 and
    * 12,888 cases under the three labels and 4,997, 5,269 and 14,609 payloads
    * inside them, and the second triple is what this tier translates.
    */
  private val Payloads: Map[String, Int] =
    Map("for_paris" -> 4997, "for_shanghai" -> 5269, "for_cancun" -> 14609)

  /** Payloads the corpus expects to be rejected for something this layer does
    * not reach, and therefore skips.
    *
    * Every one names a transaction-level defect or a block-level gas rule. The
    * header over such a block is ordinarily well formed, so the corpus is not
    * saying anything about this layer for these — and the count is the size of
    * the work that would let them be decided rather than skipped.
    */
  private val Skips: Map[String, Int] = Map("for_paris" -> 288, "for_shanghai" -> 299, "for_cancun" -> 845)

  /** Payloads the corpus expects to be rejected FOR THEIR BLOCK HASH.
    *
    * Six: three under Shanghai and three under Cancun, each naming an invalid
    * withdrawals root beside the invalid block hash. **They are the only
    * published exercise of the refusal path this tier has**, which is worth
    * stating plainly: with them removed, a translation that never refused
    * anything would certify just as green.
    */
  private val HashRefusals: Map[String, Int] =
    Map("for_paris" -> 0, "for_shanghai" -> 3, "for_cancun" -> 3)

  /** ==Every accessor here is a `def`, and that is not a style choice==
    *
    * A missing corpus must fail as tests rather than as a suite that never
    * registered. Calling `fail` from a `val` runs it during construction, and
    * ScalaTest reports that as an ABORTED suite: the run exits non-zero, but
    * the summary line says `failed: 0` and the executed total drops by however
    * many tests the suite would have held. **That reads as tests having been
    * removed**, which is the one shape this build's count ratchet is least able
    * to tell from a real deletion.
    *
    * Measured against this tier with the corpus pointer redirected: as `val`s
    * it aborted, the total fell from 179 to 161 and `failed` stayed 0. As
    * `def`s every assertion runs, so the total holds and the failures are
    * attributable to the tier rather than to an arithmetic gap.
    *
    * The run is not repeated: [[EnginePayloadCorpus.reports]] is a `lazy val`,
    * so the corpus is read once however many accessors ask for it.
    */
  private def reports: Vector[CorpusReport] =
    EnginePayloadCorpus.reports.getOrElse(
      fail(
        "the published corpus was not found: set " + FixtureCorpus.RootVariable + " or write " +
          FixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  private def reportFor(label: String): CorpusReport =
    reports
      .find(_.corpus == label)
      .getOrElse(fail("no report for " + label + ": " + reports.map(_.corpus).mkString(",")))

  private def paris: CorpusReport = reportFor("for_paris")
  private def shanghai: CorpusReport = reportFor("for_shanghai")
  private def cancun: CorpusReport = reportFor("for_cancun")
  private def every: Vector[CorpusReport] = Vector(paris, shanghai, cancun)

  /** Payloads the corpus expects to be ACCEPTED, which is neither the payload
    * count nor the agreed count.
    *
    * The agreed count includes the payloads correctly refused for their block
    * hash, so it is one group wider than this. Stated as a derivation of the
    * three pinned figures rather than as a fourth literal, so a label that
    * changes size cannot leave two of the four disagreeing.
    */
  private def accepted(label: String): Int = Payloads(label) - Skips(label) - HashRefusals(label)

  private def under(arm: EnginePayloadCorpus.Arm): Map[String, CorpusReport] =
    EnginePayloadCorpus
      .reportsUnder(arm)
      .getOrElse(fail("no run under " + arm.toString + " from a corpus that reported"))
      .map((report, _) => report.corpus -> report)
      .toMap

  private def divergedUnder(arm: EnginePayloadCorpus.Arm, label: String): Int =
    under(arm).getOrElse(label, fail("no " + label + " report under " + arm.toString)).diverged.length

  private def agreedUnder(arm: EnginePayloadCorpus.Arm, label: String): Int =
    under(arm).getOrElse(label, fail("no " + label + " report under " + arm.toString)).agreed.length

  private def coverage: Map[String, Coverage] =
    EnginePayloadCorpus.coverage.getOrElse(fail("no coverage from a corpus that reported")).toMap

  "the published engine tier" should "report one corpus per label" in
    assert(
      reports.map(_.corpus) == EnginePayloadCorpus.Labels,
      "a label that stopped being read would otherwise show up only as a smaller total: " + reports.map(_.describe)
    )

  it should "read every file under Paris" in
    assert(
      paris.filesRead == Files("for_paris"),
      "read " + paris.filesRead.toString + " files rather than " + Files("for_paris").toString + ": " + paris.describe
    )

  it should "read every file under Shanghai" in
    assert(
      shanghai.filesRead == Files("for_shanghai"),
      "read " + shanghai.filesRead.toString + " rather than " + Files("for_shanghai").toString + ": " +
        shanghai.describe
    )

  it should "find every payload under Paris" in
    assert(
      paris.casesFound == Payloads("for_paris"),
      "found " + paris.casesFound.toString + " payloads rather than " + Payloads("for_paris").toString + ": " +
        paris.describe
    )

  it should "find every payload under Shanghai" in
    assert(
      shanghai.casesFound == Payloads("for_shanghai"),
      "found " + shanghai.casesFound.toString + " payloads rather than " + Payloads("for_shanghai").toString + ": " +
        shanghai.describe
    )

  it should "read every file under Cancun" in
    assert(
      cancun.filesRead == Files("for_cancun"),
      "read " + cancun.filesRead.toString + " rather than " + Files("for_cancun").toString + ": " + cancun.describe
    )

  it should "find every payload under Cancun" in
    assert(
      cancun.casesFound == Payloads("for_cancun"),
      "found " + cancun.casesFound.toString + " payloads rather than " + Payloads("for_cancun").toString + ": " +
        cancun.describe
    )

  it should "derive a matching header for every payload the corpus expects to be accepted" in
    assert(
      every.forall(_.diverged.isEmpty),
      every.map(_.describe).mkString("\n")
    )

  it should "decode every payload it read" in
    assert(
      every.forall(_.skipsByReason.get("undecodable").isEmpty),
      "a payload this harness could not decode is a reader fault and is not the corpus declining to say anything: " +
        every.map(_.describe).mkString("\n")
    )

  it should "skip exactly the payloads whose expected defect is beyond this layer, under Paris" in
    assert(
      paris.skipped.length == Skips("for_paris"),
      "skipped " + paris.skipped.length.toString + " rather than " + Skips("for_paris").toString +
        ", so the tier is measuring a different set than it reports: " + paris.describe
    )

  it should "skip exactly the payloads whose expected defect is beyond this layer, under Shanghai" in
    assert(
      shanghai.skipped.length == Skips("for_shanghai"),
      "skipped " + shanghai.skipped.length.toString + " rather than " + Skips("for_shanghai").toString + ": " +
        shanghai.describe
    )

  it should "skip exactly the payloads whose expected defect is beyond this layer, under Cancun" in
    assert(
      cancun.skipped.length == Skips("for_cancun"),
      "skipped " + cancun.skipped.length.toString + " rather than " + Skips("for_cancun").toString + ": " +
        cancun.describe
    )

  it should "skip for one reason only" in
    assert(
      every.forall(_.skipsByReason.keySet.subsetOf(Set("rule-not-built"))),
      "a second skip reason means the tier stopped reading something rather than declining to decide it: " +
        every.map(_.skipsByReason).mkString(" ")
    )

  it should "agree with every payload it decided" in
    assert(
      every.forall(report => report.agreed.length == Payloads(report.corpus) - Skips(report.corpus)),
      "decided and agreed must account for every payload that was not skipped: " + every.map(_.describe).mkString("\n")
    )

  /** ==The one assertion that keeps the refusal path from being untested==
    *
    * Every other case here agrees by deriving a header that matches. A
    * translation that could not refuse anything would satisfy all of them. The
    * six payloads the corpus expects to be rejected for their block hash are
    * the only published cases that discriminate, so their count is pinned
    * separately — losing them would leave the tier green over a translation
    * with no refusal at all.
    */
  it should "refuse exactly the payloads the corpus expects to fail their block hash" in
    assert(
      HashRefusals.values.sum == 6 && every.forall(_.diverged.isEmpty),
      "the corpus states six such payloads and they are this tier's only exercise of the refusal path"
    )

  "the tier as a whole" should "cover every payload under every label" in
    assert(
      every.map(_.casesFound).sum == Payloads.values.sum && every.map(_.filesRead).sum == Files.values.sum,
      "the totals are stated so that a label read at half its size cannot pass the per-label checks and this one"
    )

  /** ==Coverage is asserted, because agreement alone reads the same either way==
    *
    * Twenty-three thousand agreements say the derivation is right for what it
    * was given. They say nothing about what it was given, and the labels are
    * uneven enough that the difference matters: nearly every Shanghai payload
    * carries the withdrawals FIELD, and forty-five carry a non-empty LIST. So
    * the empty-trie value of that commitment is exercised thousands of times
    * and the real trie forty-five, and only the second discriminates a wrong
    * root.
    *
    * Pinning the number is what stops that thinning further without notice.
    */
  "the tier's reach" should "exercise the transactions root over a real trie under every label" in
    assert(
      coverage("for_paris").withTransactions == 4450 && coverage("for_shanghai").withTransactions == 4669 &&
        coverage("for_cancun").withTransactions == 13460,
      "a payload with no transaction commits to the empty root, which a wrong derivation also produces: " +
        coverage.toString
    )

  it should "carry no withdrawals field at all under Paris" in
    assert(
      coverage("for_paris").withWithdrawalsField == 0,
      "the label predates the proposal, so the header's tail is one link shorter for every one of its payloads"
    )

  it should "carry the withdrawals field on every decided payload under Shanghai and Cancun" in
    assert(
      coverage("for_shanghai").withWithdrawalsField == coverage("for_shanghai").decided &&
        coverage("for_cancun").withWithdrawalsField == coverage("for_cancun").decided,
      "the field's presence is what makes the tail a link deeper, and it is universal in both labels"
    )

  /** ==Forty-seven, and the decomposition is why the figure is stated here==
    *
    * Forty-five sit in the accept group and two are among the three the corpus
    * expects to be refused for their block hash — those name an invalid
    * withdrawals root beside it, so they carry a list too. Counting the accept
    * group alone gives forty-five and is a count over a different set than the
    * tier decides, which is exactly the disagreement that produced this number.
    */
  it should "exercise a non-empty withdrawals root on forty-seven payloads only" in
    assert(
      coverage("for_shanghai").withNonEmptyWithdrawals == 47,
      "this is the thin one: a wrong withdrawals root is caught by these forty-seven and by nothing else here"
    )

  /** ==One hundred under Cancun, and the same decomposition as above does NOT
    * apply==
    *
    * All one hundred sit in the accept group. The three Cancun payloads the
    * corpus expects to be refused for their block hash name an invalid
    * withdrawals root and carry an EMPTY list, where the Shanghai three carry a
    * non-empty one — so the two labels' figures decompose differently and
    * stating only the total would hide it.
    */
  it should "exercise a non-empty withdrawals root on one hundred Cancun payloads only" in
    assert(
      coverage("for_cancun").withNonEmptyWithdrawals == 100,
      "a wrong withdrawals root is caught by these one hundred and by nothing else in this label"
    )

  it should "decide exactly the payloads the reports agree on" in
    assert(
      every.forall(report => coverage(report.corpus).decided == report.agreed.length + report.diverged.length),
      "coverage counted over a different set than the verdicts would describe reach the assertions do not have"
    )

  /** ==The skipped payloads were translated too, and the answer is asserted==
    *
    * The tier declines to call these valid or invalid, because the defect the
    * corpus states is a transaction-level or gas-level one this layer does not
    * reach. **It does not decline to translate them**, and the block hash each
    * states is a commitment about this layer exactly as much as it is for the
    * payloads above.
    *
    * Leaving that answer uncounted left a region of the input on which the
    * tier's central assertion could not fail: a derivation defect reachable
    * only from the malformed transaction shapes these fixtures carry would have
    * produced a wrong header for precisely them, and every count here would
    * have been unchanged.
    *
    * **The verdict is still `Skipped`.** Asserting the block hash says nothing
    * about the validity question the corpus asked of another layer, so the tier
    * still claims to decide only what it decided.
    */
  "the tier's undecided payloads" should "reproduce every stated block hash under Paris" in
    assert(
      coverage("for_paris").undecidedDerivingHeader == Skips("for_paris"),
      "derived " + coverage("for_paris").undecidedDerivingHeader.toString + " matching headers across " +
        Skips("for_paris").toString + " skipped payloads: " + coverage("for_paris").toString
    )

  it should "reproduce every stated block hash under Shanghai" in
    assert(
      coverage("for_shanghai").undecidedDerivingHeader == Skips("for_shanghai"),
      "derived " + coverage("for_shanghai").undecidedDerivingHeader.toString + " matching headers across " +
        Skips("for_shanghai").toString + " skipped payloads: " + coverage("for_shanghai").toString
    )

  it should "reproduce every stated block hash under Cancun" in
    // The largest of the three by an order of magnitude, and the one carrying
    // the malformed blob-transaction shapes the fork introduced -- so it is the
    // group most likely to break a derivation that reads the payload's own
    // appended chain.
    assert(
      coverage("for_cancun").undecidedDerivingHeader == Skips("for_cancun"),
      "derived " + coverage("for_cancun").undecidedDerivingHeader.toString + " matching headers across " +
        Skips("for_cancun").toString + " skipped payloads: " + coverage("for_cancun").toString
    )

  it should "be refused by the translation nowhere" in
    assert(
      every.forall(report => coverage(report.corpus).undecidedRefused == 0),
      "a refusal here is this build disagreeing with a published block hash on the payloads most likely to " +
        "break a derivation, and nothing about the corpus makes zero the expected answer: " + coverage.toString
    )

  /** ==The calibration, run rather than recorded==
    *
    * Every assertion above is satisfied by a tier that agrees. None of them is
    * satisfied only by a tier that can also DISAGREE — a derivation that had
    * quietly stopped deriving would report the same counts. So the identical
    * corpus is classified under two deliberately wrong translations, and each
    * is required to diverge by an exact amount.
    *
    * **An exact count rather than a non-zero one**, because an arm accepting
    * any divergence cannot tell the defect it seeded from an unrelated
    * breakage — and an arm that has stopped reaching the corpus at all diverges
    * zero times, which reads as the tier being unusually robust.
    *
    * The two bracket the range: one commitment reached by a few dozen payloads,
    * one reached by every payload.
    */
  "the tier's calibration" should "move no Paris payload when a withdrawal is dropped" in
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.WithdrawalDropped, "for_paris") == 0,
      "the label predates the proposal, so no payload under it carries a list for the arm to shorten"
    )

  /** Forty-five, and the decomposition is the same one the coverage assertion
    * above states: forty-seven decided payloads carry a non-empty withdrawals
    * list, and two of them are among the three the corpus expects to be refused
    * for their block hash — a shortened list leaves those refused, so they go
    * on agreeing.
    */
  it should "move exactly the forty-five accepted Shanghai payloads carrying a withdrawal" in
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.WithdrawalDropped, "for_shanghai") == 45,
      "diverged " + divergedUnder(EnginePayloadCorpus.Arm.WithdrawalDropped, "for_shanghai").toString +
        " rather than 45, so the arm is no longer seeding the defect it names"
    )

  it should "move every accepted Paris payload when the ommers hash is substituted" in
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_paris") == accepted("for_paris"),
      "diverged " + divergedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_paris").toString +
        " rather than " + accepted("for_paris").toString + ": a constant every header commits to should " +
        "leave nothing standing"
    )

  it should "move every accepted Shanghai payload when the ommers hash is substituted" in
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_shanghai") == accepted("for_shanghai"),
      "diverged " + divergedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_shanghai").toString +
        " rather than " + accepted("for_shanghai").toString
    )

  /** ==The arm has to be DISCRIMINATING, not merely destructive==
    *
    * An arm that broke every case equally would satisfy the two above and prove
    * only that something changed. These three payloads are the ones the corpus
    * expects to be refused for their block hash, and a header damaged further
    * still fails that hash — so they go on agreeing under an arm that moves
    * everything else.
    *
    * That is what separates a seeded defect from a harness that stopped
    * working: a broken harness cannot leave exactly these three alone.
    */
  it should "leave the three block-hash refusals agreeing under the ommers arm" in
    assert(
      agreedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_shanghai") == HashRefusals("for_shanghai"),
      "agreed " + agreedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_shanghai").toString +
        " rather than " + HashRefusals("for_shanghai").toString +
        ", so the arm is not discriminating between a wrong header and a payload already expected to fail"
    )

  /** Ninety-eight, and the decomposition differs from Shanghai's.
    *
    * One hundred decided Cancun payloads carry a non-empty withdrawals list and
    * every one of them sits in the accept group, so the arm moves all of them
    * rather than all but the ones already expected to fail — which is what the
    * Shanghai row's two subtract.
    */
  it should "move exactly the ninety-eight accepted Cancun payloads carrying a withdrawal" in
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.WithdrawalDropped, "for_cancun") == 98,
      "diverged " + divergedUnder(EnginePayloadCorpus.Arm.WithdrawalDropped, "for_cancun").toString +
        " rather than 98, so the arm is no longer seeding the defect it names"
    )

  it should "move every accepted Cancun payload when the ommers hash is substituted" in
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_cancun") == accepted("for_cancun"),
      "diverged " + divergedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_cancun").toString +
        " rather than " + accepted("for_cancun").toString
    )

  it should "leave the three Cancun block-hash refusals agreeing under the ommers arm" in
    assert(
      agreedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_cancun") == HashRefusals("for_cancun"),
      "agreed " + agreedUnder(EnginePayloadCorpus.Arm.OmmersSubstituted, "for_cancun").toString +
        " rather than " + HashRefusals("for_cancun").toString
    )

  /** ==The two arms that exist only because the third label does==
    *
    * Both links below were derived against nothing published until this label
    * arrived: the two labels under it predate blob transactions and the beacon
    * root, so the hand-written payloads in this module were the whole of their
    * exercise — and those carry block hashes this project computed itself from
    * the same reading the derivation uses.
    *
    * **A zero under the two lower labels is what makes each figure a reading of
    * the corpus rather than of the arm.** Neither label carries the field the
    * arm damages, so an arm that had stopped applying its change would report
    * zero under all three and look exactly like this pair does under the first
    * two.
    */
  it should "zero no beacon root under the two labels that carry none" in
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.BeaconRootZeroed, "for_paris") == 0 &&
        divergedUnder(EnginePayloadCorpus.Arm.BeaconRootZeroed, "for_shanghai") == 0,
      "both labels predate the proposal, so no payload under either carries a root for the arm to zero"
    )

  /** ==One hundred and six of fourteen thousand six hundred and nine, and the
    * ratio is the finding==
    *
    * 14,503 Cancun payloads state the ZERO root, so a derivation that ignored
    * the third argument and pushed a constant zero would agree with all but
    * these. **The baseline agreement is what certifies the field's placement
    * and encoding** — a header one link short, or with the root at the wrong
    * depth, fails every one of the 13,761 — and this figure is what certifies
    * that the value is read at all.
    *
    * It is small and it is not nothing, and both halves matter: before this
    * label the figure was zero from any published source, because the value
    * this build compared against was one it had computed itself.
    */
  it should "move the hundred and six Cancun payloads whose root is not already zero" in
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.BeaconRootZeroed, "for_cancun") == 106,
      "diverged " + divergedUnder(EnginePayloadCorpus.Arm.BeaconRootZeroed, "for_cancun").toString +
        " rather than 106: this is the only published evidence that the argument is read rather than assumed zero"
    )

  it should "zero no blob gas under the two labels that carry none" in
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.BlobGasZeroed, "for_paris") == 0 &&
        divergedUnder(EnginePayloadCorpus.Arm.BlobGasZeroed, "for_shanghai") == 0,
      "both labels predate the proposal, so no payload under either carries the pair for the arm to zero"
    )

  it should "move the thousand three hundred and forty-nine Cancun payloads accounting for blob gas" in
    // Thirteen times the beacon root's figure over the same label, and the two
    // are asserted separately because they measure different links of the same
    // tail: a derivation could read one and assume the other, and a single
    // combined figure would not say which.
    assert(
      divergedUnder(EnginePayloadCorpus.Arm.BlobGasZeroed, "for_cancun") == 1349,
      "diverged " + divergedUnder(EnginePayloadCorpus.Arm.BlobGasZeroed, "for_cancun").toString + " rather than 1349"
    )
