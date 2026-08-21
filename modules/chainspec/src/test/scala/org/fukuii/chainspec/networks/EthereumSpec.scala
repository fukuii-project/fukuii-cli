package org.fukuii.chainspec.networks

import org.fukuii.chainspec.ProposalId
import org.fukuii.evm.{Cost, Opcode, OpcodeTable, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** Properties every rule set this network composed has to hold. */
class EthereumSpec extends AnyFlatSpec:

  private val composed = Vector(Ethereum.frontier, Ethereum.homestead, Ethereum.tangerineWhistle)

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
    val settled = composed.filter(spec => settledCost(spec.evm.table, Opcode.SelfDestruct).isDefined)
    assert(settled.isEmpty, "a fork settled a price for an operation that works out its own")
  }

  it should "record exactly the proposals it adopted, in the order it adopted them" in
    // The component list is what determines the rules, so a composition whose
    // list disagrees with what it applied cannot be compared against another
    // network's meaningfully.
    assert(
      Ethereum.frontier.components == Vector.empty &&
        Ethereum.homestead.components == Vector(ProposalId.Eip(7), ProposalId.Eip(2)) &&
        Ethereum.tangerineWhistle.components == Vector(ProposalId.Eip(7), ProposalId.Eip(2), ProposalId.Eip(150)),
      "a composition's recorded components are not the ones it adopted"
    )

  it should "run the original instruction set with nothing added at genesis" in
    // The claim that makes this a network's configuration rather than the
    // machine's: what it launched with is the root with no proposal over it, and
    // the delegating byte -- which arrived with EIP-7 -- is the observable
    // difference between that and its next rule set.
    assert(
      !Ethereum.frontier.evm.table.contains(Opcode.DelegateCall) &&
        Ethereum.homestead.evm.table.contains(Opcode.DelegateCall),
      "the genesis table already ran an operation a later proposal introduced"
    )
