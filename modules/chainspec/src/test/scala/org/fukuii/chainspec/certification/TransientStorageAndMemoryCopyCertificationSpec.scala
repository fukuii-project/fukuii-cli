package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.proposals.eip.{Eip1153, Eip5656}
import org.fukuii.chainspec.{Component, ProposalId, UpgradeRules}
import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus}

/** EIP-1153 and EIP-5656 against three published state directories.
  *
  * ==The rules these are read under are a COMPOSITION, and that is the whole of
  * what makes this spec unusual==
  *
  * Every other state tier here is resolved through a network's schedule at a
  * height, so it certifies an activation as well as a machine. These three
  * cannot be: the fork their files are filled for is being built one proposal
  * at a time, and there is no activation for a rule set holding two of its six.
  * So they name `Upgrades.shanghai` with these two components adopted, and the
  * first registration below is what makes that mean anything -- without it,
  * three corpora would be certifying a composition nothing had checked was the
  * one their names describe.
  *
  * **This is not the same case as `LegacyConstantinopleStateCorpus`.** That one
  * names a composition because its rule set is unreachable by construction and
  * always will be. This one names a composition because the fork is unfinished,
  * and it stops being the right shape the moment that fork exists.
  *
  * ==The three DECIDE two proposals, and the two sets do not intersect==
  *
  * Every count below is a differential -- how many cases answer differently once
  * a proposal is withdrawn -- rather than a size. A directory named for a
  * proposal is routinely taken to certify it, and the figures show why that is
  * not free: two of the three directories are decided by one of the two
  * proposals and by nothing the other does.
  *
  * | directory | cases | without EIP-1153 | without EIP-5656 |
  * |---|---|---|---|
  * | `cancun/eip5656_mcopy` | 99 | 0 | 61 |
  * | `ported_static/stEIP5656_MCOPY` | 106 | 0 | 90 |
  * | `ported_static/stEIP1153_transientStorage` | 48 | 48 | 0 |
  *
  * **The three zeroes are the load-bearing half.** Reading a directory filled
  * for a whole fork under a rule set carrying two of its proposals is sound only
  * where the directory cannot tell the difference, and a zero is what says the
  * proposal it is measured against is inert over those files. Each zero sits
  * beside a non-zero from the same machinery over the same corpus, so none of
  * them is a rerun that silently failed to apply its change.
  *
  * **Neither of the two non-zero figures is the whole directory, and that gap
  * is the point of measuring rather than counting.** 61 of 99 and 90 of 106
  * move when the copying operation leaves the table, while all 99 and all 106
  * CARRY the operation in a pre-state account -- measured by disassembling each
  * account's code and skipping `PUSH` data rather than matching bytes, with a
  * control opcode the same instrument reads as absent. **Carrying is not
  * executing and this figure does not establish that it is**: what it bounds is
  * that the shortfall is not a directory holding cases about something else.
  * Whatever the reason in each, 38 and 16 cases answer the same whether the byte
  * is defined or not, and only the differential says which those are. The
  * transient-storage directory has no such gap: all 48 move.
  *
  * ==What this tier CANNOT see, stated rather than left to be assumed==
  *
  *   - **The other four proposals of the fork.** None of them is in the
  *     composition, so no case here is evidence about EIP-4844, EIP-6780,
  *     EIP-7516 or EIP-4788. A pass here says nothing about any of them.
  *   - **`currentExcessBlobGas`.** All 253 cases state it in their `env` and the
  *     reader models no such field. Nothing here reads it: every transaction in
  *     all three directories is a format predating blob carriage, and neither
  *     operation that would consult it is in the table. So the agreement below
  *     is not evidence that the field can go on being ignored -- it is evidence
  *     that these files never ask.
  *   - **The gas figure a copy costs, as a figure.** A `post` entry states a
  *     root and no gas, so a wrong charge is visible here only through what the
  *     sender keeps and the producer is credited. That is a real signal and it
  *     is not the same as reading the number, which
  *     `org.fukuii.evm.MemoryCopySpec` does against the document's own worked
  *     examples.
  *
  * ==What it CAN see that the unit specs cannot, which is why it is worth the
  * run==
  *
  * Both directions of an overlapping copy, and the rollback of a transient write
  * across a frame that failed -- reached here through published material rather
  * than through cases this build wrote for itself.
  * `ported_static/stEIP5656_MCOPY` names four cases `backward_overlapped_0`,
  * `backward_overlapped_1`, `forward_overlapped_0` and `forward_overlapped_1`;
  * `ported_static/stEIP1153_transientStorage` names 32 cases for a revert,
  * among them `10_revert_undoes_store_after_return` and
  * `14_revert_after_nested_staticcall`. **Those are the corpus's own words and
  * are read as vocabulary rather than as a measurement** -- what a case is
  * called is a claim by whoever filled it, and the differential above is what
  * establishes that the directories decide anything at all.
  *
  * ==One directory this phase's proposals are named for is deliberately absent==
  *
  * `state_tests/for_cancun/cancun/eip1153_tstore`, 19 files and 123 cases, is
  * the directory a reader looks for and it is not certified here. Measured
  * 2026-09-10 under exactly the composition above: **121 agree and 2 diverge**,
  * both in `tstorage_selfdestruct/reentrant_selfdestructing_call.json` and both
  * named `..._pre_existing_contract`. Their only divergence is an account that
  * this build removed and the fixture expects to survive, reported as its nonce
  * and its code -- which is EIP-6780's rule and not a transient-storage
  * question at all.
  *
  * **It is left out rather than admitted with an allowance**, because the
  * census property asserting that every corpus agrees with every case it ran is
  * the thing that must not be weakened: a per-corpus allowance for known
  * divergences absorbs a real divergence exactly as readily as an expected one.
  * The directory belongs to whichever phase builds EIP-6780, and this paragraph
  * is what tells that phase which two cases it is closing.
  */
