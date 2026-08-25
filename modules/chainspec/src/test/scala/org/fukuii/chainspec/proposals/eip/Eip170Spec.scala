package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-170 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip170.component]], because the wiring is what is untested==
  *
  * The delta is reachable on its own and a spec calling it directly passes with
  * the component wired to nothing. What a network adopts is the component.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The figure below is read from the proposal's own text. What the machine does
  * with a bound -- that the comparison is strictly greater, that a refusal is
  * not softened by the deposit rule beside it -- is the machine's, and
  * `org.fukuii.evm.InvocationSpec` certifies it against a bound belonging to no
  * network.
  */
class Eip170Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.tangerineWhistle

  private val adopted: UpgradeRules = base.adopting(Eip170.component)

  "adopting EIP-170" should "bound deployed code at 24,576 bytes" in
    // 0x6000, which the document also writes as 2**14 + 2**13. Two sources that
    // do not derive from one another: MAX_CODE_SIZE = 0x6000 in
    // ethereum/execution-specs @ ccaaaba58
    // forks/spurious_dragon/vm/interpreter.py, and MaxCodeSize = 24576 in
    // ethereum/go-ethereum-pow @ v1.10.26 params/protocol_params.go.
    assert(
      adopted.evm.maxCodeSize.contains(24576),
      "the bound the proposal names is not the one adopting it produced"
    )

  it should "have bounded nothing before it was adopted" in
    // The control. Without it the case above passes against rules that already
    // carried a bound, and an absent bound is what every height before this
    // document runs.
    assert(
      base.evm.maxCodeSize.isEmpty,
      "the preceding rules already bounded deployed code, which no earlier proposal does"
    )

  it should "settle that bound and nothing else in the machine" in
    // Stated as the whole record rather than as spot checks, so a member reached
    // by accident fails as loudly as the named one failing to move.
    assert(
      adopted.evm == base.evm.copy(maxCodeSize = Some(24576)),
      "the bounded rules differ from the earlier ones by something other than the bound"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    // A bound is not a price, so this document reaches the schedule nowhere. A
    // delta that rebuilt it would be indistinguishable by value from one that
    // did not, which is why the claim is identity.
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a rule about what a creation may leave behind rebuilt the price list"
    )

  it should "leave the table as the same value" in
    assert(
      adopted.evm.table eq base.evm.table,
      "a bound carried by the rules rebuilt the instruction set"
    )

  it should "leave the precompile prices as the same value" in
    assert(
      adopted.evm.precompiles eq base.evm.precompiles,
      "a bound carried by the rules rebuilt the precompile set"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a bound on deployed code altered a facet it does not name"
    )

  it should "leave the rule about an unaffordable deposit where it found it" in
    // The member this one sits beside, and the one a reader is most likely to
    // conflate it with. EIP-2 settles what an unaffordable deposit does; this
    // document settles a bound, and neither moves the other.
    assert(
      adopted.evm.codeDepositMustSucceed == base.evm.codeDepositMustSucceed,
      "adopting a bound on deployed code changed what an unaffordable deposit does"
    )
