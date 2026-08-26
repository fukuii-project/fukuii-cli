package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{NewAccountCharge, PrecompileSet}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-161 changes, across the two facets it reaches.
  *
  * ==Through [[Eip161.component]], because the wiring is what is untested==
  *
  * Each delta is reachable on its own and a spec calling one directly passes
  * with the component wired to nothing. What a network adopts is the component,
  * and this document is the first whose deltas span the machine and the
  * settlement around it -- so a component reaching only one of the two is the
  * failure that would otherwise land silently.
  *
  * ==Here rather than with the machine or the settlement, because these are the
  * document's claims==
  *
  * What the machine does with a starting count, a surcharge condition and an
  * exempt address is the machine's, and `org.fukuii.evm.InvocationSpec` certifies
  * it against values belonging to no network. What a settlement does with the
  * clearing rule is `org.fukuii.execution.TransactionProcessorSpec`'s.
  */
class Eip161Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.tangerineWhistle

  private val adopted: UpgradeRules = base.adopting(Eip161.component)

  // ── Clause (a) ────────────────────────────────────────────────────────────

  "adopting EIP-161" should "give a created account a count of one" in
    // The document: a creation SHALL "increment the nonce over and above its
    // normal starting value by one", where that starting value is zero on both
    // networks in scope.
    assert(
      adopted.evm.createdAccountNonce == UInt64.fromBits(1L),
      "a created account starts at a count no proposal gave it"
    )

  it should "have created accounts at nothing before it was adopted" in
    // The control. Without it the case above passes against rules that already
    // raised the count, and no earlier proposal does.
    assert(
      base.evm.createdAccountNonce == UInt64.Zero,
      "the preceding rules already raised the count a created account starts at"
    )

  // ── Clause (b) ────────────────────────────────────────────────────────────

  it should "levy the surcharge only where value reaches a dead destination" in
    assert(
      adopted.evm.newAccountCharge == NewAccountCharge.WhenValueReachesADeadDestination,
      "the condition the document replaces is still the one in force"
    )

  it should "have levied it on any absent destination before it was adopted" in
    assert(
      base.evm.newAccountCharge == NewAccountCharge.WhenTheDestinationIsAbsent,
      "the preceding rules already read the surcharge the later way"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    // The claim most likely to be got wrong by a reader and by an editor alike.
    // The document changes WHEN the surcharge is levied and never WHAT it costs,
    // so it is not a repricing and reaches the price list nowhere. A delta that
    // rebuilt it would be indistinguishable by value from one that did not,
    // which is why the claim is identity.
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a change to a surcharge's condition rebuilt the price list"
    )

  // ── Clause (d), in the machine ───────────────────────────────────────────

  it should "exempt the address the RIPEMD-160 native answers at from the revert" in
    // The document's Addendum makes empty-account deletions revert with the
    // state, and its References note 3 records the exception preserved with it:
    // the amended behavior was made to match a second client's, whose deviation
    // was "in a more limited set of contexts involving out-of-gas calls to
    // precompiled contracts".
    assert(
      adopted.evm.touchSurvivesFailure == Set(PrecompileSet.Ripemd160),
      "the one address the field exempts is not the one adopting this produced"
    )

  it should "have exempted nothing before it was adopted" in
    assert(
      base.evm.touchSurvivesFailure.isEmpty,
      "the preceding rules already exempted an address from a revert rule they do not have"
    )

  it should "settle those three in the machine and nothing else" in
    // Stated as the whole record rather than as spot checks, so a member reached
    // by accident fails as loudly as a named one failing to move.
    assert(
      adopted.evm == base.evm.copy(
        createdAccountNonce = UInt64.fromBits(1L),
        newAccountCharge = NewAccountCharge.WhenValueReachesADeadDestination,
        touchSurvivesFailure = Set(PrecompileSet.Ripemd160)
      ),
      "the adopted rules differ from the earlier ones by something other than the document's three machine deltas"
    )

  it should "leave the table as the same value" in
    assert(
      adopted.evm.table eq base.evm.table,
      "a document adding no operation rebuilt the instruction set"
    )

  it should "leave the precompile prices as the same value" in
    // Named because this is the document that puts a precompile's ADDRESS into
    // the rules. What that native costs is untouched, and the two are separate
    // things at the same address.
    assert(
      adopted.evm.precompiles eq base.evm.precompiles,
      "naming a native's address in a revert exemption rebuilt the precompile set"
    )

  // ── Clause (d), at settlement ────────────────────────────────────────────

  it should "delete an account a transaction reached and left holding nothing" in
    assert(
      adopted.execution.touchedEmptyAccountsAreDeleted,
      "the clearing rule the document's fourth clause states is not in force after adopting it"
    )

  it should "have deleted none before it was adopted" in
    assert(
      !base.execution.touchedEmptyAccountsAreDeleted,
      "the preceding rules already cleared, which no earlier proposal does"
    )

  it should "settle that one at settlement and nothing else" in
    assert(
      adopted.execution == base.execution.copy(touchedEmptyAccountsAreDeleted = true),
      "a document whose settlement delta is one rule reached a second member of that facet"
    )

  // ── The facets it does not reach ─────────────────────────────────────────

  it should "reach neither what admits a transaction nor what consensus settles" in
    assert(
      (adopted.admission eq base.admission) && (adopted.consensus eq base.consensus),
      "a document confined to the machine and the settlement altered a facet it does not name"
    )
