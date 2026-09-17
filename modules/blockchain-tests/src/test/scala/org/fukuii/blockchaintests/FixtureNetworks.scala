package org.fukuii.blockchaintests

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, Network, Upgrade, UpgradeId, UpgradeRules, UpgradeSchedule}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.consensus.ConsensusEngine

/** The rules a published case's `network` names, and the mechanism that runs a
  * block under them.
  *
  * @param schedule
  *   what every block of the case is resolved against, the genesis included --
  *   so a network changing its rules part-way through a case is a schedule with
  *   a second entry rather than a second kind of network.
  */
final case class FixtureNetwork(schedule: UpgradeSchedule, engine: ConsensusEngine):

  def chainId: UInt64 = schedule.network.chainId

/** The networks the published Ethereum block tier names, keyed as a case names
  * them.
  *
  * ==Keyed by the case's `network`, not by the directory it sits in==
  *
  * A label directory holds the cases filled for one set of rules, and the case
  * states which set in its own `network` field. The clients read for this key
  * on that field: `ethereum/go-ethereum` @ `02872e9ef` looks the chain
  * configuration up as `Forks[t.json.Network]` (`tests/block_test_util.go:117`),
  * `besu-eth/besu` @ `b330564a94` as `protocolSchedules.getByName(spec.getNetwork())`
  * (`BlockchainReferenceTestTools.java:154`), and `NethermindEth/nethermind` @
  * `3a98e0818` as `ChainUtils.ResolveSpec(test.Network, test.ChainId)`
  * (`Ethereum.Test.Base/BlockchainTestBase.cs:91`).
  *
  * ==A schedule, because that is what the field builds for a name==
  *
  * Each of those answers a chain configuration with its forks at the heights
  * the case runs them, which is what [[org.fukuii.chainspec.UpgradeSchedule]] is
  * here. So the rules for a block are asked of the schedule at that block's own
  * height and timestamp, exactly as a node asks its own network's schedule.
  *
  * ==A transition is two forks and a second, and both sources state the second==
  *
  * `ParisToShanghaiAtTime15k` and `ShanghaiToCancunAtTime15k` run the earlier
  * fork's rules from the genesis and the later fork's from timestamp 15,000.
  * `ethereum/execution-specs` @ `0cc100eb1` declares each with
  * `@transition_fork(..., at_timestamp=15_000)`
  * (`packages/testing/src/execution_testing/forks/forks/transition.py:28,35`),
  * and `ethereum/go-ethereum` @ `02872e9ef` configures the same two as
  * `ShanghaiTime: u64(15_000)` and as `ShanghaiTime: u64(0)` beside
  * `CancunTime: u64(15_000)` (`tests/init.go:317,357-358`).
  *
  * ==Every entry is this family's, and a name alone does not say which family==
  *
  * The corpus is the Ethereum family's, filled at Ethereum's forks, so every
  * schedule here is built from `org.fukuii.chainspec.networks.ethereum`'s rules.
  * **A fork name is not enough to pick those rules**: `NethermindEth/nethermind`
  * @ `3a98e0818` reads the same names for Gnosis and tells the two apart by the
  * chain identifier, answering `Cancun` with `CancunGnosis` where the chain is
  * Gnosis (`Ethereum.Test.Base/ChainUtils.cs:13-24`). So a name here means
  * Ethereum's rules at [[ChainId]] only, and a case stating another chain
  * identifier diverges rather than running under them; a corpus filled for
  * another network that reuses these names needs entries keyed by both.
  *
  * ==An entry arrives with the first corpus that runs it==
  *
  * Every entry below is exercised by a published label this build certifies,
  * so a wrong rule set or engine for a name is a divergence somewhere rather
  * than a mapping nothing reads.
  */
