package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}
import org.fukuii.chainspec.{Activation, UpgradeRules}
import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus, SkipReason, Verdict}

/** This build's rules for Mystique, against the expectations
  * `etclabscore/tests-etc` files under that label.
  *
  * ==What this upgrade adds that no earlier Ethereum Classic tier could reach==
  *
  * Its two proposals are a refund reduction and a creation-time code refusal,
  * and this is the first label on this network where either is in force. The
  * refusal is the one with no counterpart anywhere below: every rule set through
  * Magneto deploys whatever a creation returns, so a case whose subject is a
  * REJECTED deployment states nothing at any earlier label that a passing tier
  * could have read.
  *
  * `stCreate2/CREATE2_FirstByte_loop` is that case, and it is why
  * [[ClassicPublishedStateCorpus.MystiqueDirectories]] registers a directory
  * neither upgrade below does. It deploys a one-byte contract for each of the
  * 256 values a first byte can take and writes storage only where the
  * deployment FAILED -- so the reserved prefix is not incidental to the case,
  * it is the whole of what the case measures.
  *
  * ==Counts as literals, everything else as named sets==
  *
  * For the reason the sibling tiers state theirs: a count passes a mutation that
  * loses one entry and gains an unrelated one, which is what a directory
  * mis-registration or a decode regression preserving a total would look like.
  *
  * **This tier splits the divergences by OBSERVABLE where the tiers below state
  * one**, and that is a result rather than a stylistic difference. Both labels
  * below diverge only on state roots; here two entries diverge on what a refusal
  * is named while both sides agree it is refused. A single predicate admitting
  * both would be satisfied by either kind appearing anywhere, so the sets are
  * asserted to partition and each is held to its own claim.
  *
  * ==What this tier cannot certify, stated so it is not looked for==
  *
  * This tree publishes `hash`, `logs`, `txbytes` and `indexes` and **no
  * `receipt` and no full post `state`**. So a case's observable is a state root
  * and nothing finer: where a build and this tree disagree, the disagreement
  * names the root and never the account or the slot that produced it. For the
  * creation-refusal case above that is enough to discriminate the upgrade and
  * not enough to say WHICH first byte was refused -- `org.fukuii.evm.ReservedCodePrefixSpec`
  * is where the byte itself is asserted, and the two are complementary rather
  * than redundant.
  */
