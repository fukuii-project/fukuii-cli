package org.fukuii.chainspec.networks.ethereum

import org.fukuii.bytes.{UInt256, UInt64}
import org.fukuii.chainspec.ProposalId
import org.fukuii.evm.{Cost, NewAccountCharge, Opcode, OpcodeTable, Operation, PrecompileSet}
import org.scalatest.flatspec.AnyFlatSpec

/** Properties every rule set this network composed has to hold. */
class UpgradesSpec extends AnyFlatSpec:

  private val composed =
    Vector(Upgrades.frontier, Upgrades.homestead, Upgrades.tangerineWhistle, Upgrades.spuriousDragon)

  /** What a table charges for `opcode` before it runs, where that is settled. */
  private def settledCost(table: OpcodeTable, opcode: Opcode): Option[BigInt] =
    table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  "every rule set this network composes" should "leave SELFDESTRUCT working out its own price" in {
    // The mirror of the two-homes hazard, in the direction the schedule's own
    // taxonomy does not cover. Nothing reads this entry's cost, so a proposal
    // settling one -- `table.adding(Operation(Opcode.SelfDestruct,
    // Cost.Fixed(n)))` -- would compile, pass, and be charged the schedule's
    // figure instead of `n`, silently. The guard that catches the opposite
    // mismatch is the interpreter reporting an operation it cannot price; there
    // is none in this direction, so it is asserted across every composition
    // rather than at the first alone.
    val settled = composed.filter(rules => settledCost(rules.evm.table, Opcode.SelfDestruct).isDefined)
    assert(settled.isEmpty, "a fork settled a price for an operation that works out its own")
  }

  it should "record exactly the proposals it adopted, in the order it adopted them" in
    // The component list is what determines the rules, so a composition whose
    // list disagrees with what it applied cannot be compared against another
    // network's meaningfully.
    assert(
      Upgrades.frontier.components == Vector.empty &&
        Upgrades.homestead.components == Vector(ProposalId.Eip(7), ProposalId.Eip(2)) &&
        Upgrades.tangerineWhistle.components == Vector(ProposalId.Eip(7), ProposalId.Eip(2), ProposalId.Eip(150)) &&
        Upgrades.spuriousDragon.components ==
        Vector(
          ProposalId.Eip(7),
          ProposalId.Eip(2),
          ProposalId.Eip(150),
          ProposalId.Eip(155),
          ProposalId.Eip(160),
          ProposalId.Eip(161),
          ProposalId.Eip(170)
        ),
      "a composition's recorded components are not the ones it adopted"
    )

  it should "carry every clause of EIP-161 at the upgrade that adopted it" in
    // Asserted at the NETWORK rather than at the component, because the
    // settlement member here sat on the record with no production reader and no
    // network setting it, and a component nothing adopts leaves it exactly
    // there. All four in one case on purpose: the document's clause (c) is
    // satisfied by clause (d) rather than by a member of its own, so a rule set
    // carrying the machine's three and not the settlement's would claim a clause
    // it does not have.
    assert(
      Upgrades.spuriousDragon.evm.createdAccountNonce == UInt64.fromBits(1L) &&
        Upgrades.spuriousDragon.evm.newAccountCharge == NewAccountCharge.WhenValueReachesADeadDestination &&
        Upgrades.spuriousDragon.evm.touchSurvivesFailure == Set(PrecompileSet.Ripemd160) &&
        Upgrades.spuriousDragon.execution.touchedEmptyAccountsAreDeleted,
      "the upgrade that adopts EIP-161 does not carry one of its clauses"
    )

  it should "have carried none of them at the upgrade before it" in
    // The control. Without it the case above holds for a network that had them
    // all along, and no earlier proposal sets any of the four.
    assert(
      Upgrades.tangerineWhistle.evm.createdAccountNonce == UInt64.Zero &&
        Upgrades.tangerineWhistle.evm.newAccountCharge == NewAccountCharge.WhenTheDestinationIsAbsent &&
        Upgrades.tangerineWhistle.evm.touchSurvivesFailure.isEmpty &&
        !Upgrades.tangerineWhistle.execution.touchedEmptyAccountsAreDeleted,
      "a clause of EIP-161 was in force before the upgrade that adopts it"
    )

  it should "run the original instruction set with nothing added at genesis" in
    // The claim that makes this a network's configuration rather than the
    // machine's: what it launched with is the root with no proposal over it, and
    // the delegating byte -- which arrived with EIP-7 -- is the observable
    // difference between that and its next rule set.
    assert(
      !Upgrades.frontier.evm.table.contains(Opcode.DelegateCall) &&
        Upgrades.homestead.evm.table.contains(Opcode.DelegateCall),
      "the genesis table already ran an operation a later proposal introduced"
    )

  it should "pay five ether for a block at every height this build reaches" in
    // Two sources that do not derive from one another: BLOCK_REWARD in
    // ethereum/execution-specs @ ccaaaba58 forks/frontier/fork.py:58, and
    // FrontierBlockReward in ethereum/go-ethereum-pow @ v1.10.26
    // consensus/ethash/consensus.go:42. The amount drops to three ether at
    // Byzantium and two at Constantinople, neither of which is in this build.
    assert(
      composed.forall(rules =>
        rules.consensus.blockReward == UInt256.fromBigInt(BigInt(5) * BigInt(10).pow(18)).toOption.get
      ),
      "a rule set here pays something other than the launch amount, which no proposal this build adopts changes"
    )

  it should "credit a beneficiary even where the amount is zero, at every height this build reaches" in
    // This build now reaches the fork besu flips this at:
    // .skipZeroBlockRewards(false) at its Frontier definition becomes true at
    // spuriousDragonDefinition, because from there an empty account a zero
    // credit created has to be deleted again. The specification takes the other
    // route and keeps crediting unconditionally --
    // ethereum/execution-specs @ ccaaaba58 forks/spurious_dragon/fork.py's
    // pay_rewards calls create_ether with no zero check, exactly as its Frontier
    // copy does -- and reaches the same observable through that deletion. The
    // two agree here for a third reason as well: BLOCK_REWARD is five ether at
    // every height this build reaches, so no composition below can produce the
    // zero this member is about.
    assert(
      composed.forall(rules => rules.consensus.zeroRewardCreditsBeneficiary),
      "declining to credit before empty accounts are deleted would leave a leaf out of the state trie"
    )
