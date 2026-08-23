package org.fukuii.consensus

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, UInt256}
import org.fukuii.chainspec.{ConsensusRules, UpgradeRules}
import org.fukuii.evm.{EvmFixtures, Word, WorldState}

/** What an engine writes into state, and what it must be able to leave alone.
  *
  * ==The whole subject is one distinction, and it is invisible in a balance==
  *
  * *"No reward"* and *"a reward of zero"* leave the same balance and different
  * state roots: one touches no account and the other brings the beneficiary
  * into being holding nothing, which is a leaf in the state trie. So every case
  * below that concerns the zero asks whether the ACCOUNT exists and not what it
  * holds -- a suite that asserted balances would pass with the distinction
  * removed.
  *
  * ==The world state is a double, and the existence question is why it can be==
  *
  * `EvmFixtures.MapWorldState` tracks existence separately from its field maps,
  * so an account brought into being by a write of zero is one it reports as
  * present. That is the same contract [[org.fukuii.evm.WorldState]] states of
  * every write -- each is total, and applies to an absent account by creating
  * one -- rather than a convenience this suite arranged.
  *
  * ==A network's rules appear here as a carrier and not as a subject==
  *
  * The transformation cases need some resolved rule set to transform, and
  * building one by hand would mean authoring a machine's whole rule set to
  * assert something about a facet beside it. Ethereum's genesis rules are used
  * for that and nothing below asserts a value of theirs.
  */
class ConsensusEngineSpec extends AnyFlatSpec:

  private val beneficiary: Address = EvmFixtures.address(0x33)

  private def reward(of: Long): ConsensusRules =
    ConsensusRules(blockReward = UInt256.fromLong(of).toOption.get, zeroRewardCreditsBeneficiary = false)

  private val creditsZero: ConsensusRules =
    ConsensusRules.Unrewarded.copy(zeroRewardCreditsBeneficiary = true)

  private def rulesPaying(of: Long): UpgradeRules =
    org.fukuii.chainspec.networks.ethereum.Upgrades.frontier.copy(consensus = reward(of))

  /** Runs an engine's settlement over a fresh world and answers that world. */
  private def settled(engine: ConsensusEngine, rules: ConsensusRules): EvmFixtures.MapWorldState =
    val world = new EvmFixtures.MapWorldState
    engine.settlement(rules, beneficiary)(world)
    world

  /** A world in which the beneficiary already holds something, so a credit can
    * be told apart from a replacement.
    */
  private def settledOverExisting(rules: ConsensusRules, held: Long): EvmFixtures.MapWorldState =
    val world = new EvmFixtures.MapWorldState
    world.setBalance(beneficiary, Word(BigInt(held)))
    ConsensusEngine.Unmodifying.settlement(rules, beneficiary)(world)
    world

  /** An engine that pays nobody whatever the rules it was handed resolved to.
    *
    * This is the shape `besu-eth/besu` @ `c2addd9424` gives its Clique
    * schedule, which overwrites the reward and the skip flag on the
    * fork-resolved specification rather than replacing the reward code.
    */
  private val paysNobody: ConsensusEngine = new ConsensusEngine:
    override def rulesFrom(rules: UpgradeRules): UpgradeRules =
      rules.copy(consensus = ConsensusRules.Unrewarded)

  /** An engine whose emission is a formula over the amount the fork resolved.
    *
    * `besu-eth/besu-etc` @ `eb4248c99` reaches its era-based schedule exactly
    * this way, as a block processor overriding `rewardCoinbase` while still
    * reading the fork's `blockReward`, so the override point is one the field
    * uses rather than one offered speculatively. Halving is not any network's
    * formula and is not meant to be one.
    */
  private val halvesTheReward: ConsensusEngine = new ConsensusEngine:
    override def settlement(rules: ConsensusRules, to: Address): WorldState => Unit =
      world => world.setBalance(to, Word(rules.blockReward.toBigInt / 2))

  "an engine's settlement" should "credit the beneficiary the reward the rules state" in
    assert(
      settled(ConsensusEngine.Unmodifying, reward(7)).balanceOf(beneficiary) == Word(BigInt(7)),
      "the amount a block pays is the fork-resolved value, and reading any other figure is a different chain"
    )

  it should "add to what the beneficiary already holds rather than replace it" in
    assert(
      settledOverExisting(reward(7), held = 5).balanceOf(beneficiary) == Word(BigInt(12)),
      "a reward that overwrote a balance would destroy every prior credit to a repeat block producer"
    )

  it should "bring the beneficiary into being where it credits a reward of zero" in
    assert(
      settled(ConsensusEngine.Unmodifying, creditsZero).accountExists(beneficiary),
      "an account created holding nothing is still a leaf in the state trie, which is a state root difference"
    )

  it should "leave the beneficiary uncreated where a reward of zero is not credited" in
    assert(
      !settled(ConsensusEngine.Unmodifying, ConsensusRules.Unrewarded).accountExists(beneficiary),
      "this is the case that must not reach the account at all, and a balance assertion cannot tell it from the last"
    )

  it should "leave an existing beneficiary's balance alone where a reward of zero is not credited" in
    assert(
      settledOverExisting(ConsensusRules.Unrewarded, held = 5).balanceOf(beneficiary) == Word(BigInt(5)),
      "declining to credit is not a write of zero, which is what the amount would become if the two were confused"
    )

  it should "refuse a reward no account could hold rather than wrap into a small balance" in
    assertThrows[IllegalStateException](
      settledOverExisting(
        ConsensusRules(blockReward = UInt256.MaxValue, zeroRewardCreditsBeneficiary = false),
        held = 1
      )
    )

  it should "be replaceable by an engine computing its own emission from the resolved amount" in
    assert(
      settled(halvesTheReward, reward(8)).balanceOf(beneficiary) == Word(BigInt(4)),
      "an engine whose emission is a formula overrides the credit, so the default cannot be the only path"
    )

  "an engine's transformation" should "leave the resolved rules alone by default" in
    assert(
      ConsensusEngine.Unmodifying.rulesFrom(rulesPaying(7)) == rulesPaying(7),
      "a mechanism with nothing to contribute still runs, and what it contributes is nothing rather than absence"
    )

  it should "be what a caller reads when the engine overwrites a member" in
    assert(
      paysNobody.rulesFrom(rulesPaying(7)).consensus == ConsensusRules.Unrewarded,
      "an engine writing into the resolved rules is the join this seam exists to be, and it must reach the values"
    )

  it should "leave the facets it does not name as the schedule resolved them" in
    assert(
      paysNobody.rulesFrom(rulesPaying(7)).evm == rulesPaying(7).evm,
      "an engine reaching a facet it did not name is the same seam failure a component reaching one would be"
    )
