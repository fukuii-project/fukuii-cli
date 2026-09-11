package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.proposals.eip.{Eip1153, Eip5656, Eip6780}
import org.fukuii.chainspec.{Component, ProposalId, UpgradeRules}
import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus}

/** The Cancun documents this build has adopted, against five published state
  * directories.
  *
  * ==The rules these are read under are a COMPOSITION, and that is the whole of
  * what makes this spec unusual==
  *
  * Every other state tier here is resolved through a network's schedule at a
  * height, so it certifies an activation as well as a machine. These five
  * cannot be: the fork their files are filled for is being built one proposal
  * at a time, and there is no activation for a rule set holding three of its
  * six. So they name `Upgrades.shanghai` with the adopted components, and the
  * first registration below is what makes that mean anything -- without it,
  * five corpora would be certifying a composition nothing had checked was the
  * one their names describe.
  *
  * **This is not the same case as `LegacyConstantinopleStateCorpus`.** That one
  * names a composition because its rule set is unreachable by construction.
  * This one names a composition because the fork is unfinished, and it stops
  * being the right shape the moment that fork exists.
  *
  * ==The five DECIDE three proposals, and the figures are the deliverable==
  *
  * Every count below is a differential -- how many cases answer differently
  * once a proposal is withdrawn -- rather than a size. A directory named for a
  * proposal is routinely taken to certify it, and the figures show why that is
  * not free.
  *
  * | directory | cases | w/o EIP-1153 | w/o EIP-5656 | w/o EIP-6780 | w/o all three |
  * |---|---|---|---|---|---|
  * | `cancun/eip5656_mcopy` | 99 | 0 | 61 | 0 | 61 |
  * | `ported_static/stEIP5656_MCOPY` | 106 | 0 | 90 | 0 | 90 |
  * | `ported_static/stEIP1153_transientStorage` | 48 | 48 | 0 | 0 | 48 |
  * | `cancun/eip6780_selfdestruct` | 136 | 0 | 0 | 48 | 48 |
  * | `cancun/eip1153_tstore` | 123 | 121 | 0 | 2 | 121 |
  *
  * **The nine zeroes are the load-bearing half.** Reading a directory filled
  * for a whole fork under a rule set carrying three of its proposals is sound
  * only where the directory cannot tell the difference, and a zero is what says
  * the proposal it is measured against is inert over those files. Each zero
  * sits beside a non-zero from the same machinery over the same corpus, so none
  * of them is a rerun that silently failed to apply its change.
  *
  * **The last row is the one that is not a partition, and it is the point of
  * this phase.** Everywhere else the withdrawal sets are disjoint and the
  * all-three figure is their sum. In `cancun/eip1153_tstore` the two cases
  * EIP-6780 decides are INSIDE the 121 EIP-1153 decides, so the union is 121
  * rather than 123. Those two cases are why that directory could not be
  * certified before this phase: they were its only divergences under the
  * previous composition, and both were a destruction's rule rather than a
  * transient-storage one.
  *
  * **No non-zero figure is the whole directory, and that gap is the point of
  * measuring rather than counting.** 48 of 136 cases in the directory named for
  * the destruction proposal move when it is withdrawn; the other 88 answer the
  * same either way. Whatever the reason in each, only the differential says
  * which those are.
  *
  * ==What this tier CANNOT see, stated rather than left to be assumed==
  *
  *   - **The other three proposals of the fork.** None of them is in the
  *     composition, so no case here is evidence about EIP-4844, EIP-7516 or
  *     EIP-4788. A pass here says nothing about any of them.
  *   - **`currentExcessBlobGas`.** Every case in all five states it in its `env`
  *     and the reader models no such field. Nothing here reads it: every
  *     transaction in all five directories is a format predating blob carriage,
  *     and no operation that would consult it is in the table. So the agreement
  *     below is not evidence that the field can go on being ignored -- it is
  *     evidence that these files never ask.
  *   - **The gas figure any of these operations costs, as a figure.** A `post`
  *     entry states a root and no gas, so a wrong charge is visible here only
  *     through what the sender keeps and the producer is credited. That is a
  *     real signal and it is not the same as reading the number, which the unit
  *     specs in `modules/evm` do against the documents' own worked examples.
  *   - **A destruction whose balance is BURNT rather than kept.** The
  *     differential establishes that 48 cases turn on the proposal; it does not
  *     establish that any of them names its own account as beneficiary, which
  *     is the sub-case where the two rule sets differ about value rather than
  *     about existence. `org.fukuii.evm.SelfDestructScopeSpec` asserts that half
  *     directly, under both rule sets, which no corpus reading can substitute
  *     for.
  *
  * ==What it CAN see that the unit specs cannot, which is why it is worth the
  * run==
  *
  * A destruction reached through published material rather than through cases
  * this build wrote for itself, in shapes nobody here would have thought to
  * write: `dynamic_create2_selfdestruct_collision`, `journal_revert`,
  * `reentrancy_selfdestruct_revert`, `recursive_contract_creation_and_selfdestruct`
  * and `self_destructing_initcode_create_tx` are the corpus's own directory
  * names. **They are read as vocabulary rather than as a measurement** -- what a
  * case is called is a claim by whoever filled it, and the differential above is
  * what establishes that the directories decide anything at all.
  */
