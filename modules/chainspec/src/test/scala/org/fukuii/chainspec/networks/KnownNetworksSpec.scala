package org.fukuii.chainspec.networks

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.Registry
import org.scalatest.flatspec.AnyFlatSpec

/** Finding a network's schedule by the id the ecosystem keys networks on. */
class KnownNetworksSpec extends AnyFlatSpec:

  private val registry: Registry =
    KnownNetworks.registry.getOrElse(fail("the authored networks do not form a registry"))

  "the authored networks" should "form a registry" in
    assert(
      KnownNetworks.registry.isRight,
      "a Left is either a schedule this build disagrees with itself about or two networks claiming one id"
    )

  "chain id 1" should "resolve to Ethereum mainnet's schedule" in
    assert(
      registry.at(UInt64.fromBits(1L)).map(_.network).contains(ethereum.Mainnet.network),
      "the registry keys on the id carried by the schedule, so this is the lookup a node performs at startup"
    )

  "chain id 61" should "resolve to Ethereum Classic mainnet's schedule" in
    assert(
      registry.at(UInt64.fromBits(61L)).map(_.network).contains(ethereumclassic.Mainnet.network),
      "two networks in one registry is the case a lookup by id exists for, and each must reach its own schedule"
    )

  "a chain id this build knows nothing about" should "resolve to nothing" in
    assert(
      registry.at(UInt64.fromBits(63L)).isEmpty,
      "not running a network is not the same as running it with an empty schedule, and 63 is a test network"
    )
