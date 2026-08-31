package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{GasSchedule, Precompile, PrecompileSet}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-1108 changes, and the one property the rest of this file
  * exists to protect.
  *
  * ==THE ENTRIES ARE THE POINT OF THIS SPEC==
  *
  * All four figures this document moves are read where a native is BUILT and
  * nowhere else, so a delta that moved the schedule and stopped would be a
  * COMPLETE silent no-op: three natives charging Byzantium's prices, a record
  * saying otherwise, and nothing anywhere reading the difference. A spec
  * asserting only the four schedule members would agree with that delta at
  * every case and certify a fork that reprices nothing.
  *
  * So every schedule case below is paired with one over the entry built from
  * it, and the pairing is what carries the claim. `org.fukuii.evm.GasSchedule`
  * states the three classes a price falls into and how to re-derive which is
  * which; this document is the first in the tree whose whole delta is in the
  * third.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The four figures below are read from the proposal's own Specification table.
  * What the natives COMPUTE is the machine's, and `org.fukuii.evm`'s
  * `AltBn128PrecompileSpec` and `AltBn128PrecompilePropSpec` certify it there
  * against published corpora.
  */
class Eip1108Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.petersburg

  private val adopted: UpgradeRules = base.adopting(Eip1108.component)

  private val before: GasSchedule = base.evm.schedule

  private val after: GasSchedule = adopted.evm.schedule

  "adopting EIP-1108" should "move all four figures the document's table names" in
    // "| `ECADD` | `0x06` | 500 | 150 |", "| `ECMUL` | `0x07` | 40 000 | 6 000
    // |" and "| Pairing check | `0x08` | 80 000 * k + 100 000 | 34 000 * k + 45
    // 000 |", ethereum/EIPs @ dbfa6bee, EIPS/eip-1108.md, Final, under
    // ## Specification. Asserted as this network's own literals rather than
    // against another network's schedule: every genesis figure these four move
    // from is shared, so a comparison across networks would go on agreeing with
    // a mutation that moved both.
    assert(
      after.precompileAltBn128Add == BigInt(150) && after.precompileAltBn128Mul == BigInt(6000) &&
        after.precompileAltBn128PairingBase == BigInt(45000) &&
        after.precompileAltBn128PairingPerPoint == BigInt(34000),
      "a figure the document's table names did not move, or moved to something else"
    )

  it should "NOT take the pairing figure from the benchmark paragraph below that table" in
    // The named negative. The same document says `~35,000 * k + 45,000` a few
    // lines later, closing a measured derivation and carrying the `~` that says
    // so. A delta reading the prose rather than the table would be wrong by
    // 1,000 per point and would pass every other case in this file.
    assert(
      after.precompileAltBn128PairingPerPoint != BigInt(35000),
      "the per-point figure came from the rounded derivation rather than from the Specification table"
    )

  it should "have carried the earlier figures before it was adopted" in
    // The control. Without it the case above holds for a network that stated
    // the reduced prices all along, and the figures it moves from are the ones
    // EIP-196 and EIP-197 were adopted at.
    assert(
      before.precompileAltBn128Add == BigInt(500) && before.precompileAltBn128Mul == BigInt(40000) &&
        before.precompileAltBn128PairingBase == BigInt(100000) &&
        before.precompileAltBn128PairingPerPoint == BigInt(80000),
      "a figure this document reduces was already at its reduced value"
    )

  it should "rebuild all three natives at the moved prices" in
    // THE case this spec exists for. A delta editing the record alone leaves
    // every one of these three answering what it answered at Byzantium, and
    // nothing else in the machine would disagree with it.
    assert(
      adopted.evm.precompiles.at(PrecompileSet.AltBn128Add).contains(Precompile.AltBn128Add(BigInt(150))) &&
        adopted.evm.precompiles.at(PrecompileSet.AltBn128Mul).contains(Precompile.AltBn128Mul(BigInt(6000))) &&
        adopted.evm.precompiles
          .at(PrecompileSet.AltBn128PairingCheck)
          .contains(Precompile.AltBn128PairingCheck(BigInt(45000), BigInt(34000))),
      "a native still charges the price it charged before this document was adopted"
    )

  it should "leave no native charging a price its own record has moved away from" in
    // The same claim as the pairing of the two cases above, stated as the
    // agreement rather than as two sets of literals -- so a future repricing
    // that moves the figures and forgets one entry fails here even if both
    // literal cases are updated to match it.
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.AltBn128Add)
        .contains(
          Precompile.AltBn128Add(after.precompileAltBn128Add)
        ) &&
        adopted.evm.precompiles
          .at(PrecompileSet.AltBn128Mul)
          .contains(
            Precompile.AltBn128Mul(after.precompileAltBn128Mul)
          ) &&
        adopted.evm.precompiles
          .at(PrecompileSet.AltBn128PairingCheck)
          .contains(
            Precompile
              .AltBn128PairingCheck(after.precompileAltBn128PairingBase, after.precompileAltBn128PairingPerPoint)
          ),
      "a native and the record it is priced from disagree"
    )

  it should "read the moved figures out of the rules it is applied to" in {
    // The cases above cannot see a delta that names the four literals in the
    // entries as well as in the record. So the delta is applied to rules whose
    // natives are then compared against THAT run's schedule: a delta building
    // from literals answers 150 and 6000 here too, and one building from its
    // own repriced copy answers whatever it wrote.
    val moved = adopted.evm.precompiles
    assert(
      moved.at(PrecompileSet.AltBn128Add).contains(Precompile.AltBn128Add(after.precompileAltBn128Add)) &&
        !moved.at(PrecompileSet.AltBn128Add).contains(Precompile.AltBn128Add(before.precompileAltBn128Add)),
      "the rebuilt entry was read back off the record from before the repricing"
    )
  }

  it should "replace the three entries rather than adding any" in
    // An entry placed at an occupied address replaces what was there, so a
    // document that reprices three natives must move the set's size by nothing
    // at all. A delta that added would leave the old prices unreachable rather
    // than gone, which is a different bug with the same passing cases above.
    assert(
      adopted.evm.precompiles.size == base.evm.precompiles.size,
      "repricing three natives changed how many addresses the network answers at"
    )

  it should "leave the natives it does not name exactly as it found them" in
    assert(
      Seq(
        PrecompileSet.EcRecover,
        PrecompileSet.Sha256,
        PrecompileSet.Ripemd160,
        PrecompileSet.Identity,
        PrecompileSet.ModExp
      ).forall(address => adopted.evm.precompiles.at(address) == base.evm.precompiles.at(address)),
      "repricing the curve natives disturbed one this document does not name"
    )

  it should "move no figure the document's table does not name" in
    // Stated as the whole record rather than as spot checks, so a price reached
    // by accident fails as loudly as one of the four failing to move.
    assert(
      after == before.copy(
        precompileAltBn128Add = BigInt(150),
        precompileAltBn128Mul = BigInt(6000),
        precompileAltBn128PairingBase = BigInt(45000),
        precompileAltBn128PairingPerPoint = BigInt(34000)
      ),
      "the repriced record differs from the earlier one by something other than the four figures"
    )

  it should "leave the instruction table as the same value" in
    // None of the three has a byte in the instruction set, so the table cannot
    // be where this repricing lands.
    assert(adopted.evm.table eq base.evm.table, "a repricing of natives reached the instruction table")

  it should "settle those entries and nothing else in the machine" in
    assert(
      adopted.evm == base.evm.copy(
        schedule = after,
        precompiles = base.evm.precompiles
          .adding(PrecompileSet.AltBn128Add, Precompile.AltBn128Add(BigInt(150)))
          .adding(PrecompileSet.AltBn128Mul, Precompile.AltBn128Mul(BigInt(6000)))
          .adding(PrecompileSet.AltBn128PairingCheck, Precompile.AltBn128PairingCheck(BigInt(45000), BigInt(34000)))
      ),
      "the adopting rules differ from the earlier ones by something other than the record and the three entries"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a repricing inside the machine altered a facet the document does not name"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(1108)), "the journal must record what was adopted")