class CancunCompositionCertificationSpec extends AnyFlatSpec:

  /** The components these corpora are read under, in the order the composition
    * takes them.
    */
  private val Adopted: Vector[Component] =
    Vector(Eip1153.component, Eip5656.component, Eip6780.component)

  private val TransientStorage: ProposalId = ProposalId.Eip(1153)

  private val MemoryCopy: ProposalId = ProposalId.Eip(5656)

  private val SelfDestructScope: ProposalId = ProposalId.Eip(6780)

  /** The rules the five corpora name, rebuilt here.
    *
    * Adoption has no inverse -- a component is an arbitrary function over the
    * whole rule set -- so a proposal is withdrawn by rebuilding without it
    * rather than by undoing it. The first registration is what establishes that
    * this rebuild reproduces what the harness resolved the corpora at.
    */
  private val composed: UpgradeRules = ethereum.Upgrades.shanghai.adopting(Adopted*)

  private def withoutAll(dropped: Set[ProposalId]): UpgradeRules => UpgradeRules =
    _ => ethereum.Upgrades.shanghai.adopting(Adopted.filterNot(held => dropped.contains(held.id))*)

  private val Copying: String = CertificationCorpora.GeneratedCancunMemoryCopyCorpus

  private val PortedCopying: String = CertificationCorpora.PortedStaticCancunMemoryCopyCorpus

  private val PortedTransient: String = CertificationCorpora.PortedStaticCancunTransientStorageCorpus

  private val Destroying: String = CertificationCorpora.GeneratedCancunSelfDestructCorpus

  private val Transient: String = CertificationCorpora.GeneratedCancunTransientStorageCorpus

  private val corpora: Vector[String] = Vector(Copying, PortedCopying, PortedTransient, Destroying, Transient)

  /** Files and cases, stated as literals so a corpus that shrank is a failure
    * rather than a smaller pass.
    */
  private val Census: Map[String, (Int, Int)] =
    Map(
      Copying -> (7, 99),
      PortedCopying -> (3, 106),
      PortedTransient -> (4, 48),
      Destroying -> (15, 136),
      Transient -> (19, 123)
    )

  private val Differential: Map[(ProposalId, String), Int] =
    Map(
      (TransientStorage, Copying) -> 0,
      (TransientStorage, PortedCopying) -> 0,
      (TransientStorage, PortedTransient) -> 48,
      (TransientStorage, Destroying) -> 0,
      (TransientStorage, Transient) -> 121,
      (MemoryCopy, Copying) -> 61,
      (MemoryCopy, PortedCopying) -> 90,
      (MemoryCopy, PortedTransient) -> 0,
      (MemoryCopy, Destroying) -> 0,
      (MemoryCopy, Transient) -> 0,
      (SelfDestructScope, Copying) -> 0,
      (SelfDestructScope, PortedCopying) -> 0,
      (SelfDestructScope, PortedTransient) -> 0,
      (SelfDestructScope, Destroying) -> 48,
      (SelfDestructScope, Transient) -> 2
    )

  /** How many cases move when every adopted document is withdrawn at once.
    *
    * The union of the three columns above rather than their sum, and the two
    * are equal in every corpus but the last -- where two cases are decided
    * twice, so 121 + 2 sums to 123 and unions to 121.
    */
  private val WithoutAnyOfThem: Map[String, Int] =
    Map(Copying -> 61, PortedCopying -> 90, PortedTransient -> 48, Destroying -> 48, Transient -> 121)

  private val reports: Vector[CorpusReport] =
    CertificationCorpora.reports.getOrElse(
      fail(
        "no fixture corpus: write the directory holding one subdirectory per upstream organization into " +
          FixtureCorpus.RootPointer.toString + ", or set " + FixtureCorpus.RootVariable +
          " before the sbt server this task runs in was started. A run that cannot find it has measured nothing."
      )
    )

  private def report(corpus: String): CorpusReport =
    reports.find(_.corpus == corpus).getOrElse(fail("the harness assembles no corpus called " + corpus))

  /** Which cases answer differently under an altered run, by name.
    *
    * The pairing is checked before it is relied on: `zip` truncates to the
    * shorter side rather than complaining, so a rerun yielding fewer outcomes
    * would report a LOW count -- which reads as a corpus that decides less
    * rather than as a rerun that went wrong.
    */
  private def measure(corpus: String, change: UpgradeRules => UpgradeRules): Vector[String] =
    val asIs = report(corpus).outcomes
    val altered = CertificationCorpora
      .rerun(corpus, change)
      .getOrElse(fail("assembled once and not the second time: " + corpus))
      .outcomes
    if asIs.map(_.name) != altered.map(_.name) then
      fail(
        "the rerun of " + corpus + " did not answer for the same cases in the same order: " +
          asIs.length.toString + " outcomes first and " + altered.length.toString + " on the rerun"
      )
    else asIs.zip(altered).collect { case (before, after) if before != after => before.name }

  /** Every rerun this spec needs, run once.
    *
    * ==Computed here rather than per assertion, and that is a correctness
    * property as well as a cost one==
    *
    * A rerun costs the better part of a quarter-minute and several assertions
    * below read the same figure, so measuring per assertion would be the
    * expensive arrangement. It would also be the WRONG one: two assertions
    * measuring two different runs could disagree about how many cases a
    * proposal decides, and neither would be able to say which run it meant.
    */
  private lazy val moved: Map[(ProposalId, String), Vector[String]] =
    (for
      dropped <- Vector(TransientStorage, MemoryCopy, SelfDestructScope)
      corpus <- corpora
    yield (dropped, corpus) -> measure(corpus, withoutAll(Set(dropped)))).toMap

  private lazy val movedWithoutAnyOfThem: Map[String, Vector[String]] =
    corpora.map(corpus => corpus -> measure(corpus, withoutAll(Differential.keySet.map(_._1)))).toMap

  // ── What the figures below are figures ABOUT ──────────────────────────────

  "the adopted set recomposed from the fork below" should "be the rules these corpora are resolved at" in
    assert(
      corpora.forall(corpus => measure(corpus, _ => composed).isEmpty),
      "every differential below withdraws a proposal by rebuilding from the fork below, so a recomposition " +
        "that did not reproduce the rules the harness resolved these corpora at would make all fifteen " +
        "measurements about some third rule set"
    )

  it should "record all three adoptions, in order" in
    assert(
      composed.components.takeRight(3) == Vector(TransientStorage, MemoryCopy, SelfDestructScope),
      "the journal states which documents produced these rules, and every differential names one of them"
    )

  // ── The certification itself ──────────────────────────────────────────────

  "every corpus read under this composition" should "be read in full and agree with every case" in
    assert(
      corpora.forall { corpus =>
        report(corpus).filesRead == Census(corpus)._1 &&
        report(corpus).casesFound == Census(corpus)._2 &&
        report(corpus).agreed.length == Census(corpus)._2
      },
      corpora.map(report(_).describe).mkString("; ")
    )

  it should "include the directory left out of the previous composition" in
    // ADMITTING IT IS THIS PHASE'S PROOF, and no test total can carry that: a
    // corpus is one table-driven row, so this moves the census map and the
    // corpus count and moves the suite total by nothing. It was 121 of 123
    // before, and the two it could not answer are the two the row below names.
    assert(
      reports.exists(_.corpus == Transient) && report(Transient).agreed.length == 123,
      "the directory named for transient storage under the fork's own key was measured, deliberately " +
        "excluded rather than admitted with an allowance for known divergences, and is admitted here " +
        "because the rules can now answer it"
    )

  // ── The differentials ─────────────────────────────────────────────────────

  "withdrawing each document" should "move the stated cases in every directory" in
    assert(
      Differential.forall((key, expected) => moved(key).length == expected),
      "measured: " +
        Differential.keySet.toVector
          .sortBy((proposal, corpus) => (proposal.toString, corpus))
          .map((proposal, corpus) =>
            proposal.toString + " over " + corpus + " -> " + moved((proposal, corpus)).length.toString
          )
          .mkString("; ")
    )

  "withdrawing all three at once" should "move the union and not the sum" in
    // The property that rules out a case moving only when several are gone, and
    // the one place the union and the sum come apart is the last corpus.
    assert(
      corpora.forall(corpus => movedWithoutAnyOfThem(corpus).length == WithoutAnyOfThem(corpus)),
      "measured: " +
        corpora.map(corpus => corpus + " -> " + movedWithoutAnyOfThem(corpus).length.toString).mkString("; ")
    )

  it should "move exactly what the three move between them" in
    assert(
      corpora.forall { corpus =>
        val union = Vector(TransientStorage, MemoryCopy, SelfDestructScope)
          .flatMap(proposal => moved((proposal, corpus)))
          .toSet
        movedWithoutAnyOfThem(corpus).toSet == union
      },
      "a case that moved only when all three were withdrawn would be decided by an interaction rather than " +
        "by any one document, and no figure above would name it"
    )

  "the three documents" should "decide disjoint sets of cases in four of the five directories" in
    // NOT a general property, which is what the previous composition's spec
    // asserted and what this phase falsified. Stated as the measured split so
    // that a new overlap is a failure rather than a silently widened claim.
    assert(
      corpora.filterNot(_ == Transient).forall { corpus =>
        val sets =
          Vector(TransientStorage, MemoryCopy, SelfDestructScope).map(proposal => moved((proposal, corpus)).toSet)
        sets(0).intersect(sets(1)).isEmpty && sets(0).intersect(sets(2)).isEmpty && sets(1).intersect(sets(2)).isEmpty
      },
      "an overlap outside the directory named below would mean two documents reach one case somewhere this " +
        "spec claims they cannot"
    )

  it should "overlap in exactly the two cases that kept one directory out" in {
    val byTransientStorage = moved((TransientStorage, Transient)).toSet
    val bySelfDestructScope = moved((SelfDestructScope, Transient)).toSet
    val both = byTransientStorage.intersect(bySelfDestructScope)
    assert(
      both.size == 2 &&
        both.forall(name => name.contains("reentrant_selfdestructing_call") && name.contains("pre_existing_contract")),
      "the two cases this directory could not be certified for are a destruction of an account that predates " +
        "the transaction, reached from a re-entrant call that also writes transient storage -- measured: " +
        both.toVector.sorted.mkString(", ")
    )
  }

  it should "leave the memory-copying document out of that overlap" in
    // The control for the row above: a third document over the same corpus,
    // whose set is empty, so the intersection reported there is a property of
    // those two and not of any pair drawn from these rules.
    assert(
      moved((MemoryCopy, Transient)).isEmpty,
      "the copying operation is inert over this directory, so the overlap above is the two documents named " +
        "and not an artifact of measuring intersections at all"
    )
