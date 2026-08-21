package org.fukuii.chainspec

import org.fukuii.bytes.UInt64
import org.scalatest.flatspec.AnyFlatSpec

/** What a schedule refuses at construction, and what it answers once built. */
class UpgradeScheduleSpec extends AnyFlatSpec:

  import ChainspecFixtures.*

  private val genesis = entry(atBlock(0), "Start")

  private val later = entry(atBlock(100), "Later", Upgrade.ProtocolChange(secondRules))

  /** An upgrade that states the rules already in force: it has a name and an
    * activation and gates nothing, which every canonical fork enumeration in
    * the field contains at least one of.
    */
  private val gatesNothing = entry(atBlock(50), "Thawing")

  private def built(entries: UpgradeSchedule.Entry*): UpgradeSchedule =
    UpgradeSchedule.of(entries.toVector).toOption.get

  private def refused(entries: UpgradeSchedule.Entry*): Option[UpgradeSchedule.Error] =
    UpgradeSchedule.of(entries.toVector).left.toOption

  "entries that activate nowhere" should "not form a schedule" in
    assert(
      refused(entry(Activation.Unscheduled, "Pending")).contains(UpgradeSchedule.Error.NoScheduledEntry),
      "a schedule with nothing in force cannot answer for any height, so it is not total"
    )

  "no entries at all" should "not form a schedule" in
    assert(
      UpgradeSchedule.of(Vector.empty) == Left(UpgradeSchedule.Error.NoScheduledEntry),
      "there is no network to derive and no rules to start from"
    )

  "a schedule whose earliest entry is not block zero" should "be refused" in
    assert(
      refused(entry(atBlock(1), "Late")).contains(UpgradeSchedule.Error.MissingGenesis(atBlock(1))),
      "a height below the earliest entry would have no rules, and the lookup promises an answer for every height"
    )

  "a genesis entry that changes state rather than rules" should "be refused" in
    assert(
      refused(entry(atBlock(0), "Start", Upgrade.IrregularStateChange))
        .contains(UpgradeSchedule.Error.GenesisWithoutRules(UpgradeId.named(alpha, "Start"))),
      "an irregular state change states no rules, so a network whose first entry is one has no baseline"
    )

  "entries that go backwards" should "be refused" in
    assert(
      refused(genesis, entry(atBlock(100), "Later"), entry(atBlock(50), "Earlier"))
        .contains(UpgradeSchedule.Error.OutOfOrder(atBlock(100), UpgradeId.named(alpha, "Earlier"))),
      "order is checked rather than imposed, because sorting would accept the schedule silently"
    )

  /** The figures ascend -- 0 then 5 -- so a comparison reading them without
    * their axes accepts this. That is exactly what one production client's
    * schedule ordering does, and it holds there only because one network's
    * timestamps happen to exceed its heights.
    */
  "a timestamp activation before a block activation" should "be refused even where the figures ascend" in
    assert(
      refused(genesis, entry(atTimestamp(0), "ByTime"), entry(atBlock(5), "ByHeight"))
        .contains(UpgradeSchedule.Error.TimestampBeforeBlock(atTimestamp(0), UpgradeId.named(alpha, "ByHeight"))),
      "EIP-6122 requires every timestamp fork at or after every block fork, on private networks as well as public ones"
    )

  "entries from two networks" should "be refused" in
    assert(
      refused(genesis, entry(atBlock(100), "Later", Upgrade.ProtocolChange(secondRules), beta))
        .contains(UpgradeSchedule.Error.MixedNetworks(alpha, beta)),
      "one network's rules reaching another's schedule under a shared label is the failure this module exists to stop"
    )

  "two entries a network calls the same thing" should "be refused" in
    assert(
      refused(genesis, entry(atBlock(100), "Start"))
        .contains(UpgradeSchedule.Error.DuplicateUpgrade(UpgradeId.named(alpha, "Start"))),
      "a network does not name two of its upgrades the same, and a duplicate is a paste rather than a decision"
    )

  "a schedule" should "start from the rules its own genesis entry states" in
    assert(
      built(genesis, later).baseline eq firstRules,
      "the baseline is resolved per network by its schedule, which is the whole reason this layer exists"
    )

  it should "take the network from the entries rather than being told it" in
    assert(
      built(genesis, later).network == alpha,
      "a schedule filed under the wrong network would answer every lookup and answer them with another chain's rules"
    )

  "a height below the next upgrade" should "resolve to the rules already in force" in
    assert(
      built(genesis, later).at(number(99), UInt64.Zero) eq firstRules,
      "an upgrade is not in force before its activation"
    )

  "a height at the upgrade's own block" should "resolve to its rules" in
    assert(
      built(genesis, later).at(number(100), UInt64.Zero) eq secondRules,
      "the activation block is the first block the new rules apply to, not the last of the old ones"
    )

  "an upgrade that states the rules already in force" should "leave the answer unchanged" in
    assert(
      built(genesis, gatesNothing, later).at(number(60), UInt64.Zero) eq firstRules,
      "a schedule has to hold an upgrade with no rules behind it, or every entry after it is misnumbered"
    )

  "an entry that changes state rather than rules" should "leave the rules in force alone" in
    assert(
      built(genesis, entry(atBlock(50), "Recovery", Upgrade.IrregularStateChange), later)
        .at(number(60), UInt64.Zero) eq firstRules,
      "the DAO fork changed no opcode, no transaction format and no block structure, and two networks need this case"
    )

  "an upgrade a network will never take" should "never come into force" in
    assert(
      built(genesis, entry(Activation.Never, "Refused", Upgrade.ProtocolChange(secondRules)))
        .at(UInt64.MaxValue, UInt64.MaxValue) eq firstRules,
      "a permanent refusal is recorded so it is not mistaken for an activation still to come"
    )

  "an upgrade not yet scheduled" should "never come into force" in
    assert(
      built(genesis, entry(Activation.Unscheduled, "Pending", Upgrade.ProtocolChange(secondRules)))
        .at(UInt64.MaxValue, UInt64.MaxValue) eq firstRules,
      "an upgrade with no activation point has none, however far the chain has run"
    )

  /** The block cannot exist on this schedule -- a height of five with a
    * timestamp past a later fork -- and the two readings differ only here. One
    * stops at the first upgrade the chain has not reached; the other returns
    * rules from the far side of it.
    */
  "a low height with a late timestamp" should "not reach past an upgrade the chain has not passed" in
    assert(
      built(genesis, later, entry(atTimestamp(1000), "ByTime", Upgrade.ProtocolChange(thirdRules)))
        .at(number(5), number(2000)) eq firstRules,
      "resolution stops at the first activation that has not happened, which is what the ordering invariant buys"
    )

  "two upgrades sharing one activation" should "resolve to the one written last" in
    assert(
      built(
        genesis,
        entry(atBlock(100), "First", Upgrade.ProtocolChange(secondRules)),
        entry(atBlock(100), "Second", Upgrade.ProtocolChange(thirdRules))
      )
        .at(number(100), UInt64.Zero) eq thirdRules,
      "EIP-2124 contemplates several upgrades at one point, so which of them states the rules is the author's to say"
    )

  "a genesis entry that is scheduled but enforces nothing" should "be refused" in
    assert(
      refused(entry(atBlock(0), "Start", Upgrade.Unenforced))
        .contains(UpgradeSchedule.Error.GenesisWithoutRules(UpgradeId.named(alpha, "Start"))),
      "an upgrade that enforces nothing states no rules, so a network whose first entry is one has no baseline"
    )

  "an upgrade that enforces nothing" should "leave the rules in force alone" in
    assert(
      built(genesis, entry(atBlock(50), "Thaw", Upgrade.Unenforced), later).at(number(60), UInt64.Zero) eq firstRules,
      "an entry that gates no rule must not perturb resolution, or the schedule cannot hold one at all"
    )

  "the genesis entry" should "not be a fork point" in
    assert(
      built(genesis, later).forkPoints == Vector(atBlock(100)),
      "EIP-2124 states that a chain starting from a given rule set is not thereby considered to have forked"
    )

  "an upgrade that enforces nothing" should "not be a fork point" in
    assert(
      built(genesis, entry(atBlock(50), "Thaw", Upgrade.Unenforced), later).forkPoints == Vector(atBlock(100)),
      "nothing a node validates differs across it, so a peer that ignores it disagrees about nothing"
    )

  /** The claim that forces a third case rather than two: the discriminator is
    * not whether an upgrade changes the rules. This one does not and is still a
    * point at which two nodes can disagree, because the state differs across it.
    */
  "an upgrade that changes state without changing rules" should "be a fork point" in
    assert(
      built(genesis, entry(atBlock(50), "Recovery", Upgrade.IrregularStateChange), later).forkPoints ==
        Vector(atBlock(50), atBlock(100)),
      "the DAO fork changed no rule and still moves the fork identifier, so gating no rule cannot be the test"
    )

  "a height with the high bit set" should "resolve through the activations below it" in
    // The comparison in `hasActivated` reads a height as UNSIGNED, and nothing
    // else pins that: `Activation` summons its own `Ordering[UInt64]` and its
    // property spec pins that one, while `UpgradeSchedule` summons a second
    // independently. Read as signed, every height at or above 2^63 is negative,
    // so no activation below it has been reached and the answer falls back to
    // genesis -- a wrong rule set, returned without throwing, which is the
    // divergence class that costs the most to find.
    //
    // The existing high-height cases cannot see this: their schedules carry no
    // reachable later activation, so genesis is the right answer under either
    // reading. This one carries one, so the two readings disagree.
    assert(
      built(genesis, later).at(UInt64.MaxValue, UInt64.Zero) eq secondRules,
      "a height above 2^63 resolved as though no activation below it had been reached"
    )

  it should "do so at the boundary as well as at the top" in
    // 2^63 exactly -- the first height whose high bit is set, and the one a
    // signed reading turns into the most negative value rather than the largest.
    assert(
      built(genesis, later).at(UInt64.fromBits(Long.MinValue), UInt64.Zero) eq secondRules,
      "the first height with the high bit set resolved as though it were below every activation"
    )

  "two upgrades sharing block zero" should "resolve to the one written last" in
    // Block zero is the one activation the construction check reads separately:
    // `startsAtGenesis` takes the FIRST scheduled entry, while `at` folds every
    // entry that has activated -- and at height zero that is both of them. So
    // the seed is overwritten by the fold rather than leaking into the answer,
    // and the rule for a shared activation holds at genesis exactly as it holds
    // anywhere else.
    assert(
      built(
        entry(atBlock(0), "Start"),
        entry(atBlock(0), "Alongside", Upgrade.ProtocolChange(secondRules))
      ).at(number(0), UInt64.Zero) eq secondRules,
      "the entry written last at block zero did not state the rules in force there"
    )

  "two upgrades sharing one activation" should "be one fork point" in
    assert(
      built(
        genesis,
        entry(atBlock(100), "First", Upgrade.ProtocolChange(secondRules)),
        entry(atBlock(100), "Second", Upgrade.ProtocolChange(thirdRules))
      ).forkPoints == Vector(atBlock(100)),
      "the identifier is computed over the points that have passed, and one point reached twice is one point"
    )