object FixtureNetworks:

  /** The chain identifier every network here runs as.
    *
    * One, in both clients read for it: `ethereum/go-ethereum` @ `02872e9ef`
    * gives every test fork `ChainID: big.NewInt(1)` (`tests/init.go:32`), and
    * `besu-eth/besu` @ `b330564a94` builds its reference schedules with
    * `CHAIN_ID = BigInteger.ONE` (`ReferenceTestProtocolSchedules.java:54`). A
    * case that states an identifier of its own is compared against this rather
    * than trusted over it.
    *
    * ==A case stating none runs as this one too==
    *
    * `ethereum/legacytests`' snapshots carry no `config`, so their cases state
    * no identifier at all. The specification's own loader runs such a case as
    * the identifier its genesis header states and as one where it states none
    * -- `U64(json_data["genesisBlockHeader"].get("chainId", 1))`
    * (`ethereum/execution-specs` @ `0cc100eb1`,
    * `tests/json_loader/helpers/load_blockchain_tests.py:165`) -- which agrees
    * with go-ethereum's table above.
    */
  val ChainId: UInt64 = UInt64.fromBits(1L)

  /** The network a case names, or why it names none this runner resolves. */
  def named(network: String): Either[String, FixtureNetwork] =
    known.get(network) match
      case None           => Left("no rules for the network " + network)
      case Some(resolved) => resolved.left.map(error => "the network " + network + " is not a schedule: " + error)

  /** The rules of a fork the corpus names, as this build resolves them.
    *
    * What a case's blob schedule is compared against: an entry states one
    * fork's parameters, whichever network the case runs, so it is read against
    * that fork's rules rather than against the network's.
    */
  def forkRules(fork: String): Option[UpgradeRules] = forks.get(fork)

  private val forks: Map[String, UpgradeRules] =
    Map(
      "Paris" -> ethereum.Upgrades.paris,
      "Shanghai" -> ethereum.Upgrades.shanghai,
      "Cancun" -> ethereum.Upgrades.cancun
    )

  /** The timestamp a transition network's later fork activates at. */
  private val TransitionTimestamp: UInt64 = UInt64.fromBits(15_000L)

  private def networkFor(name: String): Network = Network(ChainId, "published blockchain tests " + name)

  private def entry(network: Network, activation: Activation, fork: String): UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(activation, UpgradeId.named(network, fork), Upgrade.RuleChange(forks(fork)))

  private def scheduled(entries: Vector[UpgradeSchedule.Entry]): Either[String, FixtureNetwork] =
    UpgradeSchedule
      .of(entries)
      .left
      .map(_.toString)
      .map(schedule => FixtureNetwork(schedule, ConsensusEngine.Unmodifying))

  /** A network running one fork's rules from its genesis onward. */
  private def fromGenesis(fork: String): Either[String, FixtureNetwork] =
    scheduled(Vector(entry(networkFor(fork), Activation.AtBlock(UInt64.Zero), fork)))

  /** A network running `before` from its genesis and `after` from the
    * transition timestamp.
    */
  private def transition(name: String, before: String, after: String): Either[String, FixtureNetwork] =
    val network = networkFor(name)
    scheduled(
      Vector(
        entry(network, Activation.AtBlock(UInt64.Zero), before),
        entry(network, Activation.AtTimestamp(TransitionTimestamp), after)
      )
    )

  /** The merge's rules and those after it, each run by the mechanism-neutral
    * engine.
    *
    * No mechanism leaf is involved: under EIP-3675's rules the seal and the
    * difficulty are constants the shared header rules compare, and the
    * settlement writes nothing, so [[org.fukuii.consensus.ConsensusEngine.Unmodifying]]
    * leaves no rule of these networks unrun.
    */
  private val known: Map[String, Either[String, FixtureNetwork]] =
    Map(
      "Paris" -> fromGenesis("Paris"),
      "Shanghai" -> fromGenesis("Shanghai"),
      "Cancun" -> fromGenesis("Cancun"),
      "ParisToShanghaiAtTime15k" -> transition("ParisToShanghaiAtTime15k", "Paris", "Shanghai"),
      "ShanghaiToCancunAtTime15k" -> transition("ShanghaiToCancunAtTime15k", "Shanghai", "Cancun")
    )
