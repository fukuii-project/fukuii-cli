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
    * ==Six of the nine proposals this upgrade names are implemented==
    *
    * EIP-609 § *Included EIPs* lists EIP-100, EIP-140, EIP-196, EIP-197,
    * EIP-198, EIP-211, EIP-214, EIP-649 and EIP-658.
    * [[Upgrades.byzantium]] carries EIP-100, EIP-649, EIP-140, EIP-211,
    * EIP-214 and EIP-658 and records all six in its component list; the other
    * three are not there, so a node built from this entry validates less across
    * this boundary than the network did. **The entry names the network's
    * upgrade; it does not claim to implement all of it**, which is the standing
    * [[homestead]] has for a different reason and [[spuriousDragon]] had until
    * its fourth proposal landed.
    *
    * The three that are missing are the three that add a native -- EIP-196 and
    * EIP-197 at the alt-bn128 curve, EIP-198 at modular exponentiation -- so
    * what this entry cannot yet do is answer a call to one of them.
    *
    * ==Resolving a partial upgrade at this height is nearer the network than
    * resolving none==
    *
    * [[org.fukuii.chainspec.UpgradeSchedule.at]] holds the last rule change at
    * or below the height it is asked about, so before this entry existed every
    * height from here upward answered with the preceding rule set -- which
    * carries none of these proposals and is wrong about all of them. Adding the
    * entry cannot make an answer above 4,370,000 worse than the answer it
    * replaces, and it is the only thing that makes any of them reachable from a
    * schedule at all.
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
      Vector(frontier, frontierThawing, homestead, daoFork, tangerineWhistle, spuriousDragon, byzantium)
    )
