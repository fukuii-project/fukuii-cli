package org.fukuii.chainspec.networks

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, UpgradeSchedule}
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

  private def ethereumAt(height: Long) = ethereumSchedule.at(UInt64.fromBits(height), UInt64.Zero)
  private def classicAt(height: Long) = classicSchedule.at(UInt64.fromBits(height), UInt64.Zero)

  "the two networks' rule sets" should "be separately built values rather than one value twice" in
    // Everything below compares by value, and value comparison cannot tell a
    // genuine agreement from a shared reference. This is the only test here
    // that can, and without it the rest are satisfied by construction.
    assert(
      (ethereumclassic.Upgrades.frontier ne ethereum.Upgrades.frontier) &&
        (ethereumclassic.Upgrades.homestead ne ethereum.Upgrades.homestead) &&
        (ethereumclassic.Upgrades.gasReprice ne ethereum.Upgrades.tangerineWhistle),
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
    assert(
      ethereumclassic.Upgrades.gasReprice == ethereum.Upgrades.tangerineWhistle &&
        classicSchedule.forkPoints.last != ethereumSchedule.forkPoints.last,
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
