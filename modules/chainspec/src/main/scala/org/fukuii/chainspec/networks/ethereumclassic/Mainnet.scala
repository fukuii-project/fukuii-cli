package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, Network, Upgrade, UpgradeId, UpgradeSchedule}

/** Ethereum Classic mainnet: which of [[Upgrades]]'s rule sets it runs,
  * and from when.
  *
  * ==Every activation here is an EXTERNAL fact, sourced one at a time==
  *
  * A rule set is composed from proposals and can be checked by running it. An
  * activation cannot: it is a number this network's participants agreed on, and
  * nothing in this repository can derive or contradict it. **A wrong one moves
  * every certification case across the boundary it names** and is invisible to
  * a run that never straddles it, so each is cited below on its own rather than
  * transcribed as a set.
  *
  * ==This network's byte authority is its own reference implementation==
  *
  * `ethereumclassic/core-geth` @ `4185df450364973bbf99efa3923791f5ba40b351`
  * (2025-01-23) is what this network runs, and it is cited first for every
  * activation below. Where an ECIP also states the figure, both are given: the
  * proposal is where the network decided, and the implementation is what it
  * decided to. A disagreement between them would be a finding rather than
  * something to resolve here, and there is none.
  *
  * `besu-eth/besu-etc` @ `eb4248c997cb79cc88db55ead562081a43721a3b` (2026-02-09)
  * supplies a second reading in a different language family. It is a reference
  * build rather than a mainstream client, so it corroborates and does not
  * settle.
  *
  * The `ethereumclassic/ECIPs` clone is full and carries no tags, so each ECIP
  * is cited by document number and commit, per
  * `.claude/rules/evidence-and-citation.md` §1.
  *
  * ==The first two entries are inherited history, and neither implementation
  * schedules them==
  *
  * This network ran under Ethereum mainnet's launch configuration because for
  * its first 1,920,000 blocks it *was* that chain. So its Frontier and Frontier
  * Thawing entries are not activations it chose, and `params/config_classic.go`
  * at `4185df450` carries no field for either -- its earliest is block
  * 1,150,000. Both entries take the sourcing [[ethereum.Mainnet]] states for
  * its own two, and the enumeration carries them for the reason it carries
  * every entry: one omitted misnumbers everything after it.
  */
