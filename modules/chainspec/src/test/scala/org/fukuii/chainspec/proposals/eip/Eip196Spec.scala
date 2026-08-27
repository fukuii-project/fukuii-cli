package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Precompile, PrecompileSet}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-196 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip196.component]], because the wiring is what is untested==
  *
  * The delta is reachable on its own and a spec calling it directly passes with
  * the component wired to nothing. What a network adopts is the component.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The two addresses and the two prices below are read from the proposal's own
  * text. What the natives COMPUTE -- the encoding, the padding, the refusals --
  * is the machine's, and `org.fukuii.evm`'s `AltBn128PrecompileSpec` and
  * `AltBn128PrecompilePropSpec` certify it there against published corpora.
  */
class Eip196Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.spuriousDragon

  private val adopted: UpgradeRules = base.adopting(Eip196.component)

  private val schedule = base.evm.schedule

  "adopting EIP-196" should "place a native at each of the two addresses the document names" in
    // "Address of ADD: 0x6 ... Address for MUL: 0x7", ethereum/EIPs @
    // dbfa6bee83, EIPS/eip-196.md, Final.
    assert(
      adopted.evm.precompiles.at(PrecompileSet.AltBn128Add).isDefined &&
        adopted.evm.precompiles.at(PrecompileSet.AltBn128Mul).isDefined,
      "one of the two addresses still runs whatever code the account holds"
    )

  it should "build each with the price the schedule states" in
    // The document gives 500 and 40000 under # Gas costs and the network's
    // schedule is what holds them, so the assertion is that each entry was
    // built from the schedule rather than that it was built with a figure. A
    // delta naming the figure would pass a check written the other way round
    // and would then ignore a network that stated a different one.
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.AltBn128Add)
        .contains(Precompile.AltBn128Add(schedule.precompileAltBn128Add)) &&
        adopted.evm.precompiles
          .at(PrecompileSet.AltBn128Mul)
          .contains(Precompile.AltBn128Mul(schedule.precompileAltBn128Mul)),
      "an address answers something other than this document's native, or at some other price"
    )

  it should "read both prices out of the rules it is applied to" in {
    // The case above cannot see a delta naming the network's own figures, so
    // the delta is applied a second time to rules stating something else. The
    // two are moved to DIFFERENT values, because one figure moved into both
    // entries would pass a delta that read one price and used it twice.
    val elsewhere =
      base.copy(evm = base.evm.copy(schedule = schedule.copy(precompileAltBn128Add = 11, precompileAltBn128Mul = 13)))
    val theirs = elsewhere.adopting(Eip196.component).evm.precompiles
    assert(
      theirs.at(PrecompileSet.AltBn128Add).contains(Precompile.AltBn128Add(BigInt(11))) &&
        theirs.at(PrecompileSet.AltBn128Mul).contains(Precompile.AltBn128Mul(BigInt(13))),
      "a network stating its own prices got someone else's, or got one of its own twice"
    )
  }

  it should "have run nothing at either address before it was adopted" in
    // The control. Without it the cases above pass against a set that already
    // carried the entries, and an absent entry is what every height before this
    // document runs.
    assert(
      base.evm.precompiles.at(PrecompileSet.AltBn128Add).isEmpty &&
        base.evm.precompiles.at(PrecompileSet.AltBn128Mul).isEmpty,
      "the preceding set already answered at one of these addresses"
    )

  it should "add exactly two entries" in
    assert(
      adopted.evm.precompiles.size == base.evm.precompiles.size + 2,
      "adopting a document that adds two natives moved the set by some other amount"
    )

  it should "reach neither the address the previous document placed nor the one the next does" in
    assert(
      adopted.evm.precompiles.at(PrecompileSet.ModExp) == base.evm.precompiles.at(PrecompileSet.ModExp) &&
        adopted.evm.precompiles.at(PrecompileSet.AltBn128PairingCheck).isEmpty,
      "this document reached an address it does not name"
    )

  it should "settle those entries and nothing else in the machine" in
    // Stated as the whole record rather than as spot checks, so a member
    // reached by accident fails as loudly as a named one failing to move. The
    // operation table is the one a reader most expects to move and is the one
    // this document cannot reach: a native has no byte in the instruction set.
    assert(
      adopted.evm == base.evm.copy(precompiles =
        base.evm.precompiles
          .adding(PrecompileSet.AltBn128Add, Precompile.AltBn128Add(schedule.precompileAltBn128Add))
          .adding(PrecompileSet.AltBn128Mul, Precompile.AltBn128Mul(schedule.precompileAltBn128Mul))
      ),
      "the adopting rules differ from the earlier ones by something other than the two entries"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    // Both prices are members the schedule already holds, so this document adds
    // none. A delta that rebuilt the record would be indistinguishable by value
    // from one that did not, which is why the claim is identity.
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a document whose prices are ones the schedule already holds rebuilt it"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a native added to the machine altered a facet the document does not name"
    )
