package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{NewAccountCharge, PrecompileSet, Proposal}
import org.fukuii.execution.ExecutionRules

/** EIP-161 -- four lettered clauses, reaching the machine and the settlement
  * around it.
  *
  * ==One document, four clauses, and only three of them are deltas==
  *
  * The document's own specification is `(a)` a created account's starting count,
  * `(b)` the condition under which a destination is charged for being brought
  * into being, `(c)` that no account may change from non-existent to
  * existent-but-empty, and `(d)` that an account reached by a transaction and
  * left holding nothing is deleted when that transaction ends (`ethereum/EIPs` @
  * `96523ef4d`, `EIPS/eip-161.md`, Final).
  *
  * **`(c)` is satisfied by `(d)` and needs no delta of its own.**
  * `ethereum/execution-specs` @ `ccaaaba58` does not implement it as a refusal
  * to create: `forks/spurious_dragon/state_tracker.py` is byte-identical to its
  * predecessor and still creates, and the fork meets the clause by creating the
  * account and deleting it again -- the *invariant-preserving alternative* the
  * document's own title names. `ethereum/go-ethereum-pow` @ `v1.10.26` takes the
  * other route, an early return in `core/vm/evm.go` with a carve-out for
  * precompiles, and the two reach the same observable state because `(b)` makes
  * the only price that could tell an absent destination from an empty one stop
  * asking. This project has the first shape.
  *
  * **So `(c)` holds only while `(d)` does.** The three deltas below are adopted
  * together and a network cannot take one without the others through this
  * component; a network that wanted to would be stating a rule set the document
  * does not describe.
  *
  * **`(b)` likewise holds only while `(a)` does, and that pairing is the field's
  * rather than this document's to enforce.** Levying the surcharge on a dead
  * destination while leaving a created account's count at zero makes a
  * deployment given no endowment dead while its own initialization code runs,
  * and a borrowing call out of it that sent something would pay a surcharge
  * neither authority charges. Neither of the two clients read here separates
  * them -- `ethereumclassic/core-geth` @ `4185df450` gates both on one
  * transition and `ethereum/go-ethereum-pow` @ `v1.10.26` on one fork test --
  * and `org.fukuii.evm.Interpreter.newAccountSurcharge` is where the
  * consequence is worked out. The deltas below are public, so composing `(b)` without `(a)`
  * is expressible; it is the configuration to suspect first if that surcharge
  * ever fires for a borrowing form.
  *
  * ==Built from the general constructor, because this document spans facets==
  *
  * `Component.evm` reaches the machine and nothing else, and `(d)` is the
  * settlement's. `Eip2` is the other document in this project that needs the
  * general form, and for the same reason.
  *
  * ==Two facets is what the field does, not a departure from it==
  *
  * `ethereumclassic/core-geth` @ `4185df450` gates `(a)(b)(c)` on
  * `GetEIP161abcTransition` and `(d)` on `GetEIP161dTransition`, at runtime and
  * at separate sites -- the first from `core/vm`, the second from
  * `core/state_processor.go` and each consensus engine's own finalize.
  * `openethereum/openethereum` @ `v3.0.1` carries the same split as `no_empty`
  * and `kill_empty` on its schedule.
  *
  * **No core-geth configuration can activate the halves at different heights,
  * and that is structural rather than a survey result.** Both getters return one
  * field at that ref: `EIP161FBlock` on `CoreGethChainConfig`, and
  * `EIP158Block` on the go-ethereum-shaped `ChainConfig`. So a sweep of that
  * client's configurations could not have disagreed, and what the split
  * evidences is that the document is READ as two rules at two sites -- never
  * that a network has separated them.
  */
object Eip161:

  /** Clause (a): a created account starts at a count of one.
    *
    * A number rather than a flag, on the document's own parenthetical about
    * networks whose default starting count is not zero.
    * `org.fukuii.evm.EvmRules.createdAccountNonce` holds that argument and the
    * client that parameterizes it.
    */
  val createdAccountCount: Proposal = _.copy(createdAccountNonce = UInt64.fromBits(1L))

  /** Clause (b): the surcharge for bringing a destination into being is levied
    * only where value moves to a destination that is dead.
    *
    * A condition and not a figure. What the two operations levying it pay stays
    * the schedule's, so this is not a repricing --
    * `org.fukuii.evm.NewAccountCharge` holds the three implementations that
    * state the same pair.
    */
  val surchargeCondition: Proposal = _.copy(newAccountCharge = NewAccountCharge.WhenValueReachesADeadDestination)

  /** Clause (d), inside the machine: reaching the address the RIPEMD-160 native
    * answers at is not undone when the invocation that reached it fails.
    *
    * ==The exception is the document's, and the document is where it is
    * established==
    *
    * Its Addendum amends the revert rule so that empty-account deletions are
    * reverted with the state, and its References note 3 records that the
    * amended behavior was made to match a second client's, whose deviation was
    * *"in a more limited set of contexts involving out-of-gas calls to
    * precompiled contracts"*. So the exception was preserved deliberately rather
    * than left behind.
    *
    * ==The address is the native's, and it is named as the native's==
    *
    * The four implementations that narrow the exception all narrow it to this
    * one address, and `org.fukuii.evm.EvmRules.touchSurvivesFailure` names them
    * at their refs rather than this scaladoc repeating them. It is written here
    * as the precompile it belongs to rather than as a number, because that is
    * what it is; the number itself stays where the ecosystem's addresses are.
    */
  val exemptFromTheRevert: Proposal = _.copy(touchSurvivesFailure = Set(PrecompileSet.Ripemd160))

  /** Clause (d), at settlement: an account this transaction reached and left
    * holding nothing ceases to exist when the transaction ends.
    *
    * **This one is written over the settlement facet rather than the machine's**,
    * which is the difference the deltas are separated to keep visible: the
    * machine records what was reached and never deletes anything, and no rule it
    * holds could express a deletion at the end of a transaction.
    */
  val clearingAtTheEnd: ExecutionRules => ExecutionRules = _.copy(touchedEmptyAccountsAreDeleted = true)

  /** Adopting the document, which is adopting all four of its clauses.
    *
    * The order is the order the deltas compose in. It is immaterial here -- the
    * four touch disjoint fields -- and it is stated rather than left to chance
    * because two deltas touching one field compose to whichever ran last.
    */
  val component: Component =
    Component(
      ProposalId.Eip(161),
      rules =>
        rules.copy(
          evm = rules.evm.applying(createdAccountCount, surchargeCondition, exemptFromTheRevert),
          execution = clearingAtTheEnd(rules.execution)
        )
    )
