package org.fukuii.chainspec

import org.scalatest.flatspec.AnyFlatSpec

/** Finding a network's schedule, and the one way a registry can be built
  * wrongly.
  */
class RegistrySpec extends AnyFlatSpec:

  import ChainspecFixtures.*

  private val forAlpha = UpgradeSchedule.of(genesisOnly).toOption.get

  private val forBeta =
    UpgradeSchedule.of(Vector(entry(atBlock(0), "Start", Upgrade.ProtocolChange(secondRules), beta))).toOption.get

  private val registry = Registry.of(Vector(forAlpha, forBeta)).toOption.get

  "a registry" should "answer with the schedule belonging to the chain id asked for" in
    assert(
      registry.at(beta.chainId).map(_.network).contains(beta),
      "the key is taken from the schedule, so a lookup cannot land on a schedule filed under the wrong network"
    )

  it should "have nothing for a chain id it was not given" in
    assert(
      registry.at(number(900003)).isEmpty,
      "a node that does not run a network has no schedule for it, which is not the same as having an empty one"
    )

  "two schedules claiming one chain id" should "be refused" in
    assert(
      Registry.of(Vector(forAlpha, forAlpha)) == Left(Registry.Error.DuplicateNetwork(alpha.chainId)),
      "a lookup would otherwise have to pick one, and nothing downstream could see that it had"
    )
