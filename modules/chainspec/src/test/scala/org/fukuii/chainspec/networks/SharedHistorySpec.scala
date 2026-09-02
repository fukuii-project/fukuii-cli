package org.fukuii.chainspec.networks

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, ProposalId, Upgrade, UpgradeRules, UpgradeSchedule}
import org.scalatest.flatspec.AnyFlatSpec

/** Ethereum mainnet and Ethereum Classic mainnet run the same rules through
  * Homestead, and the DAO fork is where that stops.
  *
  * ==Why an assertion and not a shared file==
  *
  * The two networks' agreement is a fact about those two networks, and a
  * directory of shared definitions cannot say *shared by whom*: the moment a
  * second pair of networks shares something it becomes a collection with no
  * membership information in it. An assertion names both parties and the
  * boundary, and it degrades correctly -- where they diverge it is deleted with
  * a reason, rather than a file quietly moving.
  *
  * ==What makes this refutable==
  *
  * [[ethereumclassic.Upgrades]] composes its own rule sets from the proposal
  * vocabulary and names nothing in [[ethereum.Upgrades]]. Two configurations
  * built from one value are equal however either was authored, so an assertion
  * over shared values would report the sharing and never the agreement. The
  * first test below is what holds that open, and it is the one to read before
  * trusting any of the others.
  *
  * ==The boundary is a divergence of STATE, not of the rules modeled here==
  *
  * This is the half that reads backwards and is the reason the range below
  * stops where it does rather than where the rules part. EIP-779 states it
  * directly: *"Unlike other hard forks, the DAO Fork did not change the
  * protocol; all EVM opcodes, transaction format, block structure, and so on
  * remained the same. Rather, the DAO Fork was an 'irregular state change'"*.
  *
  * **That sentence is a summary and the same proposal contradicts it**, so the
  * assertions below are worded against the facets rather than against it: §
  * *Specification* requires every block in `[1_920_000, 1_920_009]` to carry
  * `dao-hard-fork` in `extraData`, which is a header rule and which
  * `ethereum/go-ethereum` @ `6bb0588ad8e7f922e4ad5580f51265a4097af08f` enforces
  * in `consensus/misc/dao.go`. `UpgradeRules` holds no header facet -- the
  * facets it does hold settle the machine, settlement, admission and what a
  * block owes its consensus mechanism -- so what is equal below is every rule
  * this build models, not every rule a node validates.
  *
  * So at block 1,920,000 the two networks began building different state under
  * rules that stayed equal for another 543,000 blocks, until Ethereum mainnet
  * repriced first. A test asserting that those rules diverge at the DAO fork
  * would be asserting something false, and one asserting they agree wherever
  * they agree would run past the point the two chains stopped being one chain.
  * The range below is the second bound: the last height at which both networks
  * were the same chain.
  *
  * ==The parting is an asymmetry between two schedules, and it is asserted from
  * both sides==
  *
  * Ethereum mainnet carries the upgrade as an entry that changes no rule and
  * still reaches its fork identifier. Ethereum Classic carries nothing, and
  * should not: it declined the upgrade, and an entry that never activates
  * resolves nothing, orders nothing and reaches nothing, so it would be
  * documentation filed in a configuration.
  *
  * A test over the rules alone cannot see that difference -- both networks
  * resolve to the same rule set on both sides of the block. The fork
  * identifier is where it is visible, and the assertion over it is the only
  * thing in this build that fails if either network's entry moves to the other.
  *
  * ==The mirror: the machines realign, and the two networks do not become
  * one==
  *
  * Each network later reaches a rule set whose machine, settlement and
  * admission rules are the other's again, at a different height and by a
  * different list of proposals. That is a claim about the four facets a rule
  * set holds, and it is asserted over three of them, because the fourth is
  * where the two networks stay apart.
  *
  * **It is a claim about the two rule sets it names and not a standing claim
  * about the networks**, which is why each side is resolved at a stated height
  * rather than at whatever either schedule most recently reached. The EIP-150
  * case below records the failure the other reading produces.
  *
  * Three things the agreement excludes, and only two of them are expressible
  * here:
  *
  *   - **The irregular state change.** The network that applied it built
  *     different state from 1,920,000 under rules that stayed equal, and
  *     nothing a rule set holds records that. No assertion below can see it;
  *     the fork-identifier case above is the one projection in this build that
  *     can, and it is where that divergence stays visible.
  *   - **The consensus proposals one network adopted alone.** ECIP-1010,
  *     ECIP-1017, ECIP-1039 and ECIP-1041 have no counterpart on the other
  *     network, and two of them settle no rule-set value at all -- the emission
  *     they step is computed by the engine, so the component list is the only
  *     place they appear.
  *   - **EIP-649.** The one proposal of the other network's upgrade that
  *     Ethereum Classic declined, and the reason its consensus facet still pays
  *     the launch amount where the other network's does not.
  *
  * **That list is the FIRST realignment's, and it grows at each later one.** The
  * other network goes on to adopt EIP-1234 and EIP-1283 at its next upgrade and
  * EIP-1716 at the one after, and this network takes none of the three. So the
  * cases below read the difference at each height rather than restating this
  * list, and the third of them asserts that the difference does not grow AT
  * THAT UPGRADE -- both networks adopt the same six proposals there, so the six
  * cancel and what is left is what was already there.
  *
  * **That is a claim about one upgrade and not about the journey, and the
  * fourth realignment is where the distinction shows.** There both networks
  * again adopt the same set, which again cancels -- and each has also adopted
  * one proposal alone since, so the difference grows by one in each direction.
  * A reading of the sentence above as "the difference has stopped growing"
  * would be refuted by the case that states it.
  *
  * `ethereumclassic/core-geth` @
  * `4185df450364973bbf99efa3923791f5ba40b351` carries it as
  * `// DAOForkBlock: big.NewInt(1920000),` in `params/config_classic.go`,
  * commented out; `besu-eth/besu-etc` @ `eb4248c997cb79cc88db55ead562081a43721a3b`
  * carries it as `"classicForkBlock": 1920000` in
  * `config/src/main/resources/classic.json`; and EIP-779 § *Specification*
  * (`ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38`) states the
  * same block from the other network's side.
  */
