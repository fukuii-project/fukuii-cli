package org.fukuii.chainspec.networks

import org.fukuii.chainspec.ProposalId
import org.fukuii.evm.{Cost, Opcode, OpcodeTable, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** Properties every rule set this network composed has to hold. */
class EthereumClassicSpec extends AnyFlatSpec:

  private val composed =
    Vector(EthereumClassic.frontier, EthereumClassic.homestead, EthereumClassic.gasReprice)

  /** What a table charges for `opcode` before it runs, where that is settled. */
  private def settledCost(table: OpcodeTable, opcode: Opcode): Option[BigInt] =
    table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  "every rule set this network composes" should "leave SELFDESTRUCT working out its own price" in {
    // Nothing reads this entry's cost, so a proposal settling one would compile,
    // pass, and be charged the schedule's figure instead -- silently, and in the
    // one direction the interpreter's own unpriced-operation guard cannot see.
    val settled = composed.filter(spec => settledCost(spec.evm.table, Opcode.SelfDestruct).isDefined)
    assert(settled.isEmpty, "a fork settled a price for an operation that works out its own")
  }

  it should "record exactly the proposals it adopted, in the order it adopted them" in
    // The component list is what determines the rules, so a composition whose
    // list disagrees with what it applied cannot be compared against another
    // network's meaningfully.
    assert(
      EthereumClassic.frontier.components == Vector.empty &&
        EthereumClassic.homestead.components == Vector(ProposalId.Eip(7), ProposalId.Eip(2)) &&
        EthereumClassic.gasReprice.components ==
        Vector(ProposalId.Eip(7), ProposalId.Eip(2), ProposalId.Eip(150)),
      "a composition's recorded components are not the ones it adopted"
    )

  it should "carry the rule's proposal series rather than the document this network adopted it by" in
    // Two levels, and the schedule holds the other one: the rule is EIP-150 and
    // the document is ECIP-1015, which is this network's label for the upgrade
    // and not a component of it.
    assert(
      !EthereumClassic.gasReprice.components.contains(ProposalId.Ecip(1015)),
      "an adoption document reached a component list, which records what determines the rules"
    )

  it should "run the original instruction set with nothing added at genesis" in
    // What it launched with is the root with no proposal over it, and the
    // delegating byte -- which arrived with EIP-7 -- is the observable
    // difference between that and its next rule set.
    assert(
      !EthereumClassic.frontier.evm.table.contains(Opcode.DelegateCall) &&
        EthereumClassic.homestead.evm.table.contains(Opcode.DelegateCall),
      "the genesis table already ran an operation a later proposal introduced"
    )
