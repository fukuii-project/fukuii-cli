package org.fukuii.chainspec.networks.ethereum

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, Upgrade, UpgradeId, UpgradeSchedule}
import org.scalatest.flatspec.AnyFlatSpec

/** What this network's schedule is made of, and the two entries that gate no
  * rule while differing about everything else.
  *
  * ==The pair is the reason the schedule's upgrade type has three cases==
  *
  * Neither Frontier Thawing nor the DAO fork changes a rule this build models,
  * so nothing in [[MainnetPropSpec]]'s resolution table can tell them apart.
  * They separate at the fork identifier: one of them is a point at which two
  * nodes can disagree about validity and the other is not. Asserting both
  * directions here is what stops a projection keyed on *"does this change the
  * rules"* passing, which is the natural guess and is wrong on both entries at
  * once.
  *
  * The activation figures themselves are a matrix and live in
  * [[MainnetPropSpec]]; this holds the structural facts.
  */
class MainnetSpec extends AnyFlatSpec:

  private val schedule: UpgradeSchedule =
    Mainnet.schedule.getOrElse(fail("the authored entries do not form a schedule"))

  private def label(entry: org.fukuii.chainspec.UpgradeSchedule.Entry): String = entry.id.label match
    case UpgradeId.Label.Named(text) => text
    case UpgradeId.Label.Synthesized => "Synthesized"

  private def entryNamed(text: String): org.fukuii.chainspec.UpgradeSchedule.Entry =
    schedule.entries.find(entry => label(entry) == text).getOrElse(fail("no entry named " + text))

  private val thawing: org.fukuii.chainspec.UpgradeSchedule.Entry = entryNamed("Frontier Thawing")

  private val daoFork: org.fukuii.chainspec.UpgradeSchedule.Entry = entryNamed("DAO Fork")

  "the authored entries" should "form a schedule" in
    assert(
      Mainnet.schedule.isRight,
      "every construction invariant is checked here, so a Left is this file disagreeing with itself"
    )

  "the schedule" should "carry this network's canonical enumeration in order" in
    assert(
      schedule.entries.map(label) ==
        Vector(
          "Frontier",
          "Frontier Thawing",
          "Homestead",
          "DAO Fork",
          "Tangerine Whistle",
          "Spurious Dragon",
          "Byzantium",
          // TWO entries at one height, and the ORDER of these two is the thing
          // this enumeration pins that nothing else does: swapping them leaves
          // every other assertion in this file passing while the network runs
          // EIP-1283 for ever.
          "Constantinople",
          "Petersburg",
          "Istanbul",
          "Muir Glacier",
          "Berlin"
        ),
      "an enumeration missing an entry misnumbers every entry after it, which is silent rather than absent"
    )

  it should "belong to chain id 1" in
    assert(
      schedule.network.chainId == UInt64.fromBits(1L),
      "EIP-155 registers 1 to Ethereum mainnet, and a schedule filed under another id answers every lookup wrongly"
    )

  "Frontier Thawing" should "enforce nothing" in
    assert(
      thawing.upgrade == Upgrade.Unenforced,
      "it changed the block gas limit, which a miner already set per block within bounds it did not alter"
    )

  it should "be on the schedule even so" in
    assert(
      thawing.activation == Activation.AtBlock(UInt64.fromBits(200000L)),
      "both canonical enumerations in the field carry it, and omitting it renumbers everything after it"
    )

  it should "not reach the fork identifier" in
    assert(
      !schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(200000L))),
      "a fork identifier computed over it would be wrong on every peer handshake, and wrong quietly"
    )

  "the DAO fork" should "change no rule this build models" in
    assert(
      daoFork.upgrade == Upgrade.IrregularStateChange,
      "it moved balances and required a header marker, and neither is a machine, settlement or admission rule"
    )

  it should "reach the fork identifier even so" in
    // The half that separates it from Frontier Thawing, and the reason the
    // upgrade type cannot be two-valued. EIP-2124's worked example for this
    // network runs genesis, 1150000, 1920000 -- the third of those is here.
    assert(
      schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(1920000L))),
      "a fork identifier omitting it is a number every peer computes differently, and rejects quietly"
    )

  it should "leave the rules on both sides of it identical" in
    // What makes the entry an event rather than a rule change, asserted on the
    // schedule rather than inferred from the case it carries.
    assert(
      schedule.at(UInt64.fromBits(1919999L), UInt64.Zero) eq schedule.at(UInt64.fromBits(1920000L), UInt64.Zero),
      "an upgrade that resolves to different rules across itself is a rule change, whatever case it was given"
    )

  "this network's fork points" should "be exactly the upgrades that change what a node validates" in
    assert(
      schedule.forkPoints == Vector(
        Activation.AtBlock(UInt64.fromBits(1150000L)),
        Activation.AtBlock(UInt64.fromBits(1920000L)),
        Activation.AtBlock(UInt64.fromBits(2463000L)),
        Activation.AtBlock(UInt64.fromBits(2675000L)),
        Activation.AtBlock(UInt64.fromBits(4370000L)),
        // ONCE, not twice. Two entries activate at this height and EIP-2124
        // must see one point; four production clients de-duplicate at the same
        // place and the wrong answer is silent -- the checksum is still a
        // number, and every peer rejects it as unrelated network trouble.
        Activation.AtBlock(UInt64.fromBits(7280000L)),
        Activation.AtBlock(UInt64.fromBits(9069000L)),
        // The one figure EIP-2384 has that no corpus in this build can carry.
        // Its 9,000,000 delay is certified over 4,254 published cases, and
        // those cases do span this height -- they run from block 4,819 to
        // 19,999,658 -- but every one of them is filed under a SINGLE fork key,
        // so the corpus applies one rule set across the whole span and cannot
        // disagree about where the boundary falls. None sits at 9,200,000 or
        // either side of it. So this line is the only place an activation moved
        // by any amount is caught.
        Activation.AtBlock(UInt64.fromBits(9200000L)),
        Activation.AtBlock(UInt64.fromBits(12244000L))
      ),
      "genesis is excluded by EIP-2124 and thawing by enforcing nothing, leaving the ones that are neither"
    )

  // ── The two entries that share a height ───────────────────────────────────

  "the pair at 7,280,000" should "leave Byzantium's rules in force one block below" in
    assert(
      schedule.at(UInt64.fromBits(7279999L), UInt64.Zero) == Upgrades.byzantium,
      "the block before the fork point runs the fork below it"
    )

  it should "resolve to PETERSBURG at the height itself, not to Constantinople" in
    // The entry written SECOND is the one in force, which is EIP-1716's rule:
    // "If Petersburg and Constantinople are applied at the same block,
    // Petersburg takes precedence: with the net effect of EIP-1283 being
    // disabled."
    assert(
      schedule.at(UInt64.fromBits(7280000L), UInt64.Zero) == Upgrades.petersburg,
      "the height resolves to the rule set that still carries EIP-1283"
    )

  it should "never resolve to Constantinople's rules at ANY height" in {
    // The property that makes the specified-but-never-run rule set safe to
    // hold. Sampled across the whole schedule rather than at the fork point
    // alone, so an entry inserted later that exposed it would fail here.
    val sampled = Seq(0L, 1L, 1150000L, 1920000L, 2463000L, 2675000L, 4370000L, 7279999L, 7280000L, 7280001L, 20000000L)
    assert(
      sampled.forall(height => schedule.at(UInt64.fromBits(height), UInt64.Zero) != Upgrades.constantinople),
      "a height resolves to rules Ethereum mainnet never ran"
    )
  }

  it should "be ORDER-DEPENDENT, which is why the entries are written the way they are" in {
    // THE ASSERTION THIS FILE EXISTS FOR. The two entries above differ only in
    // which is written first, and swapping them is silent: both rule sets are
    // valid, the schedule still builds, the fork identifier is unchanged, and
    // the network runs EIP-1283 for ever.
    //
    // Built here as the REVERSED schedule and shown to resolve the other way,
    // so this fails if `at` ever stops being last-wins -- which would make the
    // real schedule above wrong without touching it.
    val reversed = UpgradeSchedule.of(
      Vector(
        UpgradeSchedule.Entry(
          Activation.AtBlock(UInt64.Zero),
          UpgradeId.named(Mainnet.network, "Frontier"),
          Upgrade.RuleChange(Upgrades.frontier)
        ),
        UpgradeSchedule.Entry(
          Activation.AtBlock(UInt64.fromBits(7280000L)),
          UpgradeId.named(Mainnet.network, "Petersburg"),
          Upgrade.RuleChange(Upgrades.petersburg)
        ),
        UpgradeSchedule.Entry(
          Activation.AtBlock(UInt64.fromBits(7280000L)),
          UpgradeId.named(Mainnet.network, "Constantinople"),
          Upgrade.RuleChange(Upgrades.constantinople)
        )
      )
    )
    assert(
      reversed.map(_.at(UInt64.fromBits(7280000L), UInt64.Zero)) == Right(Upgrades.constantinople),
      "writing the two entries the other way round does NOT change what resolves, so the real schedule's order is unpinned"
    )
  }

  "the Berlin entry" should "resolve to the rules that adopt its four proposals" in
    // THE ASSERTION THAT TELLS A CORRECT WIRING FROM A WRONG ONE. Every case in
    // the four proposal specs, and every case in `UpgradesSpec`, reads a rule set
    // NAMED -- so an entry pointing at the upgrade below, or at a composition
    // built from the wrong base, satisfies all of them. Only resolving the
    // schedule at a height reads what a node at that height would run.
    assert(
      schedule.at(UInt64.fromBits(12244000L), UInt64.Zero) == Upgrades.berlin,
      "the entry at this network's Berlin height does not resolve to the rules that upgrade composes"
    )

  it should "resolve to the upgrade below it one block earlier" in
    // The control. Without it the case above holds for an entry activating at
    // genesis, and for one activating anywhere at or below this height.
    assert(
      schedule.at(UInt64.fromBits(12243999L), UInt64.Zero) == Upgrades.muirGlacier,
      "a block below this network's Berlin height resolves to rules it does not run"
    )

  it should "change what a block may carry as well as what the machine does" in
    // The first activation on this network at which a transaction FORMAT becomes
    // valid, read at the height rather than at the composition. A node one block
    // earlier rejects a block on its transactions alone.
    assert(
      !schedule
        .at(UInt64.fromBits(12243999L), UInt64.Zero)
        .admission
        .admittedTypes
        .contains(
          org.fukuii.types.TransactionType.AccessList
        ) &&
        schedule
          .at(UInt64.fromBits(12244000L), UInt64.Zero)
          .admission
          .admittedTypes
          .contains(
            org.fukuii.types.TransactionType.AccessList
          ),
      "the format the upgrade admits is valid on the wrong side of its own activation"
    )
