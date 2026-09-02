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

  /** Block 3,000,000.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states it as the three proposals
    * rather than the fork name -- `EIP155Block`, `EIP160FBlock` and
    * `ECIP1010PauseBlock`, all `big.NewInt(3000000)` and written as one group
    * in `params/config_classic.go`. `openethereum/openethereum` @ `v3.0.1`
    * states the same three in a different codebase and in two places:
    * `eip155Transition` and `eip160Transition` `0x2dc6c0` under `params`, and
    * `ecip1010PauseTransition` `0x2dc6c0` under `engine.Ethash.params`, both in
    * `ethcore/res/ethereum/classic.json`. ECIP-1010 -- `ethereumclassic/ECIPs`
    * @ `f398567f4`, Final -- names the height itself, as
    * `pause_block = 3000000`. ECIP-1066 at `e36ef7f1` tabulates
    * `Die Hard | 3000000`.
    *
    * **`besu-eth/besu-etc` @ `eb4248c997` agrees on the height and not on
    * everything the height carries.** It names the figure
    * `"dieHardBlock": 3000000` in `config/src/main/resources/classic.json`, so
    * this activation is three lineages rather than two, and its
    * `ClassicProtocolSpecs` definition for the upgrade installs the paused
    * difficulty calculator and a gas calculator overriding the per-byte
    * exponent charge alone. What it does not put here is the later signing
    * scheme, which its preceding definition already admitted --
    * [[Upgrades.dieHard]] records that divergence, because it is about which
    * proposals this height carries rather than about where the height is.
    */
  private val dieHard: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(3000000), upgrade("Die Hard"), Upgrade.RuleChange(Upgrades.dieHard))

  /** Block 5,000,000.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states it as
    * `ECIP1017FBlock: big.NewInt(5000000)` in `params/config_classic.go`,
    * beside the era length it pairs with. `besu-eth/besu-etc` @ `eb4248c997`
    * states it as `"gothamBlock": 5000000` in
    * `config/src/main/resources/classic.json`. ECIP-1066 at `e36ef7f1`
    * tabulates `Gotham | 5000000`. Neither ECIP-1017 nor ECIP-1039 names a
    * height itself, which is why the two implementations are the whole of the
    * sourcing for this figure.
    *
    * **This entry changes no value any rule set holds, and is a rule change
    * regardless.** [[Upgrades.gotham]] says why the values do not move. What
    * moves is the emission, which
    * `org.fukuii.consensus.pow.EthashEngine` computes rather than reads, so two
    * nodes disagreeing across this height disagree about a block's validity --
    * which is what [[Upgrade.RuleChange]] asserts and what
    * [[Upgrade.Unenforced]] would deny. The network states the same thing at
    * the peer layer: 5,000,000 is one of the twelve fork blocks its
    * EIP-2124 identifier is a checksum over, and 200,000 -- the height this
    * schedule records as enforcing nothing -- is not among them.
    */
  private val gotham: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(5000000), upgrade("Gotham"), Upgrade.RuleChange(Upgrades.gotham))

  /** Block 5,900,000.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states it as
    * `DisposalBlock: big.NewInt(5900000)` in `params/config_classic.go`.
    * `openethereum/openethereum` @ `v3.0.1` states it as
    * `"bombDefuseTransition": "0x5a06e0"` under `engine.Ethash.params` in
    * `ethcore/res/ethereum/classic.json`, and `besu-eth/besu-etc` @
    * `eb4248c997` as `"ecip1041Block": 5900000` in
    * `config/src/main/resources/classic.json`. **ECIP-1041 --
    * `ethereumclassic/ECIPs` @ `8dda72c24`, Final -- names the height itself**,
    * in its abstract and again where it settles on it between the two bounds
    * it argues for. ECIP-1066 at `e36ef7f1` tabulates
    * `Defuse Difficulty Bomb | 5900000`.
    *
    * So the figure is stated by the document that decided it as well as by the
    * three implementations that took it, which is the second entry on this
    * schedule of which that is true -- [[dieHard]]'s ECIP-1010 states its own
    * `pause_block` the same way. [[gotham]] is the contrast and says so: no
    * proposal names that height, so its implementations are the whole of its
    * sourcing.
    *
    * **The label is stated twice and the two agree to the character.** It is
    * ECIP-1066's `Version and Code Name` cell verbatim, which is where every
    * other entry's label comes from, and it is also
    * `DEFUSE_DIFFICULTY_BOMB(true, "Defuse Difficulty Bomb")` in
    * `HardforkId.ClassicHardforkId` at `eb4248c997`. core-geth names no
    * upgrade here at all -- it carries the field and nothing else -- which is
    * why a label is documentary on this schedule rather than read off an
    * implementation.
    */
  private val defuse: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(5900000), upgrade("Defuse Difficulty Bomb"), Upgrade.RuleChange(Upgrades.defuse))

  /** Block 8,772,000.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states it as ten per-proposal
    * transitions rather than a fork name, every one of them
    * `big.NewInt(8772000)` in `params/config_classic.go:56-67`.
    * `besu-eth/besu-etc` @ `eb4248c997` states it as `"atlantisBlock": 8772000`
    * in `config/src/main/resources/classic.json`, and
    * `openethereum/openethereum` @ `v3.0.1` as `0x85d9a0`, written across the
    * eight transitions and the four native prices it sets in
    * `ethcore/res/ethereum/classic.json`. So this activation is three lineages,
    * as [[dieHard]]'s is.
    *
    * **ECIP-1054 -- `ethereumclassic/ECIPs` @
    * `f4ed3315e23427180b7437235667b6911255ab9d`, Final -- names the height
    * itself**, in the same abstract that names the three test networks it
    * proposes for. ECIP-1066 at `e36ef7f1` tabulates `8772000`.
    *
    * **The label is that table's `Version and Code Name` cell less the
    * subscripted counterpart it carries.** The cell reads
    * `Atlantis <sub>Byzantium</sub>`, and the subscript is how that table names
    * the counterpart upgrade on the network this one parted from -- the rows
    * for upgrades this network took alone, [[gotham]] and [[defuse]] among
    * them, carry no subscript at all. `besu-eth/besu-etc` @ `eb4248c997` states
    * the label without it, as `ATLANTIS(true, "Atlantis")` in
    * `HardforkId.ClassicHardforkId`, and the two agree on the part this entry
    * takes.
    *
    * **What that subscript records is where the two networks' machines
    * realign, and not that the networks become the same.** The irregular state
    * change one of them applied at 1,920,000 is not expressible as a rule-set
    * member at all, this network's own consensus series has no counterpart
    * there, and [[Upgrades.atlantis]] states which Byzantium proposal it
    * declined.
    */
  private val atlantis: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(8772000), upgrade("Atlantis"), Upgrade.RuleChange(Upgrades.atlantis))

  /** Block 9,573,000.
    *
    * **ECIP-1056 -- `ethereumclassic/ECIPs` @
    * `7558f1ea4061f33bc21c8b93bbdd0c4796d61f17`, Final -- names the height
    * itself**, listing `9_573_000` for this network among the four it proposes
    * across the mainnet and the three test networks.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states it as three per-proposal
    * transitions rather than a fork name, every one of them
    * `big.NewInt(9573000)` in `params/config_classic.go:70-72`. The same tree
    * states it a second time in the form the peer layer consumes:
    * `core/forkid/forkid_test.go:483` lists 9,573,000 among the twelve fork
    * blocks this network's EIP-2124 identifier is a checksum over, seventh of
    * the twelve and directly above [[atlantis]]'s.
    *
    * Two further lineages that derive from neither that tree nor each other
    * agree. `besu-eth/besu-etc` @ `eb4248c997` states it as
    * `"aghartaBlock": 9573000` in `config/src/main/resources/classic.json`, and
    * `openethereum/openethereum` @ `v3.0.1` as `0x921288`, written across the
    * three transitions it sets in `ethcore/res/ethereum/classic.json`. So this
    * activation is three lineages, as [[dieHard]]'s and [[atlantis]]'s are.
    *
    * ==THE COMMIT CITED BELOW CARRIED A WRONG FIGURE HERE, AND HAS SINCE BEEN
    * CORRECTED UPSTREAM==
    *
    * At `e36ef7f10166769aa3ac469aaf27ba5b0cacb198` (2026-07-05) -- **the same
    * commit [[atlantis]] cites for its own height and label, where it is
    * correct** -- that table gave this row `9583000`. That figure had no
    * support anywhere: a sweep for it across the client trees in this project's
    * reference corpus returns nothing, while the same sweep shape finds
    * 9,573,000 in each of the three cited above. **`ethereumclassic/ECIPs`
    * master now carries 9,573,000**, so this is a statement about one commit
    * and not about ECIP-1066.
    *
    * **The reason to record it survives the correction, and it is why the
    * height above is sourced to implementations rather than to a tabulation.**
    * A citation names a ref that cannot move, which is what makes it
    * checkable -- and the same property makes it carry that ref's errors
    * forward indefinitely after the document is fixed. A reader who took the
    * figure from the cited commit rather than from the four sources above would
    * move every case in this network's certification tiers ten thousand blocks
    * across a boundary those tiers never straddle, which no run would report.
    *
    * **[[Upgrades.atlantis]] states the neighbouring rule** -- that a table
    * restating a specification is a sound source for where an upgrade sits and
    * what it is called, and the wrong source for what it carries. That same
    * commit's Atlantis row is the corroborating instance, listing eight
    * proposals and omitting EIP-161 and EIP-170, which
    * `params/config_classic.go:56-57` sets at that height; it has since been
    * corrected upstream too. **Neither observation is a reason to restate
    * [[atlantis]]'s sourcing**, which already reads membership off the
    * specification.
    *
    * ==The label==
    *
    * That table's `Version and Code Name` cell less the subscripted counterpart
    * it carries, which is where every other label on this schedule comes from.
    * The cell reads `Agharta <sub>Constantinople+Petersburg</sub>`, and the
    * subscript names both of the other network's upgrades because
    * [[Upgrades.agharta]]'s rules are that pair's net effect on the parts this
    * network took. `besu-eth/besu-etc` @ `eb4248c997` states the label without
    * it, as `AGHARTA(true, "Agharta")` in `HardforkId.ClassicHardforkId`, and
    * the two agree to the character.
    *
    * **A label is documentary and the figure beside it is not**, which is why
    * the two halves of that cell are taken from it and the height in the next
    * column is not.
    */
  private val agharta: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(9573000), upgrade("Agharta"), Upgrade.RuleChange(Upgrades.agharta))

  /** Block 10,500,839.
    *
    * **ECIP-1088 -- `ethereumclassic/ECIPs` @
    * `f1bb761a80bcaae10c7b5e0c39e4a62062b1c023`, Final -- names the height
    * itself**, listing `10_500_839` for this network among the three it
    * proposes across the mainnet and two test networks.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states it as six per-proposal
    * transitions rather than a fork name, every one of them `big.NewInt(10_500_839)`
    * in `params/config_classic.go:78-83`. `besu-eth/besu-etc` @ `eb4248c997`
    * states it as `"phoenixBlock": 10500839` in
    * `config/src/main/resources/classic.json`, and
    * `openethereum/openethereum` @ `v3.0.1` as `0xa03ae7`, written across the
    * transitions and native prices it sets in
    * `ethcore/res/ethereum/classic.json`.
    *
    * **That last reading corroborates the HEIGHT and must not be carried over
    * to the membership**, which is where the entry below it does use it.
    * [[Upgrades.phoenix]] records that the commit configuring this upgrade in
    * that tree shares an author with the proposal, so on membership it restates
    * ECIP-1088 rather than reading it -- and a restatement of a figure this
    * entry already takes from the proposal costs nothing, where a restatement
    * of a membership is not a second opinion at all.
    *
    * ==An unusual number, and it is the specification's own==
    *
    * Every other activation on this schedule is a round figure. This one is
    * not, and all four sources above carry it to the last digit -- so the
    * oddity is the network's, not a transcription. A figure this shape is
    * exactly the kind a reader corrects toward a rounder neighbour, which is
    * why it is sourced four ways rather than two.
    *
    * ==The label==
    *
    * ECIP-1066's `Version and Code Name` cell less the subscripted counterpart
    * it carries, which is where every other label on this schedule comes from.
    * At `ethereumclassic/ECIPs` @ `e36ef7f10166769aa3ac469aaf27ba5b0cacb198`
    * (2026-07-05) the cell reads `Phoenix <sub>Istanbul</sub>`.
    * `besu-eth/besu-etc` @ `eb4248c997` states the label without it, as
    * `PHOENIX(true, "Phoenix")` in `HardforkId.ClassicHardforkId`, and the two
    * agree to the character.
    *
    * **That commit's row for this upgrade also carries the height correctly,
    * and [[agharta]] records that its row for the upgrade below carries a
    * figure nothing supports.** Both readings stand: a tabulation can be right
    * in one row and wrong in the next, which is why a label is taken from it
    * here and a height is not.
    *
    * ==Six proposals, and the registry holds three rival sets at this same
    * height==
    *
    * ECIP-1088 § *Specification* lists EIP-152, EIP-1108, EIP-1344, EIP-1884,
    * EIP-2028 and EIP-2200, and [[Upgrades.phoenix]] adopts every one of them
    * and records all six in its component list. Three further documents in that
    * registry propose a fork at this height with a different membership, one of
    * them sharing this one's title stem; every one of them is Withdrawn or
    * Rejected. [[Upgrades.phoenix]] carries them by number and status, because
    * a membership matched on the name and the height reaches the wrong set
    * without failing.
    *
    * **That is a statement about what the composition adopts, and not a
    * conformance claim** -- the same division [[ethereum.Mainnet]] draws at its
    * own entry for the upgrade these six proposals also make up there.
    */
  private val phoenix: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(10500839), upgrade("Phoenix"), Upgrade.RuleChange(Upgrades.phoenix))

  /** Block 11,380,000, gating nothing this build validates.
    *
    * The second [[Upgrade.Unenforced]] entry on this schedule, and the first
    * whose emptiness is a property of the document class rather than of the
    * record. ECIP-1100 -- `ethereumclassic/ECIPs` @ `0adb420` (2026-07-29),
    * `_specs/ecip-1100.md`, `status: Replaced`, `type: Standards Track`,
    * **`category: ECBP`** -- is an
    * Ethereum Classic Best Practice. That category is recommended rather than
    * required, so what it describes is at a client's discretion and no node
    * validates differently for having taken it. **The document says so
    * itself**, in its Specification: *"This specification falls outside of
    * existing consensus protocol."*
    *
    * `ethereumclassic/core-geth` @ `4185df450` states the height as
    * `ECBP1100FBlock: big.NewInt(11_380_000)` in `params/config_classic.go:85`.
    *
    * ==The height is tethered to no upgrade, and that is the strongest evidence
    * for what this entry is==
    *
    * **One field in that configuration carries 11,380,000, and it is this one.**
    * The comparison that gives the figure its weight is [[phoenix]]'s
    * 10,500,839, which **six** fields carry, one per proposal that upgrade
    * adopted. A hard fork gathers proposals at a shared height; this height
    * gathers nothing, sits between two upgrades and belongs to neither, and the
    * upgrade below it had already activated 879,161 blocks earlier.
    *
    * **The same configuration draws the contrast itself, one line down.** The
    * deactivation this network later scheduled is annotated
    * `// ETA 31 Jan 2023 (== Spiral hard fork)` -- bundled into an upgrade,
    * where the activation was not. So a feature that arrived on its own and was
    * retired inside a fork says, in the implementation's own comments, that
    * arriving on its own was the deliberate part.
    *
    * ==Why the entry records the height and says nothing about the behavior==
    *
    * The proposal selects between competing chains that are each already valid,
    * so two nodes disagreeing across this height disagree about which chain to
    * follow and never about whether a block is admissible -- which is what
    * [[Upgrade.RuleChange]] would assert and what [[Upgrade.Unenforced]]
    * denies. The network states the same thing at the peer layer: this height
    * is not among the twelve fork blocks its EIP-2124 identifier is a checksum
    * over, and the reference implementation excludes it by naming scheme rather
    * than by height -- `params/confp/configurator.go:31-36` matches `ECBP` and
    * `EBP` as *"not incompatible with configuration either having or lacking
    * them"*, and `BlockForks` skips what matches.
    *
    * **So the schedule carries when the network scheduled and named this, and
    * nothing about whether a given node runs it.** Whether it runs is an
    * operator setting rather than a property of the chain, it is not observable
    * from the chain, and modeling it here would put a per-node choice in the
    * one structure whose entries are the same for every node on the network.
    *
    * ==The label takes the name and drops the default, as every label here
    * drops its subscript==
    *
    * ECIP-1066 -- `ethereumclassic/ECIPs` @
    * `0adb420` (2026-07-29) -- tabulates the name cell as
    * `MESS Default: On <br> <sub>ECBP-1100</sub>`, against a later
    * `MESS Default: Off <br> <sub>ECBP-1110</sub>`. Every other label on this
    * schedule is its row's name cell less the subscripted part, and this one is
    * that plus one more removal: **the default.**
    *
    * **A default belongs to a client and not to a network.** The two rows read
    * as a pair of defaults precisely because what they record is what client
    * software shipped with, which is not a property of the chain and is not
    * observable from it. This schedule has no member that could hold one, so a
    * label asserting a default would describe something the entry cannot carry
    * and would additionally imply a matching entry for the retraction. What the
    * entry records is that the network scheduled and named MESS at this height.
    *
    * **A different commit is cited here than elsewhere in this file, and the
    * reason is that this row is newer than the rest.** The commit most entries
    * here cite -- `e36ef7f1` (2026-07-05) -- predates it and carries no row for
    * this upgrade at all, so citing that ref for this entry would resolve, for a
    * reader, to a table the row is missing from. The rows were added at
    * `b3bda63a` (2026-07-24).
    */
  private val mess: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(11380000), upgrade("MESS"), Upgrade.Unenforced)

  /** Block 11,700,000.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states it as
    * `ECIP1099FBlock: big.NewInt(11_700_000)` in `params/config_classic.go:87`.
    * `besu-eth/besu-etc` @ `eb4248c997` states it as `"thanosBlock": 11700000`
    * in `config/src/main/resources/classic.json`. **ECIP-1099 states it
    * itself** -- `ETCHASH_FORK_BLOCK := 11_700_000`, annotated `(Epoch 390)` --
    * so unlike [[gotham]] the proposal names its own height and the
    * implementations corroborate rather than supply it. ECIP-1066 at
    * `0adb420` tabulates the name cell as `Thanos <br> <sub>ECIP-1099</sub>`,
    * and the label is that cell less the subscript, as every label here is.
    * **The subscript is the document number rather than a counterpart upgrade
    * on the other network**, which is what most of this schedule's rows carry
    * there -- that network never calibrated an epoch, so there is no
    * counterpart to name.
    *
    * ==A rule change whose rule no state-transition tier can observe==
    *
    * [[Upgrades.thanos]] says what moves and why every facet but the consensus
    * one is [[Upgrades.phoenix]]'s. What makes the entry a
    * [[Upgrade.RuleChange]] rather than the case the entry above it takes is
    * that two nodes disagreeing across this height disagree about whether a
    * block's seal is valid -- the epoch sizes the cache the seal is checked
    * against -- and the proposal says so in its own Specification, which calls
    * the change *"(hardfork required)"* where the document 320,000 blocks below
    * is a recommendation a client may decline.
    *
    * The network states the same thing at the peer layer: this height is one of
    * the twelve fork blocks its EIP-2124 identifier is a checksum over, where
    * 11,380,000 is not.
    */
  private val thanos: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(11700000), upgrade("Thanos"), Upgrade.RuleChange(Upgrades.thanos))

  /** Block 13,189,133.
    *
    * **ECIP-1103 states it** -- `ethereumclassic/ECIPs`, `_specs/ecip-1103.md`
    * @ `8be555c3be366a91af2f000b82d52e672980d3b6`, `status: Final`,
    * `type: Meta` -- naming `13_189_133` for this network beside the two test
    * networks it also schedules. `ethereumclassic/core-geth` @ `4185df450`
    * states it as four per-proposal transitions rather than the fork name, at
    * `params/config_classic.go:90-93`, under the client's own comment
    * `// Berlin eq, aka Magneto`. `besu-eth/besu-etc` @ `eb4248c997` states it
    * as `"magnetoBlock": 13189133` in
    * `config/src/main/resources/classic.json`. ECIP-1066 at `0adb420`
    * tabulates the name cell as `Magneto <br> <sub>Berlin</sub>`, and the label
    * is that cell less the subscripted counterpart, as this schedule's labels
    * are.
    *
    * **The height moved twice before it settled, which is why it is sourced to
    * the specification and the implementations together rather than to the
    * proposal alone.** ECIP-1103's first revision named `12_759_699`; the
    * revision removing EIP-2315 reset all three of its networks to `TBD`; the
    * figure above was set 20 days later. A reading taken from the document's
    * history rather than its head reaches a height this network never ran.
    *
    * ==What this entry does not settle==
    *
    * [[Upgrades.magneto]] says which four proposals compose it and which two
    * facets they move. **That is a statement about what the composition adopts,
    * and not a conformance claim** -- the same division this schedule draws at
    * every entry that names an upstream counterpart.
    */
  private val magneto: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(13189133), upgrade("Magneto"), Upgrade.RuleChange(Upgrades.magneto))

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
    UpgradeSchedule.of(
      Vector(
        frontier,
        frontierThawing,
        homestead,
        gasReprice,
        dieHard,
        gotham,
        defuse,
        atlantis,
        agharta,
        phoenix,
        mess,
        thanos,
        magneto
      )
    )
