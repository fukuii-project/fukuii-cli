package org.fukuii.blockchaintests

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.consensus.ConsensusEngine

/** Which rules a published case's `network` names.
  *
  * ==Why the resolution is asked at more than one coordinate==
  *
  * A schedule answers per block, so a network that resolved to the right rules
  * at its genesis and to others later would pass a check made at the genesis
  * alone. The published blocks of a label run from the genesis up to a few
  * hundred heights and seconds, and a far coordinate stands for all of them.
  */
class FixtureNetworksSpec extends AnyFlatSpec:

  private def paris: FixtureNetwork = FixtureNetworks.named("Paris").fold(error => fail(error), identity)

  private val FarNumber: UInt64 = UInt64.fromBits(1_000_000L)

  private val FarTimestamp: UInt64 = UInt64.fromBits(4_000_000_000L)

  "named" should "resolve Paris to the merge's rules at its genesis" in
    assert(
      paris.schedule.at(UInt64.Zero, UInt64.Zero) == ethereum.Upgrades.paris,
      "the genesis of a network running the merge's rules from genesis resolves to them"
    )

  it should "resolve Paris to the merge's rules at a far coordinate" in
    assert(
      paris.schedule.at(FarNumber, FarTimestamp) == ethereum.Upgrades.paris,
      "a network running one fork from its genesis runs it at every height and second"
    )

  it should "run Paris under the mechanism-neutral engine" in
    assert(
      paris.engine eq ConsensusEngine.Unmodifying,
      "under the merge's rules no mechanism leaf runs a rule, so the neutral engine is the whole engine"
    )

  it should "run every network as chain id one" in
    assert(
      paris.chainId == UInt64.fromBits(1L) && FixtureNetworks.ChainId == UInt64.fromBits(1L),
      "the network runs as " + paris.chainId.show + " and the stated identifier is " + FixtureNetworks.ChainId.show
    )

  it should "name no rules for a network it does not hold, and say which" in
    assert(
      FixtureNetworks.named("NotAPublishedNetwork").left.exists(_.contains("NotAPublishedNetwork")),
      "a network this runner cannot resolve must be reported by name rather than run under some other rules"
    )
