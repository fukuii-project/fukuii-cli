package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}
import org.fukuii.chainspec.{Activation, UpgradeRules}
import org.fukuii.evm.{NewAccountCharge, Opcode, PrecompileSet}
import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus}

/** Atlantis's rule set against Ethereum Classic's own state fixtures.
  *
  * ==Why this tier rather than a published one==
  *
  * No published corpus can certify this upgrade at this chain's height, and the
  * two tiers fail for opposite reasons. The generated tier's Byzantium
  * directory signs 1807 of its 1845 cases for chain 1, so resolving it through
  * this chain refuses nearly all of them as signed for another chain -- the
  * harness disagreeing with itself rather than a divergence. The legacy tier
  * publishes no signed bytes at all, so a stated sender stands and the chain is
  * never consulted; its expectations there are satisfied by Ethereum's own
  * Byzantium rules, which this build already holds equal to Atlantis's on all
  * three facets the runner reads. A tier that agrees whichever network it is
  * resolved through is not evidence about either.
  *
  * ==What these expectations rest on, and where the chain stops==
  *
  * `fukuii-project/fukuii-tests` @
  * `044499e9e49dd5e705fbdda87457fd480e446c8f`, branch `main`,
  * `networks/ethereumclassic/mainnet/state`. That tree is unchanged at this
  * path since the commit the Die Hard tier cites, so the two tiers read the
  * same forty-five files.
  *
  * **The path's own tree object is `fe6fec1d956c8bc870b0e4c8705855c0a3e79c62`,
  * and it is cited beside the commit because it is the half that survives.** A
  * commit can be rebased away and then resolves for nobody who clones; a tree
  * is content-addressed, so it names these forty-five files for as long as any
  * commit reaches them. This citation is the second one here -- the first named
  * a commit that was rebased out from under it within a day, while the tree it
  * pointed at never moved. Check a commit is still an ancestor before trusting
  * it: `git merge-base --is-ancestor <sha> HEAD`.
  *
  * Every file names `core-geth` as its oracle at one build,
  * `core-geth modernized 1.13.0-26975d6a`. **Thirty-seven are filled through
  * that build's `t8n` and record a second pass through its own `statetest`
  * runner; the remaining eight are filled through `statetest` directly and
  * seven of those record no second pass**, correctly, since for them the
  * filling and verifying instrument would be the same one.
  *
  * **Which core-geth that is decides how much the agreement is worth, and the
  * version string does not say.** It is the modernized fork,
  * `white-b0x/core-geth` -- not `ethereumclassic/core-geth`, which carries no
  * such version: its newest tag is `v1.12.20` and the clone this file cites
  * elsewhere describes as `v1.12.20-8-g4185df450`. A reader who takes
  * `1.13.0` for a production release is reading a version that does not exist
  * there, which is the confusion this paragraph is written to prevent.
  *
  * **That fork is a client this project has worked on, so the oracle is not
  * independent of this project in the way an upstream one would be.** The
  * honest statement of what a pass here establishes is therefore narrower than
  * it looks: this implementation and that one agree. It is not that either is
  * right. Where a fixture's only oracle is that client, a shared error is
  * invisible to this tier by construction -- and a second pass is a second
  * RUNNER rather than a second implementation, which does not close that gap.
  *
  * These are still not expectations authored from a reading of a specification,
  * which would make agreement one reading repeated.
  *
  * **Provenance is the UNION of a fixture's `_info` provenance keys, and
  * `oracle` alone under-reads it.** That key reads as a complete statement and
  * is not one: a file may carry a second independent pass beside it under
  * another key, and two of this corpus's difficulty files do -- a client-run
  * generation under `oracle` and an independent re-derivation from the
  * specifications under `second-derivation`, each with its own firing controls.
  * Read on `oracle` alone they classify as single-source; read whole they are
  * the strongest material the corpus has.
  *
  * **This is recorded because both projects made the identical misreading on
  * one day, from opposite directions, with the file open.** The corpus's own
  * census scanned the whole block and had them right; a prose summary that read
  * one field overrode it. **A correct instrument overridden by a summary is
  * worse than a wrong instrument, because its correctness supplies the
  * confidence.**
  *
  * With that read whole: across the wider suite a majority of authored fixtures
  * record this client as sole oracle with no corroboration anywhere in the
  * block, so a pass is worth most where the provenance keys together name a
  * specification re-derivation or a client neither project maintains, and least
  * where they do not.
  *
  * ==One thing is stronger at this label than at Die Hard, and it is checkable==
  *
  * The Die Hard tier's fixtures state why the modernized build was needed:
  * production's `t8n` fork table has no name for that upgrade, so nothing else
  * could have filled them. **That reason does not apply here.**
  * `ethereumclassic/core-geth` @ `4185df450` carries `"ETC_Atlantis"` in the
  * `Forks` map of `tests/init.go:65`, and `ETC_DieHard`, `ETC_Gotham` and
  * `ETC_Defuse` appear nowhere in that tree at all -- calibrated against the
  * label that is there, which the same sweep finds. So this label names rules
  * the production build can itself address.
  *
  * **What that does NOT establish is that the production build would answer
  * the same**, which the tree asserts and this project has not re-derived. The
  * claim is that the two builds produce identical roots at every upgrade both
  * can address; taking it on the tree's word is what this reading stops short
  * of, and the fork-table membership is what a reader can check.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled. A cancelled test is counted by nothing, so a
  * build whose corpus vanished reports the same executed total as one that ran
  * it.
  *
  * ==The figures are literals, so a corpus that shrank is a failure==
  *
  * Every count below is stated rather than derived from the run, for the reason
  * the Die Hard tier states: a tier that lost a file would otherwise report a
  * smaller green.
  */