class TransientStorageAndMemoryCopyCertificationSpec extends AnyFlatSpec:

  /** The two components these corpora are read under, in the order the
    * composition takes them.
    */
  private val TheTwo: Vector[Component] = Vector(Eip1153.component, Eip5656.component)

  /** The rules the three corpora name, rebuilt here.
    *
    * Adoption has no inverse -- a component is an arbitrary function over the
    * whole rule set -- so a proposal is withdrawn by rebuilding without it
    * rather than by undoing it. The first registration is what establishes that
    * this rebuild reproduces what the harness resolved the corpora at.
    */
  private val composed: UpgradeRules = ethereum.Upgrades.shanghai.adopting(TheTwo*)

  private def without(dropped: ProposalId): UpgradeRules => UpgradeRules =
    _ => ethereum.Upgrades.shanghai.adopting(TheTwo.filterNot(_.id == dropped)*)

  private val withoutTransientStorage: UpgradeRules => UpgradeRules = without(ProposalId.Eip(1153))

  private val withoutMemoryCopy: UpgradeRules => UpgradeRules = without(ProposalId.Eip(5656))

  private val MemoryCopy: String = CertificationCorpora.GeneratedCancunMemoryCopyCorpus

  private val PortedMemoryCopy: String = CertificationCorpora.PortedStaticCancunMemoryCopyCorpus

  private val PortedTransientStorage: String = CertificationCorpora.PortedStaticCancunTransientStorageCorpus

  /** Files, cases and cases certified, stated as literals so a corpus that
    * shrank is a failure rather than a smaller pass.
    */
  private val Census: Map[String, (Int, Int)] =
    Map(MemoryCopy -> (7, 99), PortedMemoryCopy -> (3, 106), PortedTransientStorage -> (4, 48))

  private val WithoutTransientStorageTheseMove: Map[String, Int] =
    Map(MemoryCopy -> 0, PortedMemoryCopy -> 0, PortedTransientStorage -> 48)

  private val WithoutMemoryCopyTheseMove: Map[String, Int] =
    Map(MemoryCopy -> 61, PortedMemoryCopy -> 90, PortedTransientStorage -> 0)

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
  private def moved(corpus: String, change: UpgradeRules => UpgradeRules): Vector[String] =
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

  "the two recomposed from the fork below" should "be the rules these corpora are resolved at" in
    assert(
      Census.keySet.forall(corpus => moved(corpus, _ => composed).isEmpty),
      "every differential below withdraws a proposal by rebuilding from the fork below, so a recomposition " +
        "that did not reproduce the rules the harness resolved these corpora at would make all six " +
        "measurements about some third rule set"
    )

  it should "record both adoptions, in order" in
    assert(
      composed.components.takeRight(2) == Vector(ProposalId.Eip(1153), ProposalId.Eip(5656)),
      "the journal states which documents produced these rules, and a differential names one of them"
    )

  "the copying directory" should "be read in full and agree with every case" in
    assert(
      report(MemoryCopy).filesRead == Census(MemoryCopy)._1 &&
        report(MemoryCopy).casesFound == Census(MemoryCopy)._2 &&
        report(MemoryCopy).agreed.length == Census(MemoryCopy)._2,
      report(MemoryCopy).describe
    )

  "the ported copying directory" should "be read in full and agree with every case" in
    assert(
      report(PortedMemoryCopy).filesRead == Census(PortedMemoryCopy)._1 &&
        report(PortedMemoryCopy).casesFound == Census(PortedMemoryCopy)._2 &&
        report(PortedMemoryCopy).agreed.length == Census(PortedMemoryCopy)._2,
      report(PortedMemoryCopy).describe
    )

  "the ported transient-storage directory" should "be read in full and agree with every case" in
    assert(
      report(PortedTransientStorage).filesRead == Census(PortedTransientStorage)._1 &&
        report(PortedTransientStorage).casesFound == Census(PortedTransientStorage)._2 &&
        report(PortedTransientStorage).agreed.length == Census(PortedTransientStorage)._2,
      report(PortedTransientStorage).describe
    )

  "withdrawing the copying operation" should "move the stated cases in each directory" in
    assert(
      Census.keySet.forall(corpus => moved(corpus, withoutMemoryCopy).length == WithoutMemoryCopyTheseMove(corpus)),
      "measured: " +
        Census.keySet.toVector.sorted
          .map(corpus => corpus + " -> " + moved(corpus, withoutMemoryCopy).length.toString)
          .mkString("; ")
    )

  "withdrawing transient storage" should "move the stated cases in each directory" in
    assert(
      Census.keySet.forall(corpus =>
        moved(corpus, withoutTransientStorage).length == WithoutTransientStorageTheseMove(corpus)
      ),
      "measured: " +
        Census.keySet.toVector.sorted
          .map(corpus => corpus + " -> " + moved(corpus, withoutTransientStorage).length.toString)
          .mkString("; ")
    )

  "the two proposals" should "decide disjoint sets of cases in every directory" in
    // Neither proposal's operations are reachable from the other's, so nothing
    // should be decided by both. Asserted rather than read off the zeroes above,
    // because a directory could be moved by each of them over a shared case and
    // still report the same two totals.
    assert(
      Census.keySet.forall { corpus =>
        moved(corpus, withoutMemoryCopy).toSet.intersect(moved(corpus, withoutTransientStorage).toSet).isEmpty
      },
      "a case decided by both would make the two figures above overlap rather than partition"
    )

  it should "together decide exactly what each decides alone" in
    // The build that adopted neither. Its set must be the union of the two, and
    // since those are disjoint, its size must be their sum -- which is what
    // rules out a case that moves only when both are withdrawn.
    assert(
      Census.keySet.forall { corpus =>
        moved(corpus, _ => ethereum.Upgrades.shanghai).length ==
          WithoutMemoryCopyTheseMove(corpus) + WithoutTransientStorageTheseMove(corpus)
      },
      "measured: " +
        Census.keySet.toVector.sorted
          .map(corpus => corpus + " -> " + moved(corpus, _ => ethereum.Upgrades.shanghai).length.toString)
          .mkString("; ")
    )

  "the directory named for transient storage under the fork's own key" should "not be censused here" in
    // The absence is deliberate and is asserted so that admitting it becomes a
    // visible act rather than a quiet one. Two of its 123 cases are decided by
    // EIP-6780, which these rules do not carry; the header states the
    // measurement and which phase closes it.
    assert(
      !reports.exists(_.corpus.contains("eip1153_tstore")),
      "this directory cannot be answered in full by these rules, so admitting it would need either a failing " +
        "tier or a per-corpus allowance for known divergences"
    )
