package org.fukuii.blockchaintests

import org.scalatest.flatspec.AnyFlatSpec

/** What the published networks do not resolve.
  *
  * What they do resolve -- each network's rules at the coordinates its blocks
  * run at, its engine, its chain identifier, and each fork's own rules -- is
  * `FixtureNetworksPropSpec`'s table.
  */
class FixtureNetworksSpec extends AnyFlatSpec:

  "named" should "name no rules for a network it does not hold, and say which" in
    assert(
      FixtureNetworks.named("NotAPublishedNetwork").left.exists(_.contains("NotAPublishedNetwork")),
      "a network this runner cannot resolve must be reported by name rather than run under some other rules"
    )

  "forkRules" should "resolve no rules for a fork the corpus does not name" in
    assert(
      FixtureNetworks.forkRules("NotAPublishedFork").isEmpty,
      "a blob schedule naming a fork no rules are held for must reach the runner as nothing, so it diverges by name"
    )