class ClassicAtlantisStateCertificationSpec extends AnyFlatSpec:

  /** Files the tier states its cases in. */
  private val Files: Int = 45

  /** Runnable combinations across those files, at this label. */
  private val Cases: Int = 54

  /** Cases this build answers, which is every one the tier states here. */
  private val Certified: Int = 54

  /** Cases that answer differently once REVERT leaves the operation table. */
  private val MovedWithoutRevert: Int = 2

  /** Cases that answer differently once the return-data buffer's two operations
    * leave the table.
    *
    * Named rather than counted below: the pair is what EIP-211 adds, and a
    * corpus that lost one of them and gained an unrelated case would report the
    * same number.
    */
  private val WithoutTheBufferTheseMove: Vector[String] =
    Vector("returndatacopyAcrossUpgrades[d0g0v0]", "returndatasize_availability[d0g0v0]")

  /** Cases that answer differently once STATICCALL leaves the table.
    *
    * Three rather than the two an availability-and-semantics pair would give,
    * because one further case measures what a read-only frame does to the
    * access list -- an interaction of this operation with a rule five upgrades
    * later, which neither document states alone.
    */
  private val MovedWithoutStaticCall: Int = 3

  /** Cases that answer differently once the deployed-code bound is lifted.
    *
    * One, and it is named below. The bound is what separates a 24,576-byte
    * deployment from a 24,577-byte one, and the accepted half of that pair is
    * the fixture's own control -- so a rule that stopped binding would move the
    * rejected case alone.
    */
  private val WithoutTheCodeBoundThisMoves: String = "codeSizeLimitAcrossUpgrades[d1g0v0]"

  /** Cases that answer differently once a created account's nonce starts at
    * zero again.
    *
    * EIP-161 is four clauses and this tier separates them, which is worth
    * asserting rather than folding into one figure: a single count over the
    * whole document would be satisfied by any one clause still being read.
    */
  private val MovedWithoutTheCreatedAccountNonce: Int = 6

  /** Cases that answer differently once the surcharge is charged on absence
    * again rather than on value reaching a dead destination.
    *
    * ==Reading the corpus predicts seven and the run says nine==
    *
    * A census of the fixtures whose own comment names this clause reaches
    * seven: the two that are about it, and the five natives whose gas the
    * charge for touching a non-existent address dominates. What the census
    * cannot see is that a probe storing a marker still TOUCHES the address it
    * probed, so two availability fixtures written about entirely different
    * operations are priced by this clause as well. **The comment field
    * measures what a fixture is ABOUT and this measures what it can DECIDE**,
    * and the gap between them is a property of how every probe here is built
    * rather than a defect in either.
    */
  private val MovedWithoutTheNarrowedSurcharge: Int = 9

  /** Cases that answer differently once a touched empty account survives the
    * transaction.
    *
    * Nine as well, and that coincidence is why the two are also asserted as
    * SETS below: two clauses reaching nine cases each is indistinguishable
    * from one clause counted twice, and this pair is not that.
    */
  private val MovedWithoutClearingAtTheEnd: Int = 9

  /** The one case the surcharge clause decides and the clearing clause does
    * not, and the one case the reverse holds for.
    *
    * ==Two nines that are different nines, which no count can say==
    *
    * The two clauses agree on eight cases and each reaches one the other
    * cannot. The surcharge alone decides a self-destruction's refund, because
    * what a dying contract pays to reach its beneficiary is a gas figure and
    * the refund is billed against it; the clearing clause alone decides a
    * read-only call's availability probe, because that probe's target is left
    * holding nothing and whether it survives is in the root while its price is
    * not.
    *
    * **Asserted because the equal counts above would otherwise be the whole of
    * what this tier says about the document.** A build that read one clause and
    * ignored the other would satisfy both counts if the two sets were the same;
    * these two names are what makes that unsatisfiable.
    */
  private val DecidedByTheSurchargeAlone: String = "selfdestructRefundAcrossUpgrades[d0g0v0]"
  private val DecidedByTheClearingAlone: String = "staticcall_availability[d0g0v0]"

  /** Cases that answer differently once the four natives leave the precompile
    * set.
    *
    * Three documents place them and they are removed together, because the
    * corpus's own control for the group is the one native that arrives two
    * upgrades later: its gas moves here exactly as theirs does while its output
    * stays empty, which is what separates a native appearing from the charge
    * for touching an address that does not exist.
    */
  private val MovedWithoutTheByzantiumNatives: Int = 4

  /** Cases that answer differently once a receipt carries a root again rather
    * than a status.
    *
    * ==Zero, and it is the finding rather than a gap==
    *
    * This is the one Atlantis proposal that writes a rule the runner reads and
    * that no case here can decide. A post entry states a post-state root, a
    * logs hash and the signed transaction; none of the three is a receipt, so
    * there is no field a state fixture could put the answer in. The corpus says
    * as much itself and points at a block-level fixture, where the receipts
    * root a header carries is the natural observable.
    *
    * **EIP-100 is NOT asserted the same way, and the difference is what makes
    * this row worth having.** That proposal writes the consensus facet, which
    * this runner never reads at all -- so a zero there would measure the
    * harness's inputs rather than the corpus, which is a vacuous control. This
    * one moves nothing while sitting on a facet the runner does read.
    *
    * ==What a zero row can and cannot be falsified by, measured rather than
    * argued==
    *
    * Inverting this document's own delta in the build -- a receipt carrying the
    * root again at every fork -- leaves all twenty-one registrations here
    * passing. So nothing in this file asserts that the rule is implemented, and
    * that is the claim: no case in this tier can see it.
    *
    * **The same measurement bounds the row itself.** Replacing the alteration
    * above with one that changes nothing also leaves every registration
    * passing, so this row cannot be falsified by its own instrument breaking --
    * only by the corpus gaining a case that can see a receipt, which is the one
    * event it exists to report. The rows either side of it, whose alterations
    * ARE falsifiable that way, are what keep a silently-dead harness from
    * reading as a tier that decides nothing.
    */
  private val MovedWithoutTheReceiptStatus: Int = 0

  /** Cases that answer differently when this tier is resolved one fork lower.
    *
    * The control the whole registration depends on. A tier whose expectations
    * are satisfied by the rules of the fork below it is not evidence about the
    * fork it is named for, and twelve labels over one transaction is exactly
    * the shape that can look like broad coverage and decide nothing.
    *
    * **Twenty-two of fifty-four, where the same measurement at Die Hard's own
    * boundary reaches two.** The corpus re-signs each case per label below Die
    * Hard, so a signature that predates the chain identifier stays valid and
    * only two cases there can disagree at all. Both labels here are above that
    * boundary, so every case carries byte-identical transaction bytes at the
    * two and what differs is what the rules do with them.
    */
  private val MovedAtTheForkBelow: Int = 22

  /** Cases that disagree when Atlantis's rules are asked the expectations the
    * fork below files.
    */
  private val DivergingAtTheLabelBelow: Int = 22

  /** Cases that disagree when Atlantis's rules are asked the expectations the
    * fork above files.
    *
    * ==Atlantis is not in a group, and Die Hard was==
    *
    * Die Hard's rules satisfy the two labels above it exactly, because both
    * adopt proposals a state fixture cannot observe. Nothing of the sort holds
    * here: the upgrade above adds operations and a derivation the machine runs,
    * so this tier separates the two. Asserted as a non-zero rather than left
    * implicit, because it is the property the Die Hard tier could not have.
    */
  private val DivergingAtTheLabelAbove: Int = 6

  /** The label of the fork below this one. */
  private val LabelBelow: String = "ETC_DefuseDifficultyBomb"

  /** The label of the fork above this one. */
  private val LabelAbove: String = "ETC_Agharta"

  /** Files read when the label names an upgrade this corpus files nothing
    * under, and the cases each of them then declines to answer.
    *
    * The reader's own control: it dispatches on the post key, so a label the
    * corpus does not carry must report every file as stating no expectation
    * rather than silently matching something.
    */
  private val UnfilledLabel: String = "ETC_Thanos"

  private val report: CorpusReport =
    ClassicStateCorpus.atlantis.getOrElse(
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
      .reportAt("control", ClassicStateCorpus.AtlantisFork, height, change)
      .getOrElse(fail("assembled once and not the second time"))

  /** The same tree at another label, resolved at this fork's own height. */
  private def readAs(label: String): CorpusReport =
    ClassicStateCorpus
      .reportAt("control", label, ClassicStateCorpus.AtlantisStarts, identity)
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
    moved(under(ClassicStateCorpus.AtlantisStarts, change))

  /** The operation that returns unused gas and rolls the frame back. */
  private val withoutRevert: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(table = rules.evm.table.removing(Opcode.Revert)))

  /** Both operations that read the return-data buffer. */
  private val withoutTheBuffer: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm =
        rules.evm.copy(table = rules.evm.table.removing(Opcode.ReturnDataSize).removing(Opcode.ReturnDataCopy))
      )

  /** The read-only call. */
  private val withoutStaticCall: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(table = rules.evm.table.removing(Opcode.StaticCall)))

  /** No bound at all on what a creation may leave behind, which is the state
    * this network was in at the fork below.
    */
  private val withoutTheCodeBound: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(maxCodeSize = None))

  /** A created account counted from zero again. */
  private val withoutTheCreatedAccountNonce: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(createdAccountNonce = UInt64.Zero))

  /** The surcharge levied on absence again rather than on value reaching a dead
    * destination.
    */
  private val withoutTheNarrowedSurcharge: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(newAccountCharge = NewAccountCharge.WhenTheDestinationIsAbsent))

  /** A touched empty account surviving the transaction that touched it. */
  private val withoutClearingAtTheEnd: UpgradeRules => UpgradeRules =
    rules => rules.copy(execution = rules.execution.copy(touchedEmptyAccountsAreDeleted = false))

  /** The four addresses three documents place natives at. */
  private val withoutTheByzantiumNatives: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm =
        rules.evm.copy(precompiles =
          rules.evm.precompiles
            .removing(PrecompileSet.ModExp)
            .removing(PrecompileSet.AltBn128Add)
            .removing(PrecompileSet.AltBn128Mul)
            .removing(PrecompileSet.AltBn128PairingCheck)
        )
      )

  /** A receipt carrying the root the state reached again, in place of a status. */
  private val withoutTheReceiptStatus: UpgradeRules => UpgradeRules =
    rules => rules.copy(execution = rules.execution.copy(receiptCarriesStatus = false))

  "this chain's state tier at Atlantis" should "be read in full" in
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
      schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(ClassicStateCorpus.AtlantisStarts))),
      "resolving a corpus through a height no fork begins at certifies a neighboring fork's rules under this " +
        "one's name: " + schedule.forkPoints.toString
    )
  }

  "the harness" should "leave every case answered when nothing is altered" in
    assert(
      movedAtThisFork(identity).isEmpty,
      "the control path must not perturb the run, or every count below measures the control: " +
        under(ClassicStateCorpus.AtlantisStarts, identity).describe
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

  "a build without EIP-140" should "lose the cases that revert" in
    assert(
      movedAtThisFork(withoutRevert).length == MovedWithoutRevert,
      "one case measures what a revert returns and one measures what it undoes beyond storage: " +
        movedAtThisFork(withoutRevert).mkString(", ")
    )

  "a build without EIP-211" should "lose both cases that read the return-data buffer, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheBuffer).sorted == WithoutTheBufferTheseMove,
      "the document adds two operations and a corpus that lost one of them and gained an unrelated case would " +
        "report the same count: " + movedAtThisFork(withoutTheBuffer).mkString(", ")
    )

  "a build without EIP-214" should "lose the cases that call read-only" in
    assert(
      movedAtThisFork(withoutStaticCall).length == MovedWithoutStaticCall,
      "availability, write protection, and what a read-only frame leaves warmed behind it: " +
        movedAtThisFork(withoutStaticCall).mkString(", ")
    )

  "a build without EIP-170" should "lose the one case a deployed-code bound decides, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheCodeBound) == Vector(WithoutTheCodeBoundThisMoves),
      "the accepted half of that pair is the fixture's own control and must not move: " +
        movedAtThisFork(withoutTheCodeBound).mkString(", ")
    )

  "a build without EIP-161's created-account clause" should "lose every case that creates a contract" in
    assert(
      movedAtThisFork(withoutTheCreatedAccountNonce).length == MovedWithoutTheCreatedAccountNonce,
      "a created account's nonce is in the state root, so every successful creation here answers differently: " +
        movedAtThisFork(withoutTheCreatedAccountNonce).mkString(", ")
    )

  "a build without EIP-161's narrowed surcharge" should "lose the cases that touch an address holding nothing" in
    assert(
      movedAtThisFork(withoutTheNarrowedSurcharge).length == MovedWithoutTheNarrowedSurcharge,
      "the charge moves from absence to value reaching a dead destination, and every zero-value touch here is " +
        "priced by which of the two governs: " + movedAtThisFork(withoutTheNarrowedSurcharge).mkString(", ")
    )

  "a build without EIP-161's clearing clause" should "lose the cases that leave an empty account behind" in
    assert(
      movedAtThisFork(withoutClearingAtTheEnd).length == MovedWithoutClearingAtTheEnd,
      "an account reached and left holding nothing is deleted when the transaction ends, and whether it is " +
        "deleted is in the root: " + movedAtThisFork(withoutClearingAtTheEnd).mkString(", ")
    )

  "EIP-161's two gas-and-state clauses" should "not be the same nine cases, named rather than counted" in {
    val surcharge = movedAtThisFork(withoutTheNarrowedSurcharge).toSet
    val clearing = movedAtThisFork(withoutClearingAtTheEnd).toSet
    assert(
      (surcharge -- clearing) == Set(DecidedByTheSurchargeAlone) &&
        (clearing -- surcharge) == Set(DecidedByTheClearingAlone),
      "two clauses reaching nine cases each is indistinguishable from one clause counted twice unless the two " +
        "sets are compared: surcharge alone " + (surcharge -- clearing).toVector.sorted.mkString(", ") +
        " and clearing alone " + (clearing -- surcharge).toVector.sorted.mkString(", ")
    )
  }

  "a build without EIP-198, EIP-196 and EIP-197" should "lose the cases that call the four natives" in
    assert(
      movedAtThisFork(withoutTheByzantiumNatives).length == MovedWithoutTheByzantiumNatives,
      "one case per address, and the native arriving two upgrades later is the group's own control: " +
        movedAtThisFork(withoutTheByzantiumNatives).mkString(", ")
    )

  "a build without EIP-658" should "lose nothing, because no case here can see a receipt" in
    assert(
      movedAtThisFork(withoutTheReceiptStatus).length == MovedWithoutTheReceiptStatus,
      "a post entry carries a state root, a logs hash and the signed transaction, and none of the three is a " +
        "receipt -- so a case answering differently would mean the rule had reached something else: " +
        movedAtThisFork(withoutTheReceiptStatus).mkString(", ")
    )

  "this tier resolved at the fork below" should "lose the cases that make it evidence about Atlantis" in
    assert(
      moved(under(ClassicStateCorpus.DefuseStarts, identity)).length == MovedAtTheForkBelow,
      "a tier satisfied by the rules of the fork below it says nothing about the fork it is named for: " +
        under(ClassicStateCorpus.DefuseStarts, identity).describe
    )

  "this tier read under the label below" should "disagree with Atlantis's rules" in
    assert(
      readAs(LabelBelow).diverged.length == DivergingAtTheLabelBelow,
      "the label is what the reader dispatches on, so a tier agreeing with the fork below's expectations under " +
        "this fork's rules would not be evidence about either: " + readAs(LabelBelow).describe
    )

  "this tier read under the label above" should "disagree with Atlantis's rules" in
    assert(
      readAs(LabelAbove).diverged.length == DivergingAtTheLabelAbove,
      "the upgrade above adds operations the machine runs, so unlike Die Hard this fork is not in a group of " +
        "labels a state tier cannot separate: " + readAs(LabelAbove).describe
    )
