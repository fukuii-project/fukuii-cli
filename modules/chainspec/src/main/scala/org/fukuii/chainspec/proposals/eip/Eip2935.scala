package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.execution.ExecutionRules

/** EIP-2935 -- a block's own parent hash, saved where a later block can still
  * read it.
  *
  * ==The rule is a pre-execution system call, and its gate is not a header
  * field==
  *
  * *"At the start of processing any block where this EIP is active"*
  * (`ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-2935.md:38`) the block calls the
  * history-storage account with its parent's hash. **The document adds no header
  * element**, so the gate EIP-4788 uses -- a header facet, selected because that
  * proposal does add one -- cannot carry this one, and the flag lives on
  * `ExecutionRules` instead. That record's own scaladoc carries the survey
  * behind the placement and the trigger that would reverse it.
  *
  * ==Its own pseudocode gates on fork activation and says the value is not the
  * document's==
  *
  * `eip-2935.md:199` tests `block.timestamp >= FORK_TIMESTAMP` with the comment
  * `// FORK_TIMESTAMP should be defined outside of the EIP` -- which is the
  * proposal itself saying the activation point is a schedule's to state and not
  * a constant to carry here.
  *
  * ==The account's address is fixed, unlike the deposit contract's==
  *
  * `org.fukuii.execution.SystemCall.Target.HistoryStorage` holds it. One client
  * makes it configurable and **no network in this project's reference corpus
  * overrides it** -- measured against that same client's shipped configurations,
  * where nine do override the deposit contract's address, so the zero
  * discriminates rather than reporting a sweep that could not fire.
  */
object Eip2935:

  /** A block at these rules records its parent's hash before it runs anything. */
  val parentHashRecorded: ExecutionRules => ExecutionRules = _.copy(recordsParentBlockHash = true)

  /** Adopting the document, which over a rule set is adopting its one delta.
    *
    * Built from the general constructor rather than the machine-scoped one, for
    * the reason [[Eip4788]] states: a system call runs beside the machine rather
    * than inside it, so this proposal adds no operation and moves no price.
    *
    * **The opcode it serves is unchanged**, which is the easy thing to get wrong
    * here. `BLOCKHASH` still answers over the machine's own window; what this
    * adds is the state a reader needs in order to answer beyond it, and wiring
    * such a reader is the caller's, since nothing in this build holds a chain.
    */
  val component: Component =
    Component(ProposalId.Eip(2935), rules => rules.copy(execution = parentHashRecorded(rules.execution)))