class SharedHistorySpec extends AnyFlatSpec:

  private val ethereumSchedule: UpgradeSchedule =
    ethereum.Mainnet.schedule.getOrElse(fail("Ethereum mainnet's authored entries do not form a schedule"))

  private val classicSchedule: UpgradeSchedule =
    ethereumclassic.Mainnet.schedule.getOrElse(fail("Ethereum Classic's authored entries do not form a schedule"))

  /** The last block at which the two networks were one chain. */
  private val lastSharedBlock: UInt64 = UInt64.fromBits(1919999L)

  /** The first block at which they were not, sourced on the entry that carries
    * it in [[ethereum.Mainnet]].
    */
  private val partingBlock: Activation = Activation.AtBlock(UInt64.fromBits(1920000L))

  /** The height at which Ethereum mainnet reached the machine both networks
    * then ran, sourced on the entry that carries it in [[ethereum.Mainnet]].
    */
  private val ethereumRealignmentBlock: Long = 4370000L

  /** The same for Ethereum Classic, 4,402,000 blocks later, sourced on the
    * entry that carries it in [[ethereumclassic.Mainnet]].
    */
  private val classicRealignmentBlock: Long = 8772000L

  /** The next height at which the two machines realign, on each schedule.
    *
    * The other network reaches these rules by two entries at one block and this
    * one by a single entry, which is why the pair is stated as two heights
    * rather than derived from either schedule.
    */
  private val ethereumSecondRealignmentBlock: Long = 7280000L

  private val classicSecondRealignmentBlock: Long = 9573000L

  /** The next height after that, on each schedule, sourced on the entry that
    * carries it in each network's own `Mainnet`.
    *
    * The two are stated as heights rather than derived because neither
    * schedule's position tells you the other's: Ethereum Classic reaches these
    * rules 927,839 blocks above its previous entry and Ethereum mainnet
    * 1,789,000 blocks above its previous pair.
    */
  private val ethereumThirdRealignmentBlock: Long = 9069000L

  private val classicThirdRealignmentBlock: Long = 10500839L

  /** The next height after that, on each schedule.
    *
    * Stated as heights for the reason the pair above is: Ethereum Classic
    * reaches these rules 2,688,294 blocks above its previous entry and 1,489,133
    * above the one between them, and Ethereum mainnet 3,175,000 above its
    * previous pair -- neither schedule's position tells you the other's.
    */
  private val ethereumFourthRealignmentBlock: Long = 12244000L

  private val classicFourthRealignmentBlock: Long = 13189133L

  private def ethereumAt(height: Long) = ethereumSchedule.at(UInt64.fromBits(height), UInt64.Zero)
  private def classicAt(height: Long) = classicSchedule.at(UInt64.fromBits(height), UInt64.Zero)

  /** Where on `schedule` the entry carrying exactly these rules activates.
    *
    * Keyed on the rules rather than on a label or a position, so it goes on
    * naming the same upgrade however either network's schedule grows past it.
    */
  private def activationCarrying(schedule: UpgradeSchedule, rules: UpgradeRules): Activation =
    schedule.entries
      .collectFirst {
        case UpgradeSchedule.Entry(activation, _, Upgrade.RuleChange(carried)) if carried == rules =>
          activation
      }
      .getOrElse(fail("no entry on " + schedule.network.name + " carries the rules under test"))

  "the two networks' rule sets" should "be separately built values rather than one value twice" in
    // Everything below compares by value, and value comparison cannot tell a
    // genuine agreement from a shared reference. This is the only test here
    // that can, and without it the rest are satisfied by construction.
    //
    // The facets are checked as well as the rule sets, because the cases that
    // compare the two machines compare a facet at a time: a rule set built by
    // copying the other network's would be a distinct value holding the very
    // fields those cases read, and the reference check on the enclosing value
    // would pass while every agreement below reported the sharing.
    assert(
      (ethereumclassic.Upgrades.frontier ne ethereum.Upgrades.frontier) &&
        (ethereumclassic.Upgrades.homestead ne ethereum.Upgrades.homestead) &&
        (ethereumclassic.Upgrades.gasReprice ne ethereum.Upgrades.tangerineWhistle) &&
        (ethereumclassic.Upgrades.atlantis.evm ne ethereum.Upgrades.byzantium.evm) &&
        (ethereumclassic.Upgrades.atlantis.execution ne ethereum.Upgrades.byzantium.execution) &&
        (ethereumclassic.Upgrades.atlantis.admission ne ethereum.Upgrades.byzantium.admission) &&
        (ethereumclassic.Upgrades.agharta.evm ne ethereum.Upgrades.petersburg.evm) &&
        (ethereumclassic.Upgrades.agharta.execution ne ethereum.Upgrades.petersburg.execution) &&
        (ethereumclassic.Upgrades.agharta.admission ne ethereum.Upgrades.petersburg.admission) &&
        (ethereumclassic.Upgrades.phoenix.evm ne ethereum.Upgrades.istanbul.evm) &&
        (ethereumclassic.Upgrades.phoenix.execution ne ethereum.Upgrades.istanbul.execution) &&
        (ethereumclassic.Upgrades.phoenix.admission ne ethereum.Upgrades.istanbul.admission) &&
        (ethereumclassic.Upgrades.magneto.evm ne ethereum.Upgrades.berlin.evm) &&
        (ethereumclassic.Upgrades.magneto.execution ne ethereum.Upgrades.berlin.execution) &&
        (ethereumclassic.Upgrades.magneto.admission ne ethereum.Upgrades.berlin.admission),
      "one network's configuration is the other's, so every agreement asserted here is a tautology"
    )

  "the two networks" should "resolve to the same rules at every height they were one chain" in
    // Spot heights rather than a sweep: the range holds 1.92 million blocks and
    // three of the four boundaries in it are covered by the table beside this.
    // What this adds is the top of the range, which no per-network table reaches.
    assert(
      classicAt(0L) == ethereumAt(0L) &&
        classicAt(1150000L) == ethereumAt(1150000L) &&
        classicSchedule.at(lastSharedBlock, UInt64.Zero) == ethereumSchedule.at(lastSharedBlock, UInt64.Zero),
      "two networks that were one chain at this height disagree about what it ran"
    )

  it should "still agree at the block their chains parted, because that fork changed no modeled rule" in
    // The fact that keeps the boundary honest. Read the other way -- rules
    // diverging here -- the assertion above would be given a false upper bound
    // and would stop testing the range it exists for.
    assert(
      classicAt(1920000L) == ethereumAt(1920000L),
      "the DAO fork altered no machine, settlement or admission rule, so neither network's rule set moves at it"
    )

  "only one of the two schedules" should "carry the upgrade they parted over" in
    // Where the parting is actually visible. Both networks resolve to the same
    // rules on both sides of this block, so nothing above can distinguish them
    // here; the fork identifier is the one projection that can.
    assert(
      ethereumSchedule.forkPoints.contains(partingBlock) &&
        !classicSchedule.forkPoints.contains(partingBlock),
      "the network that took the upgrade and the network that declined it agree about their fork identifiers"
    )

  it should "disagree once either has adopted a proposal the other has not" in
    // What stops every assertion above from passing over two identical
    // schedules. Ethereum mainnet reprices at 2,463,000 and this network does
    // not reach it for another 37,000 blocks.
    assert(
      classicAt(2463000L) != ethereumAt(2463000L),
      "the two networks agree at a height where only one of them has repriced"
    )

  "the rules each network reached by adopting EIP-150" should "be equal while activating 37,000 blocks apart" in
    // The field's own division, asserted: a proposal is shared and a schedule is
    // not. besu-etc has to express this by re-parenting its inheritance graph at
    // the divergence point; composing from components needs no parent at all.
    //
    // The two activations are found by the rules they carry rather than taken
    // as either schedule's LAST fork point, which is what this once compared.
    // That reading answered the question only while EIP-150 was the latest
    // proposal either network had adopted: the first fork landing after it on
    // one side moves `last` off the entry under test, and the assertion goes on
    // passing while measuring two upgrades that have nothing to do with the one
    // it names.
    assert(
      ethereumclassic.Upgrades.gasReprice == ethereum.Upgrades.tangerineWhistle &&
        activationCarrying(classicSchedule, ethereumclassic.Upgrades.gasReprice) !=
        activationCarrying(ethereumSchedule, ethereum.Upgrades.tangerineWhistle),
      "the same proposal produced different rules, or two networks adopted it at one block"
    )

  "the component list alone" should "be unable to see a divergence at genesis" in
    // Why the assertions above compare whole rule sets. A launch configuration
    // adopts nothing, so both networks' component lists are empty there and
    // would agree whatever their prices, tables or precompiles were.
    assert(
      ethereumAt(0L).components.isEmpty && classicAt(0L).components.isEmpty,
      "a genesis configuration records an adopted proposal, which is not what a launch configuration is"
    )

  "the two networks' machines" should "be one machine again, reached on each schedule at its own height" in
    // The mirror of the parting. It is asserted over three facets rather than
    // over the rule sets because the fourth is where the two networks stay
    // apart, and a comparison of the whole value would report that difference
    // and say nothing about the agreement this is about.
    //
    // The heights are carried in one case with the agreement, as the EIP-150
    // case below carries its own, because the agreement is only interesting
    // while the two are apart. The last conjunct is what would state that, and
    // it is the weakest thing here: the two activations CANNOT coincide while
    // both schedules are ordered, since this network's previous entry is above
    // every entry the other network's schedule holds, so that comparison could
    // not have failed for the reason it names. What it does refute is either
    // rule set having no entry carrying it at all, which `activationCarrying`
    // reports and a height literal cannot.
    assert(
      classicAt(classicRealignmentBlock).evm == ethereumAt(ethereumRealignmentBlock).evm &&
        classicAt(classicRealignmentBlock).execution == ethereumAt(ethereumRealignmentBlock).execution &&
        classicAt(classicRealignmentBlock).admission == ethereumAt(ethereumRealignmentBlock).admission &&
        activationCarrying(classicSchedule, ethereumclassic.Upgrades.atlantis) !=
        activationCarrying(ethereumSchedule, ethereum.Upgrades.byzantium),
      "the two networks run different machine, settlement or admission rules at the heights their machines realign"
    )

  "the machines agreeing" should "not extend to what a block owes the mechanism that produced it" in
    // Where the reconvergence stops, stated member by member rather than as an
    // inequality over the facet, so that it says WHICH rules stayed apart. The
    // first conjunct is the one consensus proposal both networks took from that
    // fork; the rest are the emission one of them declined to cut and the three
    // bomb rules only one of them has ever set.
    assert(
      classicAt(classicRealignmentBlock).consensus.difficultyAdjustment ==
        ethereumAt(ethereumRealignmentBlock).consensus.difficultyAdjustment &&
        classicAt(classicRealignmentBlock).consensus.blockReward !=
        ethereumAt(ethereumRealignmentBlock).consensus.blockReward &&
        classicAt(classicRealignmentBlock).consensus.difficultyBombDelay !=
        ethereumAt(ethereumRealignmentBlock).consensus.difficultyBombDelay &&
        classicAt(classicRealignmentBlock).consensus.difficultyBombPause !=
        ethereumAt(ethereumRealignmentBlock).consensus.difficultyBombPause &&
        classicAt(classicRealignmentBlock).consensus.difficultyBombRemovedFrom !=
        ethereumAt(ethereumRealignmentBlock).consensus.difficultyBombRemovedFrom,
      "the two networks agree about an emission or a bomb rule only one of them adopted, or differ about the one they shared"
    )

  it should "leave the two records differing by exactly the proposal one declined and the four the other took alone" in
    // The record's side of the same boundary, and it is independent of the
    // values above: a delta is an arbitrary function, so the two can disagree.
    // Asserted as the difference in each direction rather than as membership,
    // because a list stating that a proposal is absent is what an empty list
    // also states.
    //
    // The launch caveat above is why this is read as a difference between two
    // populated records rather than as a claim about either one alone.
    assert(
      ethereumAt(ethereumRealignmentBlock).components.diff(classicAt(classicRealignmentBlock).components) ==
        Vector(ProposalId.Eip(649)) &&
        classicAt(classicRealignmentBlock).components.diff(ethereumAt(ethereumRealignmentBlock).components) ==
        Vector(ProposalId.Ecip(1010), ProposalId.Ecip(1017), ProposalId.Ecip(1039), ProposalId.Ecip(1041)),
      "the two records differ by some other set of proposals than the one declined and the four taken alone"
    )

  "the machines realigning" should "happen again one upgrade later, and still not make the two networks one" in
    // The same claim at the next height either network moves, and it is asserted
    // as a PAIR because either half alone misstates the relation. The equality
    // says the two machines are one machine again; the inequality says that is a
    // fact about three facets and not about the networks. Read alone, the first
    // overstates and the second understates.
    //
    // The delta arriving here is the same three proposals on both schedules.
    // What differs is what surrounds it: the other network takes two more of
    // that fork's five and then withdraws one of them, and this one takes
    // neither -- so the machines agree while the records and the consensus rules
    // do not. `ethereumclassic.Upgrades.agharta` carries the evidence for both
    // halves.
    //
    // The inequality is the weaker half and is worth reading as such: more than
    // one member of that facet differs here, so it would go on holding even if
    // this network adopted the emission cut it declines. What makes THAT
    // refutable is the assertion over the values themselves, in
    // `ethereumclassic.UpgradesSpec`. This says only that the two facets are not
    // one, which is what a reader concluding "these are the same rules" from the
    // three equalities above would have got wrong.
    assert(
      classicAt(classicSecondRealignmentBlock).evm == ethereumAt(ethereumSecondRealignmentBlock).evm &&
        classicAt(classicSecondRealignmentBlock).execution ==
        ethereumAt(ethereumSecondRealignmentBlock).execution &&
        classicAt(classicSecondRealignmentBlock).admission ==
        ethereumAt(ethereumSecondRealignmentBlock).admission &&
        classicAt(classicSecondRealignmentBlock).consensus !=
        ethereumAt(ethereumSecondRealignmentBlock).consensus,
      "the two networks' machines parted at the upgrade above their reconvergence, or their consensus rules became one"
    )

  it should "happen a third time, at the upgrade where both networks take one upstream set whole" in
    // The same pair one upgrade further on, and the first realignment at which
    // neither network declines anything: both adopt the same six proposals,
    // where the two below them each took a subset of what the other's document
    // named.
    //
    // The equality is also where this build's chain-identifier placement is
    // exercised, which is what makes it more than a third copy of the case
    // above.
    //
    // One of the six adds an operation that pushes the network's own
    // identifier. This build puts that value on org.fukuii.evm.Environment, so
    // the operation the rule set carries holds an opcode and a cost and nothing
    // network-specific -- which is the whole reason two networks' rule sets can
    // be compared by value here at all. Had the identifier been a parameter of
    // the fork-resolved rules, this equality would fail 61 against 1 and the
    // comparison would have to be abandoned or special-cased.
    //
    // That is not a hypothetical shape. besu-eth/besu-etc @ eb4248c997 builds
    // this upgrade's machine as MainnetEVMs.istanbul(gasCalculator, chainId,
    // ...), whose registerIstanbulOperations constructs the operation as
    // new ChainIdOperation(gasCalculator, Bytes32.leftPad(chainId)) -- the
    // identifier baked into the fork definition. Two networks' Istanbul-era
    // machines are not one value in that client, and the assertion below could
    // not be written against it.
    assert(
      classicAt(classicThirdRealignmentBlock).evm == ethereumAt(ethereumThirdRealignmentBlock).evm &&
        classicAt(classicThirdRealignmentBlock).execution ==
        ethereumAt(ethereumThirdRealignmentBlock).execution &&
        classicAt(classicThirdRealignmentBlock).admission ==
        ethereumAt(ethereumThirdRealignmentBlock).admission &&
        classicAt(classicThirdRealignmentBlock).consensus !=
        ethereumAt(ethereumThirdRealignmentBlock).consensus,
      "the two networks' machines parted where both took the same six proposals, or their consensus rules became one"
    )

  it should "happen a fourth time, at the upgrade where the two networks' admission rules first move" in
    // The first realignment at which the ADMISSION facet is doing work. Through
    // the third, every rule set on both networks admitted the untagged format
    // alone -- set by each network's own `frontier` and moved by nothing -- so
    // "the admission facets are equal" had been true partly by never having been
    // tested. Here both networks widen it, 945,133 blocks apart, and the case
    // below states the interval where they disagree.
    //
    // The consensus inequality holds several times over rather than narrowly:
    // the block reward is five ether against two, one network carries an
    // exponential term held back by nine million blocks where the other removed
    // it outright, and only one of the two has calibrated an epoch.
    assert(
      classicAt(classicFourthRealignmentBlock).evm == ethereumAt(ethereumFourthRealignmentBlock).evm &&
        classicAt(classicFourthRealignmentBlock).execution ==
        ethereumAt(ethereumFourthRealignmentBlock).execution &&
        classicAt(classicFourthRealignmentBlock).admission ==
        ethereumAt(ethereumFourthRealignmentBlock).admission &&
        classicAt(classicFourthRealignmentBlock).consensus !=
        ethereumAt(ethereumFourthRealignmentBlock).consensus,
      "the two networks' machines parted where both took the same four proposals, or their consensus rules became one"
    )

  it should "have disagreed about which formats are admissible for the whole interval between the two activations" in
    // The case with no analogue at any earlier realignment, and what stops the
    // equality above reporting an agreement that has been continuous rather than
    // one that was restored. It is the admission-facet counterpart of the
    // machine case at the first parting: the two networks are compared at a
    // height where only one of them has widened the set.
    //
    // Both endpoints matter. At the lower one the other network has already
    // moved and this one has not; one block below this network's own activation
    // it still has not.
    assert(
      ethereumAt(ethereumFourthRealignmentBlock).admission !=
        classicAt(ethereumFourthRealignmentBlock).admission &&
        ethereumAt(ethereumFourthRealignmentBlock).admission !=
        classicAt(classicFourthRealignmentBlock - 1L).admission,
      "the two networks admitted the same transaction formats at a height where only one of them had widened the set"
    )

  it should "leave the two records differing by one more proposal in each direction, having grown symmetrically" in
    // The record's side of the fourth realignment, and the one place where the
    // difference GROWS at a realignment rather than cancelling. Both networks
    // adopt the same four proposals here, so those four cancel exactly as the
    // six did one upgrade below -- but each network also adopted one proposal
    // alone since that upgrade, and neither of those cancels.
    //
    // One network delayed an exponential term it still carries; this one
    // calibrated a mining epoch. Both are consensus proposals reaching a rule no
    // state-transition tier can observe, which is why the record is where they
    // are visible at all.
    //
    // The literals are what keep the equalities from being vacuous, and the
    // comparison against the upgrade below is what makes the growth the claim
    // rather than an accident of two lists.
    assert(
      ethereumAt(ethereumFourthRealignmentBlock).components
        .diff(classicAt(classicFourthRealignmentBlock).components) ==
        Vector(
          ProposalId.Eip(649),
          ProposalId.Eip(1234),
          ProposalId.Eip(1283),
          ProposalId.Eip(1716),
          ProposalId.Eip(2384)
        ) &&
        classicAt(classicFourthRealignmentBlock).components
          .diff(ethereumAt(ethereumFourthRealignmentBlock).components) ==
        Vector(
          ProposalId.Ecip(1010),
          ProposalId.Ecip(1017),
          ProposalId.Ecip(1039),
          ProposalId.Ecip(1041),
          ProposalId.Ecip(1099)
        ) &&
        ethereumAt(ethereumFourthRealignmentBlock).components
          .diff(classicAt(classicFourthRealignmentBlock).components)
          .size ==
        ethereumAt(ethereumThirdRealignmentBlock).components
          .diff(classicAt(classicThirdRealignmentBlock).components)
          .size + 1 &&
        classicAt(classicFourthRealignmentBlock).components
          .diff(ethereumAt(ethereumFourthRealignmentBlock).components)
          .size ==
        classicAt(classicThirdRealignmentBlock).components
          .diff(ethereumAt(ethereumThirdRealignmentBlock).components)
          .size + 1,
      "the four proposals both networks adopted here did not cancel, or the one each took alone did"
    )

  it should "leave the two records differing by exactly what they differed by one upgrade below" in
    // The record's side of the third realignment, and the precise statement of
    // adoption rather than construction. Both networks adopt the SAME six
    // proposals here, so the six cancel out of both directions of the
    // difference and what is left is the difference that was already there.
    //
    // The literals are what keep the equalities from being vacuous -- two empty
    // differences would satisfy them -- and the equalities are what make the
    // claim about this upgrade rather than a second copy of the one below. A
    // difference that MOVED here would mean the two networks took different
    // sets, which is exactly the claim being made.
    //
    // Read the direction: one network declined the emission cut of its own
    // earlier fork and then adopted a storage scheme it later withdrew, and
    // this one took four consensus proposals of its own series. Neither list
    // has anything to do with the six adopted here.
    assert(
      ethereumAt(ethereumThirdRealignmentBlock).components
        .diff(classicAt(classicThirdRealignmentBlock).components) ==
        Vector(ProposalId.Eip(649), ProposalId.Eip(1234), ProposalId.Eip(1283), ProposalId.Eip(1716)) &&
        classicAt(classicThirdRealignmentBlock).components
          .diff(ethereumAt(ethereumThirdRealignmentBlock).components) ==
        Vector(ProposalId.Ecip(1010), ProposalId.Ecip(1017), ProposalId.Ecip(1039), ProposalId.Ecip(1041)) &&
        ethereumAt(ethereumThirdRealignmentBlock).components
          .diff(classicAt(classicThirdRealignmentBlock).components) ==
        ethereumAt(ethereumSecondRealignmentBlock).components
          .diff(classicAt(classicSecondRealignmentBlock).components) &&
        classicAt(classicThirdRealignmentBlock).components
          .diff(ethereumAt(ethereumThirdRealignmentBlock).components) ==
        classicAt(classicSecondRealignmentBlock).components
          .diff(ethereumAt(ethereumSecondRealignmentBlock).components),
      "the six proposals both networks adopted here did not cancel, so one of them adopted something the other did not"
    )
