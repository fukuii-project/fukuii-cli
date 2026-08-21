package org.fukuii.chainspec

import org.scalatest.flatspec.AnyFlatSpec

/** Identity, and the property that a shared word is not shared rules. */
class UpgradeIdSpec extends AnyFlatSpec:

  import ChainspecFixtures.{alpha, beta}

  /** The measured case this guards: one production client carries a whole
    * directory of upgrades named for another network's with a suffix, and the
    * one that shares the most also differs -- it routes the base fee where the
    * other burns it.
    */
  "two networks using one word for an upgrade" should "not produce one identifier" in
    assert(
      UpgradeId.named(alpha, "London") != UpgradeId.named(beta, "London"),
      "a shared label must not be able to imply shared rules, and equality is the first way it could"
    )

  "an identifier" should "render with the network that owns it" in
    assert(
      UpgradeId.named(alpha, "London").show == "Alpha London",
      "a label read without its network is the ambiguity this type exists to remove"
    )

  "a network that never named its launching configuration" should "render a composed label" in
    assert(
      UpgradeId.synthesized(beta).show == "Beta Baseline",
      "the form is composed once here so that every such network reads the same rather than each inventing a string"
    )

  it should "not be the same identifier as one that named it those words" in
    assert(
      UpgradeId.synthesized(alpha) != UpgradeId.named(alpha, "Baseline"),
      "one records that the network supplied no name; the other records that it supplied this one"
    )
