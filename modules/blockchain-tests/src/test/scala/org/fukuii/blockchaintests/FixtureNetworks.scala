package org.fukuii.blockchaintests

import org.fukuii.bytes.UInt64
import org.fukuii.execution.RequestRules
import org.fukuii.chainspec.{Activation, Network, Upgrade, UpgradeId, UpgradeRules, UpgradeSchedule}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.consensus.ConsensusEngine
import org.fukuii.consensus.pow.{EthashEngine, SealEngine}

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

  /** The request parameters a fork committing to a request list needs.
    *
    * ==Mainnet's, because the corpus states none and uses mainnet's contract==
    *
    * A fixture's `config` carries the network name, the chain id and the blob
    * schedule, and **no deposit contract address** -- measured across the whole
    * generated block tier at `tests-v20.0.1`: zero files mention one, against
    * 2,573 stating a blob schedule under `for_prague` alone, so the zero is an
    * answer rather than a sweep that could not fire. What the deposit cases
    * actually emit from is `0x00000000219ab540356cbb839cbe05303d7705fa`, 1,135
    * occurrences, which is the address
    * `org.fukuii.chainspec.networks.ethereum.Mainnet.requestRules` states and
    * sources.
    *
    * **So the corpus expects the harness to supply it exactly as a node does**,
    * which is the whole reason that value is per-network rather than a rule --
    * and every case under this label states `"chainid": "0x01"`, so mainnet's is
    * the right one to supply.
    */
  def requestRules: RequestRules = ethereum.Mainnet.requestRules

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
  * ==Two spellings for two forks, because two corpora wrote them==
  *
  * The generated tier writes `TangerineWhistle` and `SpuriousDragon`;
  * `ethereum/legacytests`' `Constantinople` snapshot writes `EIP150` and
  * `EIP158` for the same forks, as the tool that filled it named them. Both
  * spellings resolve to one rule set each. That snapshot also names
  * `Constantinople`, the fork EIP-1013 specifies with EIP-1283's metering, which
  * resolves to [[org.fukuii.chainspec.networks.ethereum.Upgrades.constantinople]]
  * and not to the rules `ConstantinopleFix` names.
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
  * so none is a mapping nothing reads. **Exercised is not decided for every
  * entry**: a label moves under a wrong rule set only where its cases tell the
  * two rule sets apart. `EIP150` resolved to Homestead's rules and
  * `Constantinople` to ConstantinopleFix's each leave every label agreeing, and
  * `FixtureNetworksPropSpec` is what refuses either; which entries a label
  * decides was measured for those two alone.
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

  /** The seal engine a case states for blocks sealed without proof, which is the
    * one configuration every engine here runs.
    */
  val NoProof: String = "NoProof"

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
      "Frontier" -> ethereum.Upgrades.frontier,
      "Homestead" -> ethereum.Upgrades.homestead,
      "TangerineWhistle" -> ethereum.Upgrades.tangerineWhistle,
      "EIP150" -> ethereum.Upgrades.tangerineWhistle,
      "SpuriousDragon" -> ethereum.Upgrades.spuriousDragon,
      "EIP158" -> ethereum.Upgrades.spuriousDragon,
      "Byzantium" -> ethereum.Upgrades.byzantium,
      "Constantinople" -> ethereum.Upgrades.constantinople,
      "ConstantinopleFix" -> ethereum.Upgrades.petersburg,
      "Istanbul" -> ethereum.Upgrades.istanbul,
      "Berlin" -> ethereum.Upgrades.berlin,
      "London" -> ethereum.Upgrades.london,
      "Paris" -> ethereum.Upgrades.paris,
      "Shanghai" -> ethereum.Upgrades.shanghai,
      "Cancun" -> ethereum.Upgrades.cancun,
      "Prague" -> ethereum.Upgrades.prague
    )

  /** The timestamp a transition network's later fork activates at. */
  private val TransitionTimestamp: UInt64 = UInt64.fromBits(15_000L)

  /** The engine a network before the merge runs its blocks under.
    *
    * Ethash with no ECIP-1017 era, which is Ethereum's emission, and configured
    * for blocks sealed without proof: it runs the difficulty rule and states no
    * seal rule, the configuration a case stating [[NoProof]] selects.
    */
  private val proofOfWork: ConsensusEngine = EthashEngine(ecip1017EraLength = None, sealEngine = SealEngine.NoProof)

  private def networkFor(name: String): Network = Network(ChainId, "published blockchain tests " + name)

  private def entry(network: Network, activation: Activation, fork: String): UpgradeSchedule.Entry =
    UpgradeSchedule.Entry(activation, UpgradeId.named(network, fork), Upgrade.RuleChange(forks(fork)))

  private def scheduled(
      entries: Vector[UpgradeSchedule.Entry],
      engine: ConsensusEngine
  ): Either[String, FixtureNetwork] =
    UpgradeSchedule
      .of(entries)
      .left
      .map(_.toString)
      .map(schedule => FixtureNetwork(schedule, engine))

  /** A network running one fork's rules from its genesis onward. */
  private def fromGenesis(fork: String, engine: ConsensusEngine): Either[String, FixtureNetwork] =
    scheduled(Vector(entry(networkFor(fork), Activation.AtBlock(UInt64.Zero), fork)), engine)

  /** A network running `before` from its genesis and `after` from the
    * transition timestamp.
    */
  private def transition(name: String, before: String, after: String): Either[String, FixtureNetwork] =
    val network = networkFor(name)
    scheduled(
      Vector(
        entry(network, Activation.AtBlock(UInt64.Zero), before),
        entry(network, Activation.AtTimestamp(TransitionTimestamp), after)
      ),
      ConsensusEngine.Unmodifying
    )

  /** Every network a published label runs, each with the engine its blocks run
    * under.
    *
    * **Before the merge, [[proofOfWork]]**: the difficulty is a formula over the
    * parent, and the reward is the mechanism's settlement.
    *
    * **From the merge on, the mechanism-neutral engine**, and no mechanism leaf
    * is involved: under EIP-3675's rules the seal and the difficulty are
    * constants the shared header rules compare, and the settlement writes
    * nothing, so [[org.fukuii.consensus.ConsensusEngine.Unmodifying]] leaves no
    * rule of these networks unrun.
    */
  private val known: Map[String, Either[String, FixtureNetwork]] =
    Map(
      "Frontier" -> fromGenesis("Frontier", proofOfWork),
      "Homestead" -> fromGenesis("Homestead", proofOfWork),
      "TangerineWhistle" -> fromGenesis("TangerineWhistle", proofOfWork),
      "EIP150" -> fromGenesis("EIP150", proofOfWork),
      "SpuriousDragon" -> fromGenesis("SpuriousDragon", proofOfWork),
      "EIP158" -> fromGenesis("EIP158", proofOfWork),
      "Byzantium" -> fromGenesis("Byzantium", proofOfWork),
      "Constantinople" -> fromGenesis("Constantinople", proofOfWork),
      "ConstantinopleFix" -> fromGenesis("ConstantinopleFix", proofOfWork),
      "Istanbul" -> fromGenesis("Istanbul", proofOfWork),
      "Berlin" -> fromGenesis("Berlin", proofOfWork),
      "London" -> fromGenesis("London", proofOfWork),
      "Paris" -> fromGenesis("Paris", ConsensusEngine.Unmodifying),
      "Shanghai" -> fromGenesis("Shanghai", ConsensusEngine.Unmodifying),
      "Cancun" -> fromGenesis("Cancun", ConsensusEngine.Unmodifying),
      "Prague" -> fromGenesis("Prague", ConsensusEngine.Unmodifying),
      "ParisToShanghaiAtTime15k" -> transition("ParisToShanghaiAtTime15k", "Paris", "Shanghai"),
      "ShanghaiToCancunAtTime15k" -> transition("ShanghaiToCancunAtTime15k", "Shanghai", "Cancun"),
      "CancunToPragueAtTime15k" -> transition("CancunToPragueAtTime15k", "Cancun", "Prague")
    )
