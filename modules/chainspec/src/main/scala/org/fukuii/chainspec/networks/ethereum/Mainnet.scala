package org.fukuii.chainspec.networks.ethereum

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, Network, Upgrade, UpgradeId, UpgradeSchedule}

/** Ethereum mainnet: which of [[Upgrades]]'s rule sets it runs, and from when.
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
  * The clones cited are full and untagged, so each is a commit and a date, per
  * `.claude/rules/evidence-and-citation.md` §1.
  *
  * ==What the schedule holds that the rule sets cannot==
  *
  * [[Upgrades]] states this network's rule sets and says outright that it holds
  * no activation and no schedule. This is the other half, and the two are
  * separate files because the halves have different lifetimes: a rule set is a
  * composition another network can reach independently and be asserted equal
  * to, while these numbers are this network's alone and equal nothing. Keeping
  * them apart is what stops a network that runs the same rules from inheriting
  * these activations with them.
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

  /** Chain id 1.
    *
    * EIP-155 § *List of Chain ID's* gives `1 | Ethereum mainnet`
    * (`ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38`,
    * 2026-08-14). Two clients from different language families agree:
    * `ethereum/go-ethereum` @ `6bb0588ad8e7f922e4ad5580f51265a4097af08f`
    * (2026-08-14) declares `ChainID: big.NewInt(1)` in `params/config.go`, and
    * `besu-eth/besu` @ `c2addd94244196d4713e38ea659be0d2581082e9` (2026-08-14)
    * declares `"chainId": 1` in `config/src/main/resources/mainnet.json`.
    *
    * The name is a display concern, per [[Network]]'s own contract, and is not
    * sourced to the same standard as the id.
    */
  val network: Network = Network(UInt64.fromBits(1L), "Ethereum Mainnet")

  private def upgrade(label: String): UpgradeId = UpgradeId.named(network, label)

  /** The rules this network launched with, in force from its first block.
    *
    * ==Block zero, which is what the executable specification says==
    *
    * `ethereum/execution-specs` @
    * `ccaaaba58c748c072ca0ef9a09e91f9e3dcd277a` (2026-08-10) declares it
    * normatively in `src/ethereum/forks/frontier/__init__.py`:
    * `FORK_CRITERIA: ForkCriteria = ByBlockNumber(0)`, where that criterion's
    * own field documents itself as *"Number of the first block in this fork."*
    *
    * **Three controls in the same mechanism rule out an off-by-one**, since a
    * criterion that were systematically one below its documented block would
    * produce this reading by accident. Each of the next three forks declares
    * exactly its documented activation: `homestead` `ByBlockNumber(1150000)`,
    * `dao_fork` `ByBlockNumber(1920000)`, `tangerine_whistle`
    * `ByBlockNumber(2463000)`. So the zero is deliberate.
    *
    * ==Two documentation tables say one, and one of them is this repository's==
    *
    * `ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38`, EIP-6953 §
    * *Proof-of-Work Network Upgrades*, tabulates `Frontier | 1`, and
    * execution-specs' own `docs/specs/protocol_history.md` gives the same
    * figure at the commit above. **That row links, as its fork manifest, to the
    * very file declaring `ByBlockNumber(0)`** -- so the disagreement is inside
    * one repository, between its prose and its executable artifact, and this
    * entry follows the artifact.
    *
    * What the tables answer is *when did this network start producing blocks
    * under these rules*, alongside a release date; a schedule answers *what
    * rules are in force at a height*, and it is asked at height zero. No client
    * carries the tabulated figure either: neither of the clients cited on the
    * entries below has any Frontier activation field at all, because Frontier is
    * what applies when nothing else has, and `ethereum/go-ethereum` @
    * `6bb0588ad8e7f922e4ad5580f51265a4097af08f` gives block 0 and block
    * 1,149,999 one fork identifier, `0xfc64ec04`, labelling the second *"Last
    * Frontier block"* (`core/forkid/forkid_test.go`).
    *
    * [[UpgradeSchedule.of]] would refuse the tabulated figure outright, since a
    * schedule starting at block one cannot answer for block zero.
    */
  private val frontier: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(0), upgrade("Frontier"), Upgrade.RuleChange(Upgrades.frontier))

  /** Block 200,000, gating nothing.
    *
    * ==The one activation here with no implementation to check it against==
    *
    * There is no Hardfork Meta EIP for it. Two documents state the figure and
    * both are documentary: `ethereum/EIPs` @
    * `9c915ee494c05069945f4e1018fa0854e2d3fb38`, EIP-6953 (Final, Informational)
    * tabulates `Frontier Thawing | 200000`, and `ethereum/execution-specs` @
    * `ccaaaba58c748c072ca0ef9a09e91f9e3dcd277a`, `docs/specs/protocol_history.md`
    * gives the same figure with a 2015-09-07 release date. **No client supplies
    * a third reading, because no client carries the value**: it appears in no
    * chain configuration in go-ethereum, in `go-ethereum-pow` at `v1.10.26` or
    * in core-geth, and besu does not name the upgrade anywhere at all.
    *
    * ==Why it is on the schedule when it gates nothing==
    *
    * Because the canonical enumeration has it, and an enumeration that omits it
    * misnumbers everything after it. Both `ethereum/go-ethereum` @
    * `6bb0588ad8e7f922e4ad5580f51265a4097af08f` and
    * `ethereumclassic/core-geth` @ `4185df450364973bbf99efa3923791f5ba40b351`
    * (2025-01-23) carry `FrontierThawing` between `Frontier` and `Homestead` in
    * an `iota` constant block in `params/forks/forks.go`, and in both trees that
    * file is the only one that mentions it.
    *
    * ==What it changed, and why that is not a rule==
    *
    * The block gas limit, which a miner sets per block within adjustment bounds
    * the protocol fixes. Those bounds are unchanged by it -- go-ethereum's
    * `params/protocol_params.go` states `MinGasLimit = 5000` and
    * `GasLimitBoundDivisor = 1024`, and this network's own genesis block sets
    * `GasLimit: 5000`, the floor. Participants raised it; nothing began
    * validating differently. That is [[Upgrade.Unenforced]], and it is why this
    * entry does not reach [[UpgradeSchedule.forkPoints]].
    */
  private val frontierThawing: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(200000), upgrade("Frontier Thawing"), Upgrade.Unenforced)

  /** Block 1,150,000.
    *
    * `ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38`, EIP-606
    * *Hardfork Meta: Homestead* (Final): *"Block >= 1,150,000 on Mainnet"*.
    * `besu-eth/besu` @ `c2addd94244196d4713e38ea659be0d2581082e9` implements it
    * as `"homesteadBlock": 1150000` in `config/src/main/resources/mainnet.json`
    * -- a client in a different language family from the specification's other
    * implementations, and independent of it.
    *
    * **EIP-606 includes EIP-8, which is not here.** It is devp2p forward
    * compatibility rather than a rule the machine runs, so the EVM facet of
    * [[Upgrades.homestead]] is EIP-2 and EIP-7 only. This entry names the
    * network's upgrade; it does not claim to implement all of it.
    */
  private val homestead: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(1150000), upgrade("Homestead"), Upgrade.RuleChange(Upgrades.homestead))

  /** Block 1,920,000.
    *
    * `ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38`, EIP-779
    * *Hardfork Meta: DAO Fork* (Final): *"Block == 1,920,000 on Mainnet"*.
    * `ethereum/execution-specs` @ `ccaaaba58c748c072ca0ef9a09e91f9e3dcd277a`
    * states it executably as `ByBlockNumber(1920000)` in
    * `src/ethereum/forks/dao_fork/__init__.py`. Two clients from different
    * language families implement it: `ethereum/go-ethereum` @
    * `6bb0588ad8e7f922e4ad5580f51265a4097af08f` as
    * `DAOForkBlock: big.NewInt(1_920_000)` in `params/config.go`, and
    * `besu-eth/besu` @ `c2addd94244196d4713e38ea659be0d2581082e9` as
    * `"daoForkBlock": 1920000` in `config/src/main/resources/mainnet.json`.
    *
    * ==What this entry states, and what nothing here performs==
    *
    * That the upgrade activated at this block, and that validity can diverge
    * across it. [[Upgrade.IrregularStateChange]] carries no payload, so the
    * transfer itself is not modeled: EIP-779 moves the ether in a list of
    * accounts into one recipient at the beginning of this block, which is work
    * for a layer that processes a block rather than a transaction. **The entry
    * is a description of this network's history and not a claim about what this
    * build executes**, which is the standing [[frontierThawing]] has too. The
    * two part company at [[UpgradeSchedule.forkPoints]], which this one reaches
    * and that one does not.
    *
    * ==It also carries a rule, and the proposal's own summary says it does
    * not==
    *
    * EIP-779 opens by stating that *"all EVM opcodes, transaction format, block
    * structure, and so on remained the same"*, and its § *Specification* then
    * requires every block in `[1_920_000, 1_920_009]` to carry `dao-hard-fork`
    * in `extraData`. Both clients cited above enforce that: go-ethereum in
    * `consensus/misc/dao.go`, whose own comment calls it a *"DAO hard-fork
    * extension to the header validity"*, and besu by swapping its block-header
    * validator for `createDaoValidator()` across the same ten blocks.
    *
    * It is a header rule, and [[org.fukuii.chainspec.UpgradeRules]] holds no
    * header facet, so nothing here can express it and no facet of the rules in
    * force changes at this block. That is what keeps the case above the
    * accurate one rather than [[Upgrade.RuleChange]].
    *
    * ==One entry, where besu writes three==
    *
    * besu adds a milestone at this block, another at the block after it, and
    * the preceding specification again at plus ten, because the transfer lasts
    * one block and the header rule lasts ten. **None of that reaches a fork
    * identifier**: its `getForkBlockNumbers` takes `getDaoForkBlock` once, and
    * go-ethereum gathers the one configuration field, so both clients put a
    * single point at 1,920,000 and neither puts one after it.
    */
  private val daoFork: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(1920000), upgrade("DAO Fork"), Upgrade.IrregularStateChange)

  /** Block 2,463,000.
    *
    * `ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38`, EIP-608
    * *Hardfork Meta: Tangerine Whistle* (Final): *"Block >= 2,463,000 on
    * Mainnet"*. `besu-eth/besu` @ `c2addd94244196d4713e38ea659be0d2581082e9`
    * implements it as `"eip150Block": 2463000` in the same file -- under the
    * proposal's number rather than the fork's name, which is
    * [[org.fukuii.chainspec.ProposalId]]'s point made by a client.
    */
  private val tangerineWhistle: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(
      atBlock(2463000),
      upgrade("Tangerine Whistle"),
      Upgrade.RuleChange(Upgrades.tangerineWhistle)
    )

  /** Block 2,675,000.
    *
    * `ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38`, EIP-607
    * *Hardfork Meta: Spurious Dragon* (Final): *"Block >= 2,675,000 on
    * Mainnet"*. `ethereum/execution-specs` @
    * `ccaaaba58c748c072ca0ef9a09e91f9e3dcd277a` states it executably as
    * `ByBlockNumber(2675000)` in `src/ethereum/forks/spurious_dragon/__init__.py`.
    * Two clients from different language families implement it:
    * `ethereum/go-ethereum` @ `6bb0588ad8e7f922e4ad5580f51265a4097af08f` as
    * `EIP155Block: big.NewInt(2_675_000)` and `EIP158Block:
    * big.NewInt(2_675_000)` in `params/config.go`, and `besu-eth/besu` @
    * `c2addd94244196d4713e38ea659be0d2581082e9` as `"eip158Block": 2675000` in
    * `config/src/main/resources/mainnet.json`.
    *
    * ==This entry carries all four proposals the upgrade names==
    *
    * [[Upgrades.spuriousDragon]] adopts EIP-155, EIP-160, EIP-161 and EIP-170,
    * and records all four in its component list. EIP-607 names exactly those
    * four, so the caveat [[homestead]] carries -- an entry naming a network
    * upgrade whose rule set is only part of it -- is not one this entry needs.
    *
    * **That is a statement about what the composition adopts, and not a
    * conformance claim.** Which proposals a rule set is built from is checkable
    * from this file; whether each is implemented correctly is what the
    * published corpora answer, read at this network's own activation.
    *
    * ==go-ethereum's field name for this activation is the pre-renumbering
    * one==
    *
    * Its `EIP158Block` gates EIP-161, which supersedes EIP-158 as that
    * document's *"invariant-preserving alternative"*; EIP-607 lists 161 and
    * not 158. besu carries the same spelling in its genesis key and binds
    * `spuriousDragonDefinition` to it. Recorded because the number in the
    * clients' name for this block is the one document the upgrade does not
    * include.
    */
  private val spuriousDragon: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(
      atBlock(2675000),
      upgrade("Spurious Dragon"),
      Upgrade.RuleChange(Upgrades.spuriousDragon)
    )

  /** Block 4,370,000.
    *
    * `ethereum/EIPs` @ `dbfa6bee8329650969b95080f23f7059c015c2ba` (2026-08-26),
    * EIP-609 *Hardfork Meta: Byzantium* (Final): *"Block >= 4,370,000 on
    * Mainnet"*. `ethereum/execution-specs` @
    * `20f7f6271a720091e5fea0a82e7bc802866ae36a` (2026-08-26) states it
    * executably as `ByBlockNumber(4370000)` in
    * `src/ethereum/forks/byzantium/__init__.py`, where its neighbours declare
    * their own documented activations -- 2,675,000 below it and 7,280,000 above
    * -- so the figure is not a criterion offset by a constant. Two clients from
    * different language families implement it: `ethereum/go-ethereum` @
    * `e9e35a42f8213235da1fde4f9ac8f3e9ff666b87` (2026-08-26) as
    * `ByzantiumBlock: big.NewInt(4_370_000)` in `params/config.go`, and
    * `besu-eth/besu` @ `fdf1247c6d6431f0325a123ada37086ded17ce7e` (2026-08-26)
    * as `"byzantiumBlock": 4370000` in
    * `config/src/main/resources/mainnet.json`.
    *
    * ==This entry carries all nine proposals the upgrade names==
    *
    * EIP-609 § *Included EIPs* lists EIP-100, EIP-140, EIP-196, EIP-197,
    * EIP-198, EIP-211, EIP-214, EIP-649 and EIP-658, and
    * [[Upgrades.byzantium]] adopts every one of them and records all nine in
    * its component list. So the caveat [[homestead]] carries -- an entry naming
    * a network upgrade whose rule set is only part of it -- is not one this
    * entry needs either, which is the standing [[spuriousDragon]] already has.
    *
    * **That is a statement about what the composition adopts, and not a
    * conformance claim.** Which proposals a rule set is built from is checkable
    * from this file; whether each is implemented correctly is what the
    * published corpora answer, read at this network's own activation.
    *
    * ==The two names this network gives this upgrade are both in the
    * specification==
    *
    * EIP-609 records *"Codename: Byzantium"* and, on the line below,
    * *"Aliases: Metropolis/Byzantium, Metropolis part 1"*. The label here is
    * the codename, which is what both clients cited above spell in their own
    * configuration keys.
    */
  private val byzantium: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(4370000), upgrade("Byzantium"), Upgrade.RuleChange(Upgrades.byzantium))

  /** Constantinople, at 7,280,000 -- and PETERSBURG IS AT THE SAME BLOCK.
    *
    * ==Two entries, one height, and the order below is load-bearing==
    *
    * `UpgradeSchedule.at` folds over the entries that have activated and keeps
    * the last rule change, so at 7,280,000 both of these have activated and
    * **the one written second is the one in force**. That is EIP-1716's own
    * rule -- *"If `Petersburg` and `Constantinople` are applied at the same
    * block, `Petersburg` takes precedence: with the net effect of EIP-1283
    * being disabled"* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-1716.md`,
    * Final).
    *
    * **Reversing these two lines would leave this network running EIP-1283 for
    * ever, and nothing about either rule set would look wrong.** `MainnetSpec`
    * pins the order in both directions, because a schedule is the only place
    * that mistake is visible.
    *
    * ==Both heights, from clients that state them separately==
    *
    * `ethereum/go-ethereum` @ `e9e35a42f8` sets `ConstantinopleBlock:
    * big.NewInt(7_280_000)` and `PetersburgBlock: big.NewInt(7_280_000)` on
    * consecutive lines of `params/config.go`. `erigontech/erigon` @
    * `776a380b1a` carries the same pair in
    * `execution/chain/spec/chainspecs/mainnet.json`.
    *
    * **Three of six independent lineages model ONE fork here instead**, and
    * that is a real disagreement rather than an oversight: besu's shipped
    * `mainnet.json` carries `petersburgBlock` and no `constantinopleBlock` at
    * all, nethermind resolves its Constantinople block to null, and
    * `ethereum/execution-specs` has no `petersburg` fork package and says in
    * prose that it *"omits the whole awkward situation"*. This schedule follows
    * the clients that shipped the two forks in sequence, which is also what
    * keeps the specified-but-never-run rule set expressible.
    *
    * ==One fork point, not two==
    *
    * `UpgradeSchedule.forkPoints` de-duplicates, so EIP-2124 sees 7,280,000
    * once. Four production clients de-duplicate at the same place for the same
    * reason -- go-ethereum, erigon, besu and reth all do it explicitly -- and
    * getting it wrong is silent: the checksum is still a number and every peer
    * rejects it.
    */
  private val constantinople: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(7280000), upgrade("Constantinople"), Upgrade.RuleChange(Upgrades.constantinople))

  /** Petersburg, at the same 7,280,000, removing EIP-1283 before it ever ran.
    *
    * **This entry must stay BELOW [[constantinople]]** -- see that entry's note
    * for why, and `MainnetSpec` for the assertion that holds it there.
    *
    * The label is the codename EIP-1716 gives itself and the field name five
    * clients use. It is deliberately NOT the conformance corpora's spelling,
    * which is `ConstantinopleFix`; `UpgradeId` is this network's word for the
    * upgrade and a corpus label is a different thing.
    */
  private val petersburg: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(7280000), upgrade("Petersburg"), Upgrade.RuleChange(Upgrades.petersburg))

  /** Block 9,069,000.
    *
    * `ethereum/EIPs` @ `dbfa6bee8329650969b95080f23f7059c015c2ba` (2026-08-26),
    * EIP-1679 *Hardfork Meta: Istanbul* (Final), § *Activation*: *"`Block >=
    * 9,069,000` on the Ethereum Mainnet"*. `ethereum/execution-specs` @
    * `20f7f6271a720091e5fea0a82e7bc802866ae36a` (2026-08-26) states it
    * executably as `ByBlockNumber(9069000)` in
    * `src/ethereum/forks/istanbul/__init__.py`, where its neighbours declare
    * their own documented activations -- 7,280,000 below it and 9,200,000 above
    * -- so the figure is not a criterion offset by a constant. Two clients from
    * different language families implement it: `ethereum/go-ethereum` @
    * `e9e35a42f8213235da1fde4f9ac8f3e9ff666b87` (2026-08-26) as
    * `IstanbulBlock: big.NewInt(9_069_000)` in `params/config.go`, and
    * `besu-eth/besu` @ `fdf1247c6d6431f0325a123ada37086ded17ce7e` (2026-08-26)
    * as `"istanbulBlock": 9069000` in
    * `config/src/main/resources/mainnet.json`.
    *
    * ==This entry carries all six proposals the upgrade names==
    *
    * EIP-1679 § *Included EIPs* lists EIP-152, EIP-1108, EIP-1344, EIP-1884,
    * EIP-2028 and EIP-2200, and [[Upgrades.istanbul]] adopts every one of them
    * and records all six in its component list. So the caveat [[homestead]]
    * carries -- an entry naming a network upgrade whose rule set is only part
    * of it -- is not one this entry needs either.
    *
    * **That is a statement about what the composition adopts, and not a
    * conformance claim.** Which proposals a rule set is built from is checkable
    * from this file; whether each is implemented correctly is what the
    * published corpora answer, read at this network's own activation.
    *
    * ==The proposal's header names a seventh, and adopting it here would be
    * adopting it twice==
    *
    * The frontmatter reads `requires: 152, 1108, 1344, 1716, 1884, 2028, 2200`.
    * EIP-1716 is [[petersburg]]'s own meta proposal, already adopted at the
    * entry above. *Included EIPs* is the membership statement and `requires:`
    * is a dependency list, which is a distinction this document makes and a
    * reader is not obliged to notice.
    *
    * ==Another network took all six together too, at its own height and under
    * its own name==
    *
    * `ethereumclassic/core-geth` @
    * `4185df450364973bbf99efa3923791f5ba40b351` (2025-01-23) sets all six
    * transitions at 10,500,839 in `params/config_classic.go`, under a comment
    * naming ECIP-1088. That is a different number and a different label for the
    * same six documents, which is what keeps an activation a network's own fact
    * rather than a property of the rule set -- and it is why
    * [[org.fukuii.chainspec.networks.ethereumclassic.Upgrades]] would reach
    * these rules by composing the same proposals rather than by naming this.
    */
  private val istanbul: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(9069000), upgrade("Istanbul"), Upgrade.RuleChange(Upgrades.istanbul))

  /** Block 9,200,000.
    *
    * `ethereum/EIPs` @ `dbfa6bee8329650969b95080f23f7059c015c2ba` (2026-08-26),
    * EIP-2387 *Hardfork Meta: Muir Glacier* (Final), § *Activation*: *"`Block >=
    * 9,200,000` on the Ethereum mainnet"*. `ethereum/execution-specs` @
    * `20f7f6271a720091e5fea0a82e7bc802866ae36a` (2026-08-26) states it executably
    * as `ByBlockNumber(9200000)` in
    * `src/ethereum/forks/muir_glacier/__init__.py`, where its neighbours declare
    * their own documented activations -- 9,069,000 below it and 12,244,000 above
    * -- so the figure is not a criterion offset by a constant. Two clients from
    * different language families implement it: `ethereum/go-ethereum` @
    * `e9e35a42f8213235da1fde4f9ac8f3e9ff666b87` (2026-08-26) as
    * `MuirGlacierBlock: big.NewInt(9_200_000)` in `params/config.go`, and
    * `besu-eth/besu` @ `fdf1247c6d6431f0325a123ada37086ded17ce7e` (2026-08-26) as
    * `"muirGlacierBlock": 9200000` in `config/src/main/resources/mainnet.json`.
    *
    * ==This entry carries the one proposal the upgrade names==
    *
    * EIP-2387 § *Included EIPs* lists EIP-2384 and nothing else, and
    * [[Upgrades.muirGlacier]] adopts exactly it. So the caveat [[homestead]]
    * carries -- an entry naming a network upgrade whose rule set is only part of
    * it -- is not one this entry needs.
    *
    * ==The upgrade above this one inherits its figure rather than restating it==
    *
    * That is what makes this entry load-bearing rather than a completeness item:
    * an upgrade composed from [[Upgrades.istanbul]] instead would run a bomb
    * delay of 5,000,000 where the network runs 9,000,000, and no state-fixture
    * tier settles a header, so nothing this build certifies against would
    * disagree.
    *
    * ==The label is this network's own and no corpus spells it==
    *
    * The published difficulty vectors key this document's cases on `Berlin`,
    * under a directory named `dfEIP2384`, because the delay carries forward
    * unchanged. `UpgradeId` is this network's word for what it released here;
    * a corpus label is a different thing, exactly as it is at [[petersburg]].
    */
  private val muirGlacier: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(9200000), upgrade("Muir Glacier"), Upgrade.RuleChange(Upgrades.muirGlacier))

  /** Block 12,244,000.
    *
    * Stated by two of the four proposals this upgrade carries rather than by a
    * document of its own: `ethereum/EIPs` @
    * `dbfa6bee8329650969b95080f23f7059c015c2ba` (2026-08-26) gives
    * `FORK_BLOCK | 12244000` in the Parameters table of both `EIPS/eip-2929.md`
    * and `EIPS/eip-2930.md` (both Final). `ethereum/execution-specs` @
    * `20f7f6271a720091e5fea0a82e7bc802866ae36a` (2026-08-26) states it executably
    * as `ByBlockNumber(12244000)` in `src/ethereum/forks/berlin/__init__.py`,
    * where its neighbours declare their own documented activations -- 9,200,000
    * below it -- so the figure is not a criterion offset by a constant. Two
    * clients from different language families implement it:
    * `ethereum/go-ethereum` @ `e9e35a42f8213235da1fde4f9ac8f3e9ff666b87`
    * (2026-08-26) as `BerlinBlock: big.NewInt(12_244_000)` in
    * `params/config.go`, and `besu-eth/besu` @
    * `fdf1247c6d6431f0325a123ada37086ded17ce7e` (2026-08-26) as
    * `"berlinBlock": 12244000` in `config/src/main/resources/mainnet.json`.
    *
    * ==This entry carries four proposals and no meta document names them==
    *
    * [[Upgrades.berlin]] holds the membership and how it was established,
    * including the fifth proposal that was in this upgrade and was removed
    * before it ran. The caveat [[homestead]] carries -- an entry naming a
    * network upgrade whose rule set is only part of it -- is not one this entry
    * needs, but the reason is different from [[muirGlacier]]'s: there the meta
    * document settles it, and here five client and specification readings do.
    *
    * ==The first activation on this network at which a new transaction format
    * becomes valid==
    *
    * Every entry above changes what the machine does or what a header must
    * satisfy. This one also changes what a block may CARRY, so a node at these
    * rules accepts a block an earlier one would reject on its transactions alone.
    */
  private val berlin: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(12244000), upgrade("Berlin"), Upgrade.RuleChange(Upgrades.berlin))

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
    * ==[[UpgradeSchedule.forkPoints]] over this schedule is this network's own,
    * as far as the enumeration reaches==
    *
    * EIP-2124's worked example for this network runs `uint64(1150000)` then
    * `uint64(1920000)`, and [[daoFork]] is what makes the second of those
    * appear. A schedule that omitted it would still yield points, still produce
    * an identifier and still be a number every peer accepts the shape of --
    * and every peer would reject the value, in a way that reads as unrelated
    * network trouble rather than as a missing entry.
    */
  val schedule: Either[UpgradeSchedule.Error, UpgradeSchedule] =
    UpgradeSchedule.of(
      Vector(
        frontier,
        frontierThawing,
        homestead,
        daoFork,
        tangerineWhistle,
        spuriousDragon,
        byzantium,
        constantinople,
        petersburg,
        istanbul,
        muirGlacier,
        berlin
      )
    )