class ClassicPublishedMystiqueStateCertificationSpec extends AnyFlatSpec:

  /** Files the registered directories hold. */
  private val Files: Int = 565

  /** Outcomes across them: one per entry stated at this label, plus one per case
    * stating nothing here.
    */
  private val Outcomes: Int = 3489

  /** Entries this build answers as the tree states them.
    *
    * The three figures reconcile: this figure plus the divergences named below
    * plus the cases stating nothing at this label is every one of the
    * [[Outcomes]], so nothing here is unaccounted for.
    */
  private val Certified: Int = 3475

  /** Entries this build answers differently, named rather than counted.
    *
    * ==The set is eleven where the label below names five, and the difference is
    * what is READ rather than what is WRONG==
    *
    * That is the sentence to carry away, because the growth reads like a
    * regression and is not one. This tier registers two directories no tier
    * below it registered, so six entries are compared here that nothing had
    * compared before. **No entry answered correctly at an earlier label answers
    * differently now**, and neither of this upgrade's proposals moved any of
    * them.
    *
    * ==None of the eleven is caused by either proposal this upgrade adopts==
    *
    * They fall into three groups, and the grouping is measured from the tree
    * rather than inferred from the names:
    *
    *   - **Five inherited.** `InitCollision`'s four entries and
    *     `dynamicAccountOverwriteEmpty` diverge at Magneto and at Phoenix, by
    *     the same observable.
    *   - **Four newly READ rather than newly wrong.** `create2collisionStorage`'s
    *     three entries and `RevertInCreateInInitCreate2` are in `stCreate2`,
    *     which no tier registered below this label. **This tree states the same
    *     expected root for each of them at this label and at the one below**, so
    *     this upgrade changes nothing about them and the disagreement is one
    *     that was already there and had never been looked at.
    *   - **Two the tree states here for the first time.** `coinbaseT2`'s two
    *     entries are in `stEIP2930`, registered one upgrade below, where the
    *     case states no expectation at all -- it is named in that tier's own
    *     silent set. So this is the first label at which the entry could be read
    *     either.
    *
    * **They do not even share an OBSERVABLE, which is why the two sets below
    * are separate.** Nine are state-root disagreements. The two `coinbaseT2`
    * entries are not: there this build and the tree agree that the transaction
    * is refused and disagree about what the refusal is CALLED. Grouping all
    * eleven under one observable would have asserted something false about
    * those two, and asserting it is what surfaced them.
    */
  private val KnownDivergences: Vector[String] =
    Vector(
      "InitCollision[d0g0v0]",
      "InitCollision[d1g0v0]",
      "InitCollision[d2g0v0]",
      "InitCollision[d3g0v0]",
      "RevertInCreateInInitCreate2[d0g0v0]",
      "coinbaseT2[d0g0v0]",
      "coinbaseT2[d1g0v0]",
      "create2collisionStorage[d0g0v0]",
      "create2collisionStorage[d1g0v0]",
      "create2collisionStorage[d2g0v0]",
      "dynamicAccountOverwriteEmpty[d0g0v0]"
    )

  /** The divergences whose observable is a disagreement about the state root.
    *
    * Nine of the eleven. Named as their own set rather than left to a `forall`
    * over all of them, because the other two are a different kind of finding and
    * a single predicate wide enough to admit both would assert nothing about
    * either.
    */
  private val StateRootDivergences: Vector[String] =
    Vector(
      "InitCollision[d0g0v0]",
      "InitCollision[d1g0v0]",
      "InitCollision[d2g0v0]",
      "InitCollision[d3g0v0]",
      "RevertInCreateInInitCreate2[d0g0v0]",
      "create2collisionStorage[d0g0v0]",
      "create2collisionStorage[d1g0v0]",
      "create2collisionStorage[d2g0v0]",
      "dynamicAccountOverwriteEmpty[d0g0v0]"
    )

  /** What every one of those states. */
  private val StateRootReason: String = "state root "

  /** The divergences where both sides refuse the transaction and disagree about
    * the name of the refusal.
    *
    * **This is a weaker finding than a state-root disagreement and must not be
    * read as one.** The tree states `TR_TypeNotSupported` and this build reports
    * its own `TypeNotAdmitted`; both are refusals of the same transaction, so
    * nothing here says the two disagree about what the chain would do.
    *
    * ==Recording it as a divergence is the reader contract, not a shortfall==
    *
    * `fukuii-project/fukuii-tests` `FIXTURE-FORMAT.md` @ `7bc9cef` states what a
    * consumer of `expectException` owes. Split on `|`, trim, and keep the set
    * *"verbatim and unmapped"*; intersect it with the refusals this build can
    * produce; then *"An empty intersection is a divergence that names the
    * unmatched rule -- never a skip, and never a pass. The point is to stop a
    * case passing because a refusal for some other reason happened to leave the
    * state root where the fixture expected it."*
    *
    * [[StateFixtureRunner]] implements that already -- its vocabulary carries
    * the modern `TransactionException.*` spellings and no `TR_` name at all, so
    * this one survives unmapped and reaches the comparison as itself.
    * **Translating it onto this build's refusal is precisely the move the
    * contract forbids**, which is why these two entries are recorded rather than
    * reconciled.
    *
    * ==The name is this tree's own convention, and that is the whole claim==
    *
    * `TR_TypeNotSupported` appears 37 times across this tree's
    * `GeneralStateTests`, so it is how this corpus spells the rule rather than
    * one fixture's slip. **Nothing here says the tree is wrong.** What is said
    * is narrower and is all this build can know: no vocabulary it holds maps
    * that name, so the contract above requires the divergence.
    *
    * Kept as its own set so that a real state-root disagreement appearing on one
    * of these entries would fail the case below rather than blend into a
    * predicate that tolerates both.
    */
  private val ExceptionNameDivergences: Vector[String] =
    Vector("coinbaseT2[d0g0v0]", "coinbaseT2[d1g0v0]")

  /** What every one of those states. */
  private val ExceptionNameReason: String = "refused as "

  /** Cases that state no expectation at this label, named rather than counted.
    *
    * **The set is not the one the label below carries**, which is the reason it
    * is named here rather than inherited: four of the cases silent at Magneto do
    * state an entry at this label, and two of the directories this label adds
    * bring cases that state nothing. A tier copying its neighbour's list would
    * fail for a correct corpus.
    */
  private val StatingNothingHere: Vector[String] =
    Vector(
      "CallEcrecover_Overflow",
      "RevertDepthCreate2OOG",
      "RevertDepthCreateAddressCollision"
    )

  /** Entries the fork below's rules still answer as this label states them.
    *
    * A literal rather than an inequality, so the control states a margin. See
    * the case that reads it.
    */
  private val AgreeingUnderTheForkBelow: Int = 2822

  /** A label this network's schedule carries and this tree files nothing under.
    *
    * The reader's own negative control, against the run itself as the positive
    * one: the reader dispatches on the post key, so a label the tree does not
    * use must report every file as stating no expectation rather than silently
    * matching something.
    */
  private val UnfilledLabel: String = "ETC_Thanos"

  private val report: CorpusReport =
    ClassicPublishedStateCorpus.mystique.getOrElse(
      fail(
        "the published corpus was not found: set " + FixtureCorpus.RootVariable + " or write " +
          FixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  /** What the run states about each of `names`, in the run's own order.
    *
    * Selected by name rather than by taking every divergence, so that a case
    * reading one observable cannot be satisfied by an entry belonging to the
    * other.
    */
  private def reasonsFor(names: Vector[String]): Vector[String] =
    report.diverged
      .filter(outcome => names.contains(outcome.name))
      .map(_.verdict)
      .collect { case Verdict.Diverged(why) => why }
      .flatten

  /** The same directories and label, resolved at `height` with `change` applied
    * to whatever the schedule answered there.
    */
  private def under(height: Long, change: UpgradeRules => UpgradeRules): CorpusReport =
    ClassicPublishedStateCorpus
      .reportAt(
        "control",
        ClassicPublishedStateCorpus.MystiqueFork,
        height,
        ClassicPublishedStateCorpus.MystiqueDirectories,
        change
      )
      .getOrElse(fail("the published corpus was not found for a control run"))

  "the published Ethereum Classic tier at Mystique" should "read the files the registered directories hold" in
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

  it should "diverge on exactly the entries the forks below diverge on, named rather than counted" in
    assert(
      report.diverged.map(_.name).sorted == KnownDivergences.sorted,
      "diverged at " + report.diverged.map(_.name).sorted.mkString(", ")
    )

  it should "partition those divergences into the two observables, with neither set empty" in
    // The arm that stops one predicate standing in for two findings. It guards
    // COVERAGE and non-vacuousness only: an entry appearing in neither set, or a
    // set going empty, fails here.
    //
    // It does NOT catch an entry moving BETWEEN the two sets. The union is
    // unchanged by that, so this assertion is symmetric in which vector holds
    // which name and would pass with the two swapped wholesale. The two tests
    // below are what catch it, by reading each entry's reason off the live
    // report -- so a state-root disagreement newly appearing on an
    // exception-name entry fails there, not here.
    assert(
      (StateRootDivergences ++ ExceptionNameDivergences).sorted == KnownDivergences.sorted &&
        StateRootDivergences.nonEmpty && ExceptionNameDivergences.nonEmpty,
      "the two observables do not partition the divergences named above"
    )

  it should "state every state-root divergence as a disagreement about the root" in {
    val reasons = reasonsFor(StateRootDivergences)
    assert(
      reasons.length == StateRootDivergences.length && reasons.forall(_.startsWith(StateRootReason)),
      "a refused creation leaves the sender charged and the target untouched, so any other kind of " +
        "disagreement on these entries would be a different finding wearing the same names: " + reasons.mkString("; ")
    )
  }

  it should "state every exception-name divergence as a refusal both sides make" in {
    // The weaker set, asserted to its own weaker claim. Both names are required
    // to appear: a reason naming only one of them would mean the shape of this
    // disagreement had changed, and the note on the set above would stop being
    // true while the case went on passing.
    val reasons = reasonsFor(ExceptionNameDivergences)
    assert(
      reasons.length == ExceptionNameDivergences.length &&
        reasons.forall(why =>
          why.startsWith(ExceptionNameReason) && why.contains("TypeNotAdmitted") &&
            why.contains("TR_TypeNotSupported")
        ),
      "these entries no longer disagree only about what the refusal is called: " + reasons.mkString("; ")
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

  "the fork below" should "satisfy fewer of this label's expectations" in {
    // THE CONTROL THE WHOLE TIER DEPENDS ON. A tier whose expectations the
    // upgrade below already satisfies is not evidence about the upgrade it is
    // named for -- it is a corpus that could not have disagreed.
    //
    // The figure is pinned rather than compared, because a bare inequality is
    // satisfied by a margin of one and would go on passing while the real margin
    // narrowed. The margin here is several hundred entries wide, which is more
    // than a two-proposal upgrade might be expected to move: a refund ceiling
    // reaches every entry that refunds anything, so the reach is broad even
    // though the document is narrow.
    //
    // It is still not what certifies either proposal individually. A margin this
    // size is consistent with one of the two doing all the work, which is what
    // the two mutations below rule out by withdrawing each in turn.
    val below = under(ClassicPublishedStateCorpus.MagnetoStarts, identity)
    assert(
      below.agreed.length == AgreeingUnderTheForkBelow,
      "the rules below Mystique answer " + below.agreed.length.toString + " of this label's expectations " +
        "rather than " + AgreeingUnderTheForkBelow.toString + ", against " + report.agreed.length.toString +
        " under Mystique's own"
    )
  }

  "the reader" should "answer nothing at a label this tree files no expectation under" in {
    // The negative control for the reader itself, against the run above as the
    // positive one. It must read the same files and state nothing about them.
    val unfilled = ClassicPublishedStateCorpus
      .reportAt(
        "control",
        UnfilledLabel,
        ClassicPublishedStateCorpus.MystiqueStarts,
        ClassicPublishedStateCorpus.MystiqueDirectories,
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
    // Slide MystiqueStarts to any value at or above the real one and every other
    // assertion in this file still passes, because the schedule answers
    // Upgrades.mystique for all of them -- so the tier would certify a
    // neighbouring fork's rules under this one's name and report clean.
    val resolved = KnownNetworks.registry.toOption
      .flatMap(_.at(ethereumclassic.Mainnet.network.chainId))
      .getOrElse(fail("this network is not in the registry"))
    assert(
      resolved.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(ClassicPublishedStateCorpus.MystiqueStarts))),
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
      resolved.at(UInt64.fromBits(ClassicPublishedStateCorpus.MystiqueStarts), UInt64.Zero) eq
        ethereumclassic.Upgrades.mystique,
      "the schedule answers rules other than Mystique's at the height this tier is resolved at"
    )
  }

  "a build refusing no code prefix" should "be refused by this label's creation entries" in {
    // THE ARM THAT MAKES EIP-3541 NON-VACUOUS HERE. Withdrawing the reserved
    // prefix leaves the refund reduction and everything below it in place, so
    // anything that moves moved because a deployment that this upgrade refuses
    // was allowed to succeed.
    //
    // Without this arm the tier could register a directory whose cases never
    // reach the rule and report the same certified figure either way, which is
    // the corpus-that-could-not-have-disagreed shape.
    val refusingNothing = under(
      ClassicPublishedStateCorpus.MystiqueStarts,
      rules => rules.copy(evm = rules.evm.copy(reservedCodePrefix = None))
    )
    assert(
      refusingNothing.agreed.length < report.agreed.length,
      "withdrawing the reserved code prefix changed no outcome, so this tier certifies nothing about EIP-3541: " +
        refusingNothing.agreed.length.toString + " against " + report.agreed.length.toString
    )
  }

  it should "not be what this network runs there" in
    // The positive half of the pair above: the mutation is what moves the
    // figure, not the tier being unreachable.
    assert(
      ethereumclassic.Upgrades.mystique.evm.reservedCodePrefix.isDefined,
      "the composition under test refuses no code prefix, so the mutation withdraws nothing"
    )

  "a build paying the refunds of the fork below" should "be refused by this label's refund entries" in {
    // The same arm for this upgrade's OTHER proposal, so neither is certified by
    // the tier's size alone. The two refund figures and the ceiling are put back
    // to the fork below's, which is exactly EIP-3529's delta and nothing else --
    // no proposal between these two labels touches another schedule field.
    //
    // Read the pair together: this upgrade adopts two proposals, and a tier that
    // moves under one mutation and not the other would be certifying one of them
    // while merely carrying the other.
    val payingOldRefunds = under(
      ClassicPublishedStateCorpus.MystiqueStarts,
      rules =>
        rules.copy(
          evm = rules.evm.copy(schedule = ethereumclassic.Upgrades.magneto.evm.schedule),
          execution = rules.execution.copy(
            maxRefundQuotient = ethereumclassic.Upgrades.magneto.execution.maxRefundQuotient
          )
        )
    )
    assert(
      payingOldRefunds.agreed.length < report.agreed.length,
      "restoring the fork below's refund rules changed no outcome, so this tier certifies nothing about " +
        "EIP-3529: " + payingOldRefunds.agreed.length.toString + " against " + report.agreed.length.toString
    )
  }

  it should "not be what this network runs there" in
    // The positive half of the pair above, stated over all three fields the
    // mutation restores, so that a mutation putting back a value this upgrade
    // never changed would be visible as such.
    assert(
      ethereumclassic.Upgrades.mystique.evm.schedule.refundNetStorageClear !=
        ethereumclassic.Upgrades.magneto.evm.schedule.refundNetStorageClear &&
        ethereumclassic.Upgrades.mystique.evm.schedule.refundSelfDestruct !=
        ethereumclassic.Upgrades.magneto.evm.schedule.refundSelfDestruct &&
        ethereumclassic.Upgrades.mystique.execution.maxRefundQuotient !=
        ethereumclassic.Upgrades.magneto.execution.maxRefundQuotient,
      "the composition under test states the fork below's refund rules, so the mutation restores nothing"
    )
