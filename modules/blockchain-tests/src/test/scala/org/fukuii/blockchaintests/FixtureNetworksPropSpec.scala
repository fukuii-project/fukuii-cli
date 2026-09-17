package org.fukuii.blockchaintests

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.consensus.ConsensusEngine

/** Which rules each published case's `network` names, at the coordinates its
  * blocks run at.
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
  * `FixtureNetworksSpec` holds what the networks do not resolve.
  */
class FixtureNetworksPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val FarNumber: UInt64 = UInt64.fromBits(1_000_000L)

  private val FarTimestamp: UInt64 = UInt64.fromBits(4_000_000_000L)

  private val BeforeTransition: UInt64 = UInt64.fromBits(14_999L)

  private val AtTransition: UInt64 = UInt64.fromBits(15_000L)

  private val One: UInt64 = UInt64.fromBits(1L)

  private def network(name: String): FixtureNetwork = FixtureNetworks.named(name).fold(error => fail(error), identity)

  private val Resolutions = Table(
    ("network", "number", "timestamp", "rules"),
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

  private val Names = Table(
    "network",
    "Paris",
    "Shanghai",
    "Cancun",
    "ParisToShanghaiAtTime15k",
    "ShanghaiToCancunAtTime15k"
  )

  private val Forks = Table(
    ("fork", "rules"),
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

  property("every network runs under the mechanism-neutral engine") {
    forAll(Names) { (name: String) =>
      assert(
        network(name).engine eq ConsensusEngine.Unmodifying,
        name + ": under the merge's rules and those after it no mechanism leaf runs a rule"
      )
    }
  }

  property("every network runs as chain id one") {
    forAll(Names) { (name: String) =>
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
