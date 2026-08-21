package org.fukuii.chainspec.networks

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.Schedule
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
  * [[EthereumClassic]] composes its own rule sets from the proposal vocabulary
  * and names nothing in [[Ethereum]]. Two configurations built from one value
  * are equal however either was authored, so an assertion over shared values
  * would report the sharing and never the agreement. The first test below is
  * what holds that open, and it is the one to read before trusting any of the
  * others.
  *
  * ==The boundary is a divergence of STATE, not of rules==
  *
  * This is the half that reads backwards and is the reason the range below
  * stops where it does rather than where the rules part. EIP-779 states it
  * directly: *"Unlike other hard forks, the DAO Fork did not change the
  * protocol; all EVM opcodes, transaction format, block structure, and so on
  * remained the same. Rather, the DAO Fork was an 'irregular state change'"*.
  *
  * So at block 1,920,000 the two networks began building different state under
  * identical rules, and their RULES stayed equal for another 543,000 blocks
  * until Ethereum mainnet repriced first. A test asserting that the rules
  * diverge at the DAO fork would be asserting something false, and one
  * asserting they agree wherever they agree would run past the point the two
  * chains stopped being one chain. The range below is the second bound: the
  * last height at which both networks were the same chain.
  *
  * ==This assertion is the only place the parting is recorded==
  *
  * Neither schedule holds it, and neither should: Ethereum mainnet's DAO fork
  * entry needs a layer that can mutate state, and Ethereum Classic declined the
  * upgrade, which is not a schedule entry at all -- an entry that never
  * activates resolves nothing, orders nothing and reaches nothing, so it would
  * be documentation filed in a configuration.
  *
  * So the block below is stated here and sourced here, and if this assertion is
  * ever deleted the figure and its reason leave the build with it. That is the
  * intended trade: an assertion that must be deleted deliberately, against a
  * schedule entry that could rot unnoticed.
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

  private val ethereum: Schedule =
    EthereumMainnet.schedule.getOrElse(fail("Ethereum mainnet's authored entries do not form a schedule"))

  private val classic: Schedule =
    EthereumClassicMainnet.schedule.getOrElse(fail("Ethereum Classic's authored entries do not form a schedule"))

  /** The last block at which the two networks were one chain. */
  private val lastSharedBlock: UInt64 = UInt64.fromBits(1919999L)

  private def ethereumAt(height: Long) = ethereum.at(UInt64.fromBits(height), UInt64.Zero)
  private def classicAt(height: Long) = classic.at(UInt64.fromBits(height), UInt64.Zero)

  "the two networks' rule sets" should "be separately built values rather than one value twice" in
    // Everything below compares by value, and value comparison cannot tell a
    // genuine agreement from a shared reference. This is the only test here
    // that can, and without it the rest are satisfied by construction.
    assert(
      (EthereumClassic.frontier ne Ethereum.frontier) &&
        (EthereumClassic.homestead ne Ethereum.homestead) &&
        (EthereumClassic.gasReprice ne Ethereum.tangerineWhistle),
      "one network's configuration is the other's, so every agreement asserted here is a tautology"
    )

  "the two networks" should "resolve to the same rules at every height they were one chain" in
    // Spot heights rather than a sweep: the range holds 1.92 million blocks and
    // three of the four boundaries in it are covered by the table beside this.
    // What this adds is the top of the range, which no per-network table reaches.
    assert(
      classicAt(0L) == ethereumAt(0L) &&
        classicAt(1150000L) == ethereumAt(1150000L) &&
        classic.at(lastSharedBlock, UInt64.Zero) == ethereum.at(lastSharedBlock, UInt64.Zero),
      "two networks that were one chain at this height disagree about what it ran"
    )

  it should "still agree at the block their chains parted, because that fork changed no rule" in
    // The fact that keeps the boundary honest. Read the other way -- rules
    // diverging here -- the assertion above would be given a false upper bound
    // and would stop testing the range it exists for.
    assert(
      classicAt(1920000L) == ethereumAt(1920000L),
      "the DAO fork was an irregular state change, so no rule a node validates differs across it"
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
      EthereumClassic.gasReprice == Ethereum.tangerineWhistle &&
        classic.forkPoints.last != ethereum.forkPoints.last,
      "the same proposal produced different rules, or two networks adopted it at one block"
    )

  "the component list alone" should "be unable to see a divergence at genesis" in
    // Why the assertions above compare whole specs. A launch configuration
    // adopts nothing, so both networks' component lists are empty there and
    // would agree whatever their prices, tables or precompiles were.
    assert(
      ethereumAt(0L).components.isEmpty && classicAt(0L).components.isEmpty,
      "a genesis configuration records an adopted proposal, which is not what a launch configuration is"
    )
