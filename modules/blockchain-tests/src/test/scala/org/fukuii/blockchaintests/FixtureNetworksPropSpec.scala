package org.fukuii.blockchaintests

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.consensus.ConsensusEngine
import org.fukuii.consensus.pow.{EthashEngine, SealEngine}

/** Which rules each published case's `network` names, at the coordinates its
  * blocks run at, and which engine runs them.
  *
  * ==Why each network is asked at more than one coordinate==
  *
  * A schedule answers per block, so a network that resolved to the right rules
  * at its genesis and to others later would pass a check made at the genesis
  * alone. A network running one fork is asked at its genesis and at a far
  * coordinate standing for every block after it; a transition network is asked
  * one second either side of the second its later fork activates at, which is
  * where an off-by-one in the activation would show.
  *
  * ==Both spellings, and the fork EIP-1283 was withdrawn from==
  *
  * `EIP150` and `TangerineWhistle` are one fork written by two corpora, as are
  * `EIP158` and `SpuriousDragon`, and each pair is asked for one rule set.
  * `Constantinople` and `ConstantinopleFix` are two forks, and a row each holds
  * them apart.
  *
  * `FixtureNetworksSpec` holds what the networks do not resolve.
  */
class FixtureNetworksPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val FarNumber: UInt64 = UInt64.fromBits(1_000_000L)

  private val FarTimestamp: UInt64 = UInt64.fromBits(4_000_000_000L)

  private val BeforeTransition: UInt64 = UInt64.fromBits(14_999L)

  private val AtTransition: UInt64 = UInt64.fromBits(15_000L)

  private val One: UInt64 = UInt64.fromBits(1L)

  private def network(name: String): FixtureNetwork = FixtureNetworks.named(name).fold(error => fail(error), identity)

  private val ProofOfWork: EthashEngine = EthashEngine(ecip1017EraLength = None, sealEngine = SealEngine.NoProof)

  private val Resolutions = Table(
    ("network", "number", "timestamp", "rules"),
    ("Frontier", UInt64.Zero, UInt64.Zero, ethereum.Upgrades.frontier),
    ("Frontier", FarNumber, FarTimestamp, ethereum.Upgrades.frontier),
    ("Homestead", UInt64.Zero, UInt64.Zero, ethereum.Upgrades.homestead),
    ("Homestead", FarNumber, FarTimestamp, ethereum.Upgrades.homestead),
    ("TangerineWhistle", FarNumber, FarTimestamp, ethereum.Upgrades.tangerineWhistle),
    ("EIP150", FarNumber, FarTimestamp, ethereum.Upgrades.tangerineWhistle),
    ("SpuriousDragon", FarNumber, FarTimestamp, ethereum.Upgrades.spuriousDragon),
    ("EIP158", FarNumber, FarTimestamp, ethereum.Upgrades.spuriousDragon),
    ("Byzantium", UInt64.Zero, UInt64.Zero, ethereum.Upgrades.byzantium),
    ("Byzantium", FarNumber, FarTimestamp, ethereum.Upgrades.byzantium),
    ("Constantinople", FarNumber, FarTimestamp, ethereum.Upgrades.constantinople),
    ("ConstantinopleFix", FarNumber, FarTimestamp, ethereum.Upgrades.petersburg),
    ("Istanbul", FarNumber, FarTimestamp, ethereum.Upgrades.istanbul),
    ("Berlin", FarNumber, FarTimestamp, ethereum.Upgrades.berlin),
    ("London", UInt64.Zero, UInt64.Zero, ethereum.Upgrades.london),
    ("London", FarNumber, FarTimestamp, ethereum.Upgrades.london),
    ("Paris", UInt64.Zero, UInt64.Zero, ethereum.Upgrades.paris),
    ("Paris", FarNumber, FarTimestamp, ethereum.Upgrades.paris),
    ("Shanghai", UInt64.Zero, UInt64.Zero, ethereum.Upgrades.shanghai),
    ("Shanghai", FarNumber, FarTimestamp, ethereum.Upgrades.shanghai),
    ("Cancun", UInt64.Zero, UInt64.Zero, ethereum.Upgrades.cancun),
    ("Cancun", FarNumber, FarTimestamp, ethereum.Upgrades.cancun),
    ("ParisToShanghaiAtTime15k", One, BeforeTransition, ethereum.Upgrades.paris),
    ("ParisToShanghaiAtTime15k", One, AtTransition, ethereum.Upgrades.shanghai),
    ("ShanghaiToCancunAtTime15k", One, BeforeTransition, ethereum.Upgrades.shanghai),
    ("ShanghaiToCancunAtTime15k", One, AtTransition, ethereum.Upgrades.cancun)
  )

  private val Engines = Table(
    ("network", "engine"),
    ("Frontier", ProofOfWork),
    ("Homestead", ProofOfWork),
    ("TangerineWhistle", ProofOfWork),
    ("EIP150", ProofOfWork),
    ("SpuriousDragon", ProofOfWork),
    ("EIP158", ProofOfWork),
    ("Byzantium", ProofOfWork),
    ("Constantinople", ProofOfWork),
    ("ConstantinopleFix", ProofOfWork),
    ("Istanbul", ProofOfWork),
    ("Berlin", ProofOfWork),
    ("London", ProofOfWork),
    ("Paris", ConsensusEngine.Unmodifying),
    ("Shanghai", ConsensusEngine.Unmodifying),
    ("Cancun", ConsensusEngine.Unmodifying),
    ("ParisToShanghaiAtTime15k", ConsensusEngine.Unmodifying),
    ("ShanghaiToCancunAtTime15k", ConsensusEngine.Unmodifying)
  )

  private val Forks = Table(
    ("fork", "rules"),
    ("Frontier", ethereum.Upgrades.frontier),
    ("EIP150", ethereum.Upgrades.tangerineWhistle),
    ("ConstantinopleFix", ethereum.Upgrades.petersburg),
    ("London", ethereum.Upgrades.london),
    ("Paris", ethereum.Upgrades.paris),
    ("Shanghai", ethereum.Upgrades.shanghai),
    ("Cancun", ethereum.Upgrades.cancun)
  )

  property("every network resolves the rules its blocks run under at each coordinate") {
    forAll(Resolutions) { (name: String, number: UInt64, timestamp: UInt64, rules: UpgradeRules) =>
      assert(
        network(name).schedule.at(number, timestamp) == rules,
        name + " at block " + number.show + " and second " + timestamp.show
      )
    }
  }

  property("every network runs under the engine its rules call for") {
    // Before the merge, ethash configured for blocks sealed without proof, which
    // compares by value; from the merge on, the one mechanism-neutral engine,
    // which has no equality but its identity.
    forAll(Engines) { (name: String, engine: ConsensusEngine) =>
      val resolved = network(name).engine
      assert(resolved == engine, name + " runs under " + resolved.toString)
    }
  }

  property("every network runs as chain id one") {
    forAll(Engines) { (name: String, _: ConsensusEngine) =>
      assert(
        network(name).chainId == One && FixtureNetworks.ChainId == One,
        name + " runs as " + network(name).chainId.show
      )
    }
  }

  property("every fork a blob schedule may name resolves to that fork's own rules") {
    forAll(Forks) { (fork: String, rules: UpgradeRules) =>
      assert(FixtureNetworks.forkRules(fork).contains(rules), fork)
    }
  }
