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

  /** A timestamp stated as a literal from a specification.
    *
    * Separate from [[atBlock]] rather than a second call to one helper, because
    * the two quantities are unrelated and [[Activation]] keeps the axis in the
    * value precisely so that a transposition is a type error rather than a
    * plausible number.
    */
  private def atTimestamp(seconds: Long): Activation = Activation.AtTimestamp(UInt64.fromBits(seconds))

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

  /** Block 12,965,000.
    *
    * `ethereum/execution-specs` @
    * `20f7f6271a720091e5fea0a82e7bc802866ae36a` (2026-08-26) states it
    * executably in `src/ethereum/forks/london/__init__.py`, whose upgrade
    * schedule gives `| Mainnet | 12,965,000 | August 5, 2021 |`, and its
    * neighbours declare their own documented activations -- 12,244,000 below it
    * -- so the figure is not a criterion offset by a constant. Three clients
    * from three language families implement it: `ethereum/go-ethereum` @
    * `e9e35a42f8213235da1fde4f9ac8f3e9ff666b87` (2026-08-26) as
    * `LondonBlock: big.NewInt(12_965_000)` in `params/config.go`;
    * `besu-eth/besu` @ `fdf1247c6d6431f0325a123ada37086ded17ce7e` (2026-08-26)
    * as `"londonBlock": 12965000` in `config/src/main/resources/mainnet.json`;
    * and `ethereumclassic/core-geth` @ `4185df450364973bbf99efa3923791f5ba40b351`
    * (2025-01-23), which states the height five times rather than once, one per
    * proposal, in `params/confp/testdata/coregeth_foundation.json`.
    *
    * ==That last reading is what makes the membership checkable by height==
    *
    * A configuration keyed per proposal answers *which proposals activate here*
    * without consulting any document that names the upgrade, so it is an
    * instrument independent of the meta document [[Upgrades.london]] rests on.
    * It returns exactly five at this height.
    *
    * ==Nothing activates between this entry and the one below it==
    *
    * Checked rather than assumed, because the two entries preceding this one on
    * this network were each planned as a single upgrade and each grew under
    * survey. The instrument is every block-number-valued field in a client's
    * mainnet configuration, including fields not named after an upgrade --
    * which is what the two misses had in common. Calibrated in both directions
    * before its zero was believed: the same sweep finds an upgrade that appears
    * in one client only as a difficulty-delay key and no proposal transition,
    * and finds two Ethereum Classic events that no fork identifier reports at
    * all. Six client lineages agree there is nothing in this window.
    *
    * ==The first activation on this network whose rules reach the header==
    *
    * [[berlin]] changed what a block may carry. This one changes what a block's
    * header must SAY -- it must state the charge its parent's rules require,
    * and a header below this height must state none.
    */
  private val london: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(atBlock(12965000), upgrade("London"), Upgrade.RuleChange(Upgrades.london))

  /** Arrow Glacier, block 13,773,000.
    *
    * ==Four independent statements of the height, from four codebases==
    *
    * `ethereum/execution-specs` @ `20f7f6271` (2026-08-26) states it twice over
    * in one module: `src/ethereum/forks/arrow_glacier/__init__.py` carries the
    * upgrade schedule `| Mainnet | 13,773,000 | December 8, 2021 |` in prose and
    * `FORK_CRITERIA: ForkCriteria = ByBlockNumber(13773000)` as the executable
    * criterion beneath it. `ethereum/go-ethereum` @ `e9e35a42f` (2026-08-26)
    * has `ArrowGlacierBlock: big.NewInt(13_773_000)` in `params/config.go`;
    * `besu-eth/besu` @ `fdf1247c6` (2026-08-26) has `"arrowGlacierBlock":
    * 13773000` in `config/src/main/resources/mainnet.json`; and
    * `ethereumclassic/core-geth` @ `4185df450` (2025-01-23) carries the same
    * figure on its Ethereum mainnet configuration, which is the reading that
    * matters least here and is kept because it is the client this build reads
    * for the other network.
    *
    * ==Nothing activates between this entry and the one below it==
    *
    * Checked with the instrument the two misses on this ladder had in common --
    * EVERY numeric field in a client's mainnet configuration, not the fields
    * named after an upgrade. Three clients read whole, and the window above
    * London holds this height and the next and nothing else.
    *
    * The sweep is calibrated in three directions before its zero is read: it
    * captures each configuration block to its closing brace rather than
    * truncating; it returns non-fork-named fields where those exist, which is
    * the exact shape a miss would take; and run against a window known to hold
    * three upgrades it returns those three.
    *
    * ==The rules across this boundary differ in ONE figure==
    *
    * Which is what a delay is. [[Upgrades.arrowGlacier]] is [[Upgrades.london]]
    * with one consensus field replaced, so every machine, execution and
    * admission rule is the same value on both sides and only a header's
    * difficulty can disagree.
    */
  private val arrowGlacier: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(
      atBlock(13773000),
      upgrade("Arrow Glacier"),
      Upgrade.RuleChange(Upgrades.arrowGlacier)
    )

  /** Gray Glacier, block 15,050,000.
    *
    * ==The same four statements, at the same refs==
    *
    * `src/ethereum/forks/gray_glacier/__init__.py` gives `| Mainnet |
    * 15,050,000 | June 29, 2022 |` and `ByBlockNumber(15050000)`;
    * `GrayGlacierBlock: big.NewInt(15_050_000)` in go-ethereum;
    * `"grayGlacierBlock": 15050000` in besu; the same figure in core-geth.
    *
    * ==The last height on this network at which a rule set is reached by mining==
    *
    * The upgrade above this one is a transition to proof-of-stake, so this is
    * the boundary past which the difficulty figure this entry moves stops being
    * something a miner competes over. Stated here because a reader arriving at
    * the end of a run of bomb delays would reasonably expect a seventh, and the
    * reason there is none is not that the mechanism was repealed.
    *
    * ==Nothing activates between this entry and the one below it==
    *
    * The same three-client sweep, over the same window, which covers both
    * glaciers in one reading.
    */
  private val grayGlacier: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(
      atBlock(15050000),
      upgrade("Gray Glacier"),
      Upgrade.RuleChange(Upgrades.grayGlacier)
    )

  /** Paris, block 15,537,394.
    *
    * ==This height is a RETROSPECTIVE reading, and every other on this ladder is
    * not==
    *
    * Each activation above was agreed in advance and published as a number. This
    * one was not: the trigger was a condition on accumulated work, so no
    * participant could state the height until the chain had reached it.
    * [[Upgrade.RetrospectiveRuleChange]] carries what that costs -- the height is
    * real, `UpgradeSchedule.at` honors it, and EIP-2124's fork identifier must
    * not count it -- and that case's documentation holds the evidence rather than
    * this entry.
    *
    * ==Where the number comes from, and the two nearby figures that are not it==
    *
    * `ethereum/execution-specs` @ `20f7f6271` (2026-08-26) states it as the
    * executable criterion in `src/ethereum/forks/paris/__init__.py:43`,
    * `FORK_CRITERIA: ForkCriteria = ByBlockNumber(15537394)`, under a comment
    * recording that the trigger was the accumulated work reaching a terminal
    * value and that the event *"is now a historical event"*.
    *
    * **A neighboring figure is one below and is a different client's index, not
    * this height.** `NethermindEth/nethermind` @ `b92e2a471` (2026-08-26) carries
    * `ParisBlockNumber = 15_537_393` in
    * `Nethermind.Specs/MainnetSpecProvider.cs:24`, with `postMergeBlock:
    * ParisBlockNumber + 1` at `:75` recovering this height from it. That client
    * indexes its own spec ladder at the last mined block deliberately; its own
    * source explains why, and `org.fukuii.chainspec.proposals.eip.Eip3675`
    * records that the arrangement is that client's and not the document's. **A
    * schedule copying 15,537,393 would put post-merge rules over a block that was
    * mined**, which is a chain split at exactly one block and is invisible to any
    * fixture that does not straddle it.
    *
    * ==Two further statements of 15,537,394, of two different kinds==
    *
    * `erigontech/erigon` @ `776a380b1` (2026-08-26) states it as a schedule
    * field, `"mergeBlock": 15537394` in
    * `execution/chain/spec/chainspecs/mainnet.json`. `besu-eth/besu` @
    * `fdf1247c6` (2026-08-26) states it as something else entirely and is worth
    * keeping for that reason: `config/src/main/resources/mainnet.json` carries a
    * `checkpoint` block whose `"number": 15537394` sits under the comment *"Start
    * sync from first proof of stake block"*. That is a sync origin rather than a
    * fork activation, so it is independent of any fork ladder and still names
    * this height as the first block produced the new way.
    *
    * **`mergeBlock` is NOT `mergeNetsplitBlock`, and conflating them is the
    * available mistake here.** The second is EIP-3675's `FORK_NEXT_VALUE`, the
    * virtual fork a network configures at a knowable height so peers split there
    * rather than at a transition nobody can predict. Erigon's own `sepolia.json`
    * carries both and they differ -- `"mergeBlock": 1450409` against
    * `"mergeNetsplitBlock": 1735371`, **284,962 blocks apart** -- so a reader
    * taking one key for the other reads the right figure on this network and the
    * wrong one on the next. [[UpgradeSchedule.forkPoints]] records that this
    * build does not model the virtual fork and what would bring it in.
    *
    * ==go-ethereum states no height for it at all, and that is the corroboration
    * it can offer==
    *
    * `ethereum/go-ethereum` @ `e9e35a42f` (2026-08-26) runs
    * `GrayGlacierBlock: big.NewInt(15_050_000)` straight into
    * `TerminalTotalDifficulty` and then into `ShanghaiTime` in `params/config.go`,
    * with no block-numbered field for this transition anywhere between them, and
    * `MergeNetsplitBlock` nil on this network. **So it is not a witness to the
    * figure**, and the absence is the point: that client derives its fork
    * identifier by reflecting over block-numbered configuration fields, and
    * [[Upgrade.RetrospectiveRuleChange]] rests partly on there being no such field
    * to reflect over. The reading that would be wrong is to record geth as
    * agreeing with the height.
    *
    * ==Nothing activates between this entry and the one above it==
    *
    * Checked with the instrument the two misses on this ladder had in common --
    * EVERY numeric field in a client's mainnet configuration, not the fields
    * named after an upgrade -- over the window from 15,050,000 to this height,
    * exclusive at both ends, in go-ethereum, erigon and besu. The window is
    * empty in all three.
    *
    * **The zero is calibrated before it is read.** Run unchanged over
    * 12,244,000 to 15,050,000, which is known to hold two upgrades, the same
    * instrument returns 12,965,000 and 13,773,000 from each of the three -- so it
    * discriminates, and an empty window above is a reading rather than a silence.
    *
    * ==The last entry activating by block number on this network==
    *
    * Every upgrade above this one activates at a timestamp, which
    * `UpgradeSchedule.Error.TimestampBeforeBlock` is what enforces the ordering
    * of. Stated here because this entry is where the axis changes, and a reader
    * adding the next one needs to know that before writing its activation rather
    * than after.
    */
  private val paris: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(
      atBlock(15537394),
      upgrade("Paris"),
      Upgrade.RetrospectiveRuleChange(Upgrades.paris)
    )

  /** Shanghai, timestamp 1,681,338,455.
    *
    * ==THE FIRST ENTRY ON ANY NETWORK IN THIS BUILD THAT ACTIVATES ON A
    * TIMESTAMP==
    *
    * [[Activation.AtTimestamp]] has existed since that type was written and has
    * had no authored consumer until this line. So this entry is the first real
    * exercise of three things that were previously carried only by that type's
    * own tests: the axis being part of the value, the cross-axis ordering rule,
    * and [[UpgradeSchedule.at]] reading a timestamp at all.
    *
    * ==The ordering against the entry below it is a specification MUST, not a
    * consequence of the numbers==
    *
    * 1,681,338,455 is larger than 15,537,394, so a schedule comparing bare
    * integers would accept this entry for the wrong reason and would go on
    * accepting it right up until a network configured a fork at a low
    * timestamp. EIP-6122 § Additional rules is what actually governs: *"Forks by
    * timestamp MUST be scheduled at or after the forks by block (on mainnet as
    * well as on private networks)."* `UpgradeSchedule.Error.TimestampBeforeBlock`
    * is that rule, and this entry is the first thing to satisfy it non-vacuously.
    *
    * ==Four independent statements of the figure==
    *
    * `ethereum/execution-specs` @ `20f7f6271` (2026-08-26) states it twice in one
    * module: `src/ethereum/forks/shanghai/__init__.py` gives the schedule row
    * `| Mainnet | 1681338455 | 2023-04-12 22:27:35 |` and
    * `FORK_CRITERIA: ForkCriteria = ByTimestamp(1681338455)` beneath it.
    * `ethereum/go-ethereum` @ `e9e35a42f` (2026-08-26) has
    * `ShanghaiTime: newUint64(1681338455)` in `params/config.go`;
    * `besu-eth/besu` @ `fdf1247c6` (2026-08-26) has `"shanghaiTime": 1681338455`
    * in `config/src/main/resources/mainnet.json`; and
    * `ethereumclassic/core-geth` @ `4185df450` (2025-01-23) carries the same
    * figure on its Ethereum mainnet configuration.
    *
    * ==Nothing activates between this entry and the one below it==
    *
    * The same instrument the glaciers were checked with -- every numeric field
    * in three clients' mainnet configurations, not the fields named after an
    * upgrade. **Note what that sweep has to span here**: the window runs from a
    * block number to a timestamp, so a reading that compared them as one
    * quantity would have nothing to report and would be right by accident. The
    * fields between are read on their own axes, and there are none.
    *
    * ==This one DOES reach the fork identifier, unlike the entry below it==
    *
    * A [[Upgrade.RuleChange]] rather than the retrospective case: this
    * activation was published in advance and every peer could compute it before
    * reaching it, which is exactly the property the case below lacks. The two
    * entries sitting next to each other, taking different cases for that reason,
    * is the clearest statement of what separates them.
    */
  private val shanghai: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(
      atTimestamp(1681338455),
      upgrade("Shanghai"),
      Upgrade.RuleChange(Upgrades.shanghai)
    )

  /** Cancun, timestamp 1,710,338,135.
    *
    * ==Four independent statements of the figure==
    *
    * `ethereum/execution-specs` @ `0cc100eb1` (2026-09-08) states it twice in
    * one module: `src/ethereum/forks/cancun/__init__.py` gives the schedule row
    * `| Mainnet | 1710338135 | 2024-03-13 13:55:35 | 0x9f3d2254 | 269,568 |`
    * and `FORK_CRITERIA: ForkCriteria = ByTimestamp(1710338135)` beneath it.
    * `ethereum/go-ethereum` @ `02872e9ef` (2026-09-09) has
    * `CancunTime: newUint64(1710338135)` in `params/config.go`;
    * `besu-eth/besu` @ `b330564a9` (2026-09-09) has `"cancunTime": 1710338135`
    * in `config/src/main/resources/mainnet.json`; and
    * `ethereumclassic/core-geth` @ `4185df450` (2025-01-23) carries the same
    * figure on its Ethereum mainnet configuration in `params/config.go:66`.
    *
    * ==Nothing activates between this entry and the one below it==
    *
    * The same instrument every entry above was checked with -- every numeric
    * field in two clients' mainnet configurations, not the fields named after
    * an upgrade. **Both entries sit on one axis here, so the window is read on
    * that axis alone**, unlike the pair below it: between 1,681,338,455 and
    * 1,710,338,135 neither client states a timestamp at all.
    *
    * Two fields in those configurations are numeric and are not activations,
    * named so a later reader does not re-decide them: a blob schedule, which is
    * a figure the rules resolve rather than a point they change at, and a
    * deposit-contract address, which names a place rather than a time.
    *
    * ==The second entry on this network on the timestamp axis, and the first
    * whose ordering against its predecessor is settled by the numbers alone==
    *
    * The entry below it is the first timestamp entry, and its own note records
    * that EIP-6122's rule -- forks by timestamp MUST be scheduled at or after
    * forks by block -- is what actually orders it against a block number. This
    * one sits above another timestamp, so the ordinary comparison settles it
    * and the cross-axis rule is not what is doing the work. Both entries are
    * still subject to it.
    */
  private val cancun: UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(
      atTimestamp(1710338135),
      upgrade("Cancun"),
      Upgrade.RuleChange(Upgrades.cancun)
    )

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
        berlin,
        london,
        arrowGlacier,
        grayGlacier,
        paris,
        shanghai,
        cancun
      )
    )
