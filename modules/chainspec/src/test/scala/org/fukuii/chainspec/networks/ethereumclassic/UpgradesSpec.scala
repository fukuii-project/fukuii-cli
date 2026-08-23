package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.ProposalId
import org.fukuii.evm.{Cost, Opcode, OpcodeTable, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** Properties every rule set this network composed has to hold. */
class UpgradesSpec extends AnyFlatSpec:

  private val composed =
    Vector(Upgrades.frontier, Upgrades.homestead, Upgrades.gasReprice)

  /** What a table charges for `opcode` before it runs, where that is settled. */
  private def settledCost(table: OpcodeTable, opcode: Opcode): Option[BigInt] =
    table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  "every rule set this network composes" should "leave SELFDESTRUCT working out its own price" in {
    // Nothing reads this entry's cost, so a proposal settling one would compile,
    // pass, and be charged the schedule's figure instead -- silently, and in the
    // one direction the interpreter's own unpriced-operation guard cannot see.
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
        Upgrades.gasReprice.components ==
        Vector(ProposalId.Eip(7), ProposalId.Eip(2), ProposalId.Eip(150)),
      "a composition's recorded components are not the ones it adopted"
    )

  it should "carry the rule's proposal series rather than the document this network adopted it by" in
    // Two levels, and the schedule holds the other one: the rule is EIP-150 and
    // the document is ECIP-1015, which is this network's label for the upgrade
    // and not a component of it.
    assert(
      !Upgrades.gasReprice.components.contains(ProposalId.Ecip(1015)),
      "an adoption document reached a component list, which records what determines the rules"
    )

  it should "run the original instruction set with nothing added at genesis" in
    // What it launched with is the root with no proposal over it, and the
    // delegating byte -- which arrived with EIP-7 -- is the observable
    // difference between that and its next rule set.
    assert(
      !Upgrades.frontier.evm.table.contains(Opcode.DelegateCall) &&
        Upgrades.homestead.evm.table.contains(Opcode.DelegateCall),
      "the genesis table already ran an operation a later proposal introduced"
    )

  it should "pay five ether for a block at every height this build reaches" in
    // Two lineages that do not derive from one another: blockReward in
    // openethereum/openethereum @ v3.0.1 ethcore/res/ethereum/classic.json, and
    // MAX_BLOCK_REWARD = Wei.fromEth(5) in besu-eth/besu-etc @ eb4248c99
    // ClassicProtocolSpecs.java:60. It is the base ECIP-1017's ladder reduces
    // rather than a figure that proposal replaces, and this build reaches no
    // height at which the ladder has stepped.
    assert(
      composed.forall(rules =>
        rules.consensus.blockReward == UInt256.fromBigInt(BigInt(5) * BigInt(10).pow(18)).toOption.get
      ),
      "a rule set here pays something other than the amount this network's own proposal takes as its first era"
    )

  it should "credit a beneficiary even where the amount is zero, at every height this build reaches" in
    // The same answer as any network at a height before touched empty accounts
    // are deleted, and unobservable at five ether. It is stated because the
    // field states it rather than leaving it to the default.
    assert(
      composed.forall(rules => rules.consensus.zeroRewardCreditsBeneficiary),
      "declining to credit before empty accounts are deleted would leave a leaf out of the state trie"
    )