object Mainnet:

  /** A block number stated as a literal from a specification.
    *
    * [[UInt64.fromLong]]'s non-negative precondition exists to stop an
    * arithmetic slip reading as a quantity near 2^64; there is no arithmetic
    * here, and every caller below is a visible non-negative literal, so the
    * bits are the value.
    */
  private def atBlock(number: Long): Activation = Activation.AtBlock(UInt64.fromBits(number))

  /** Chain id 61.
    *
    * Three sources agree, and the registry is one of them.
    * `ethereum-lists/chains` @ `39a763d1b437b85baf794b6d11efdb79a2bca796`
    * (2024-05-21) gives `"chainId": 61` in `_data/chains/eip155-61.json`, which
    * is the registry EIP-155 § *List of Chain ID's* names for identifiers its
    * own table does not carry -- and 61 is one of those, so EIP-155 cannot
    * answer this the way it answers Ethereum mainnet's.
    * `ethereumclassic/core-geth` @ `4185df450` declares
    * `ChainID: big.NewInt(61)` in `params/config_classic.go`, and
    * `besu-eth/besu-etc` @ `eb4248c997` declares `"chainId": 61` in
    * `config/src/main/resources/classic.json`.
    *
    * **This is not the peer-to-peer network identifier**, which is a separate
    * number this network sets to 1 -- `NetworkID: 1` in the same core-geth
    * declaration, `"networkId": 1` in the same registry entry. [[Network]]
    * states why that one is deliberately absent from this module.
    *
    * The name is a display concern, per [[Network]]'s own contract, and is not
    * sourced to the same standard as the id.
    */
  val network: Network = Network(UInt64.fromBits(61L), "Ethereum Classic")

  private def upgrade(label: String): UpgradeId = UpgradeId.named(network, label)

  /** The rules this network launched with, in force from its first block.
    *
    * Inherited rather than chosen, per this object's own documentation, so the
    * figure is Ethereum mainnet's and is sourced in [[ethereum.Mainnet]]. This
    * network's own enumeration -- ECIP-1066,
    * `ethereumclassic/ECIPs` @ `e36ef7f10166769aa3ac469aaf27ba5b0cacb198`
    * (2026-07-05) -- tabulates `Frontier | 1`, and the entry is block zero here
    * for the reason given there: a schedule answers *what rules are in force at
    * a height* and is asked at height zero, so a schedule starting at block one
    * could not answer for the block below it.
    */
  private val frontier: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(0), upgrade("Frontier"), Upgrade.RuleChange(Upgrades.frontier))

  /** Block 200,000, gating nothing.
    *
    * Inherited rather than chosen. ECIP-1066 at `e36ef7f1` tabulates
    * `Frontier Thawing | 200000` and is one of three rows there carrying
    * neither a specification nor an included proposal -- the others being the
    * genesis row and an upgrade recorded as `aborted`, so this is the only one
    * of the three that both activated and was not genesis. That emptiness is
    * this network's own way of saying nothing was scheduled here.
    * **No implementation carries the value** -- it appears in no chain
    * configuration in core-geth, and besu-etc does not name the upgrade at all
    * -- so the sourcing is documentary, exactly as it is in
    * [[ethereum.Mainnet]], where what it changed and why that is not a rule are
    * stated.
    */
  private val frontierThawing: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(200000), upgrade("Frontier Thawing"), Upgrade.Unenforced)

  /** Block 1,150,000.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states it as the two proposals
    * rather than the fork name -- `EIP2FBlock: big.NewInt(1150000)` and
    * `EIP7FBlock: big.NewInt(1150000)` in `params/config_classic.go` -- and
    * `besu-eth/besu-etc` @ `eb4248c997` states it as
    * `"homesteadBlock": 1150000` in `config/src/main/resources/classic.json`.
    * ECIP-1066 at `e36ef7f1` tabulates `Homestead | 1150000`.
    *
    * The two implementations disagree about nothing except which level they
    * name it at, which is [[org.fukuii.chainspec.ProposalId]]'s two levels seen
    * from the outside.
    */
  private val homestead: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(1150000), upgrade("Homestead"), Upgrade.RuleChange(Upgrades.homestead))

  /** Block 2,500,000.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states it as
    * `EIP150Block: big.NewInt(2500000)` in `params/config_classic.go`, and
    * `besu-eth/besu-etc` @ `eb4248c997` states it as
    * `"ecip1015Block": 2500000` in `config/src/main/resources/classic.json`.
    * ECIP-1015 -- `ethereumclassic/ECIPs` @ `e36ef7f1`, Final -- proposes it in
    * its own words: *"Hard fork on block 2,500,000"*. ECIP-1066 at the same
    * commit tabulates `Gas Reprice | 2500000`.
    *
    * **The figure is 37,000 blocks later than the other network's, and the
    * proposal says why in the same document**: it enumerates the clients then
    * in the field, including one already repricing at the other network's block
    * under a flag, and chooses a separate activation for this network.
    */
  private val gasReprice: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(2500000), upgrade("Gas Reprice"), Upgrade.RuleChange(Upgrades.gasReprice))

  /** This network's upgrades in order, or the first reason they are not a
    * schedule.
    *
    * ==The failure is returned rather than thrown==
    *
    * These entries are authored, so a `Left` here means this file disagrees
    * with itself and no run could be correct. It is still returned: a `val`
    * that throws fails at class initialization, which surfaces as an unrelated
    * error in whatever first touched the object, and the checks
    * [[UpgradeSchedule.of]] performs are exactly the ones worth reading in a
    * failure message.
    *
    * ==What this network declined is not an entry here==
    *
    * The upgrade the two networks parted over is absent, and the absence is the
    * decision. An entry for it would carry no activation point, take no
    * ordering position and reach nothing derived from the schedule: it would
    * occupy a slot in this vector and have no other effect, which is
    * `params/config_classic.go`'s commented-out field at `4185df450` written in
    * a different syntax and filed in the same place -- documentation living in
    * a configuration.
    *
    * There is also no boundary on which non-events would belong. This network
    * declined the difficulty-bomb delays and the consensus transition too, and
    * the only thing that would single out one of them is being historically
    * notable, which is a reason to write about it rather than to configure it.
    *
    * **Where the parting IS recorded is `SharedHistorySpec`**, as an assertion
    * naming both networks, the block and the reason. That is the form the
    * divergence belongs in: it degrades correctly, because at the point the two
    * stop agreeing it is deleted with a reason rather than quietly left true.
    */
  val schedule: Either[UpgradeSchedule.Error, UpgradeSchedule] =
    UpgradeSchedule.of(Vector(frontier, frontierThawing, homestead, gasReprice))
