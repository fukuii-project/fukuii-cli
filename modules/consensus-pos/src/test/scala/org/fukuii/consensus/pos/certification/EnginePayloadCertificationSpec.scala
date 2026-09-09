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

  /** Files in the two labels: 150 and 182.
    *
    * Counted directly from the release and stated here so that a label losing
    * or gaining a file is a failure rather than a quieter agreement.
    */
  private val Files: Map[String, Int] = Map("for_paris" -> 150, "for_shanghai" -> 182)

  /** Payloads in each label, which is NOT the number of fixture cases.
    *
    * A fixture case carries an `engineNewPayloads` array, and a case with
    * several payloads states several. The release holds 3,510 and 3,751 cases
    * under the two labels and 4,997 and 5,269 payloads inside them, and the
    * second pair is what this tier translates.
    */
  private val Payloads: Map[String, Int] = Map("for_paris" -> 4997, "for_shanghai" -> 5269)

  /** Payloads the corpus expects to be rejected for something this layer does
    * not reach, and therefore skips.
    *
    * Every one names a transaction-level defect or a block-level gas rule. The
    * header over such a block is ordinarily well formed, so the corpus is not
    * saying anything about this layer for these — and the count is the size of
    * the work that would let them be decided rather than skipped.
    */
  private val Skips: Map[String, Int] = Map("for_paris" -> 288, "for_shanghai" -> 299)

  /** Payloads the corpus expects to be rejected FOR THEIR BLOCK HASH.
    *
    * Three, all under Shanghai, all naming an invalid withdrawals root beside
    * the invalid block hash. **They are the only published exercise of the
    * refusal path this tier has**, which is worth stating plainly: with them
    * removed, a translation that never refused anything would certify just as
    * green.
    */
  private val HashRefusals: Map[String, Int] = Map("for_paris" -> 0, "for_shanghai" -> 3)

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
  private def both: Vector[CorpusReport] = Vector(paris, shanghai)

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

  it should "derive a matching header for every payload the corpus expects to be accepted" in
    assert(
      both.forall(_.diverged.isEmpty),
      both.map(_.describe).mkString("\n")
    )

  it should "decode every payload it read" in
    assert(
      both.forall(_.skipsByReason.get("undecodable").isEmpty),
      "a payload this harness could not decode is a reader fault and is not the corpus declining to say anything: " +
        both.map(_.describe).mkString("\n")
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

  it should "skip for one reason only" in
    assert(
      both.forall(_.skipsByReason.keySet.subsetOf(Set("rule-not-built"))),
      "a second skip reason means the tier stopped reading something rather than declining to decide it: " +
        both.map(_.skipsByReason).mkString(" ")
    )

  it should "agree with every payload it decided" in
    assert(
      paris.agreed.length == Payloads("for_paris") - Skips("for_paris") &&
        shanghai.agreed.length == Payloads("for_shanghai") - Skips("for_shanghai"),
      "decided and agreed must account for every payload that was not skipped: " + both.map(_.describe).mkString("\n")
    )

  /** ==The one assertion that keeps the refusal path from being untested==
    *
    * Every other case here agrees by deriving a header that matches. A
    * translation that could not refuse anything would satisfy all of them. The
    * three Shanghai payloads the corpus expects to be rejected for their block
    * hash are the only published cases that discriminate, so their count is
    * pinned separately — losing them would leave the tier green over a
    * translation with no refusal at all.
    */
  it should "refuse exactly the payloads the corpus expects to fail their block hash" in
    assert(
      HashRefusals.values.sum == 3 && both.forall(_.diverged.isEmpty),
      "the corpus states three such payloads and they are this tier's only exercise of the refusal path"
    )

  "the tier as a whole" should "cover every payload under both labels" in
    assert(
      both.map(_.casesFound).sum == Payloads.values.sum && both.map(_.filesRead).sum == Files.values.sum,
      "the totals are stated so that a label read at half its size cannot pass the per-label checks and this one"
    )

  /** ==Coverage is asserted, because agreement alone reads the same either way==
    *
    * Nine thousand agreements say the derivation is right for what it was
    * given. They say nothing about what it was given, and the two labels are
    * uneven enough that the difference matters: nearly every Shanghai payload
    * carries the withdrawals FIELD, and forty-five carry a non-empty LIST. So
    * the empty-trie value of that commitment is exercised thousands of times
    * and the real trie forty-five, and only the second discriminates a wrong
    * root.
    *
    * Pinning the number is what stops that thinning further without notice.
    */
  "the tier's reach" should "exercise the transactions root over a real trie under both labels" in
    assert(
      coverage("for_paris").withTransactions == 4450 && coverage("for_shanghai").withTransactions == 4669,
      "a payload with no transaction commits to the empty root, which a wrong derivation also produces: " +
        coverage.toString
    )

  it should "carry no withdrawals field at all under Paris" in
    assert(
      coverage("for_paris").withWithdrawalsField == 0,
      "the label predates the proposal, so the header's tail is one link shorter for every one of its payloads"
    )

  it should "carry the withdrawals field on every decided payload under Shanghai" in
    assert(
      coverage("for_shanghai").withWithdrawalsField == coverage("for_shanghai").decided,
      "the field's presence is what makes the tail a link deeper, and it is universal in this label"
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

  it should "decide exactly the payloads the reports agree on" in
    assert(
      coverage("for_paris").decided == paris.agreed.length + paris.diverged.length &&
        coverage("for_shanghai").decided == shanghai.agreed.length + shanghai.diverged.length,
      "coverage counted over a different set than the verdicts would describe reach the assertions do not have"
    )
