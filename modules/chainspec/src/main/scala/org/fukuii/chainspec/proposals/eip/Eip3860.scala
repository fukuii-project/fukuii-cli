package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.Proposal

/** EIP-3860 -- the code that initializes a new account is bounded, and paid for
  * by its length.
  *
  * ==Four rules, and only two of them are in the machine==
  *
  * The document's Specification is a numbered list, and reading it as an EVM
  * change alone implements half of it. Verbatim (`ethereum/EIPs` @ `dbfa6bee8`
  * (2026-08-26), `EIPS/eip-3860.md`, Final):
  *
  *   1. *"If length of transaction data (`initcode`) in a create transaction
  *      exceeds `MAX_INITCODE_SIZE`, transaction is invalid."*
  *   2. *"For a create transaction, extend the transaction data cost formula to
  *      include `initcode_cost(initcode)`."*
  *   3. *"If length of `initcode` to `CREATE` or `CREATE2` instructions exceeds
  *      `MAX_INITCODE_SIZE`, instruction execution exceptionally aborts (as if
  *      it runs out of gas)."*
  *   4. *"For the `CREATE` and `CREATE2` instructions charge an extra gas cost
  *      equaling to `initcode_cost(initcode)`."*
  *
  * **The first two are settled before the machine runs and the last two inside
  * it**, so this is the second component in this build whose delta leaves the
  * machine, and the first whose absence from admission would be invisible to
  * every test of the machine. A build implementing only 3 and 4 accepts a
  * transaction the network rejects and charges a deploying transaction too
  * little -- neither of which any execution of `CREATE` can reveal.
  *
  * ==The two quantities, and where each lives==
  *
  * `INITCODE_WORD_COST` is 2 and `initcode_cost(initcode)` is
  * *"`INITCODE_WORD_COST * ceil(len(initcode) / 32)`"*. It is a price, so it is
  * `org.fukuii.evm.GasSchedule.initcodePerWord` --- held at zero by every
  * network below this document, where it charges nothing. That is what makes
  * rules 2 and 4 a repricing in place rather than a new mechanism: both sites
  * already multiply a per-word rate over the same word count, and this adds a
  * second rate to the sum.
  *
  * `MAX_INITCODE_SIZE` is *"`2 * MAX_CODE_SIZE`"* where *"`MAX_CODE_SIZE` is
  * defined by EIP-170 as `24576`"*, so it is
  * `org.fukuii.evm.EvmRules.maxInitcodeSize` and it is DERIVED here from the
  * bound this document doubles rather than written as a literal.
  *
  * ==`requires: 170`, and what an unmet requirement does here==
  *
  * A rule set that has not adopted [[Eip170]] bounds no deployed code, so
  * doubling that bound yields no bound either and this delta leaves
  * `maxInitcodeSize` absent. **That is a real state and it is silent**: rules 1
  * and 3 then refuse nothing, while rules 2 and 4 still charge. The alternative
  * is a literal 49,152, which would state a bound derived from a code size the
  * network does not have --- a number with no source on that chain. The
  * derivation is preferred because it cannot disagree with its own premise, and
  * the requirement is the document's to state rather than this component's to
  * enforce.
  *
  * **49,152 is not a constant of the ecosystem either.** `ethereum/go-ethereum`
  * @ `e9e35a42f` (2026-08-26) declares `MaxInitCodeSize = 2 * MaxCodeSize` and,
  * two lines below, a second pair for a later fork that doubles a larger bound
  * (`params/protocol_params.go:161,163`). So the doubling follows the deployed
  * bound wherever it goes, and a build that froze the figure would part from the
  * field at the first fork that moves it.
  *
  * ==The extra charge is one rate added to another, not a second charge==
  *
  * The document says so where it explains the figure: *"the same implementation
  * may be used for `CREATE` and `CREATE2` with different cost constants: before
  * activation `0` for `CREATE` and `6` for `CREATE2`, after activation `2` for
  * `CREATE` and `6 + 2` for `CREATE2`"* (Rationale). `org.fukuii.evm.Interpreter`
  * sums the two rates before multiplying, which is that sentence written out;
  * `ethereum/go-ethereum` @ `e9e35a42f` writes the identical sum at
  * `core/vm/gas_table.go:589`.
  *
  * Rule 4's ordering clause -- *"This cost is deducted before the calculation of
  * the resulting contract address and the execution of `initcode`"*, with the
  * note *"before or at the same time as the hashing cost is applied in
  * `CREATE2`"* -- is satisfied by that sum being part of one charge taken before
  * the address is derived.
  *
  * ==Rule 3 aborts where the neighboring refusals push zero==
  *
  * A creation refused for its balance, its transaction count or its depth hands
  * the forwarded gas back and pushes zero. This one does not: it *"exceptionally
  * aborts (as if it runs out of gas)"*, keeping nothing. The document places it
  * deliberately and says why: it belongs to *"the group of early out-of-gas
  * checks, including: stack underflow, memory expansion, static call violation,
  * initcode hashing cost, and initcode cost introduced by this EIP. They precede
  * the later 'light' checks: call depth and balance"*. Putting it after the
  * light checks would return gas on an input that must consume all of it.
  *
  * ==What this document does NOT do==
  *
  * It bounds the code handed TO a creation and leaves the bound on the code a
  * creation LEAVES BEHIND exactly where [[Eip170]] put it. The two are different
  * numbers over different bytes at different moments, and the second is
  * unchanged: *"We extend EIP-170 by introducing a maximum size limit for
  * `initcode`"* -- extend, beside, rather than replace.
  */
object Eip3860:

  /** Twice the bound on deployed code, where the rules bound that at all.
    *
    * Read from what the rules already hold rather than written as a figure, for
    * the reason above: the document defines the bound as a doubling and the
    * thing doubled is not fixed across forks.
    */
  val initcodeBound: Proposal = rules => rules.copy(maxInitcodeSize = rules.maxCodeSize.map(_ * 2))

  /** Two gas for each whole word of the code a creation is initialized from.
    *
    * The rate is spent at both sites the document names -- the create operations
    * and the charge every creating transaction pays before it runs -- so moving
    * it here settles rules 2 and 4 together. `org.fukuii.evm.GasSchedule` records
    * why one field reaches two layers.
    */
  val initcodeMetering: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(initcodePerWord = BigInt(2)))

  /** Adopting the document: the bound, then the rate.
    *
    * The order is immaterial -- the two name different fields -- and is stated
    * because two deltas touching one field compose to whichever ran last.
    *
    * ==Built through the machine's constructor even though two of the four
    * rules are settled outside it==
    *
    * The constructor scopes what a delta may WRITE, and both values this
    * document sets are the machine's: a bound on what a creation may be handed,
    * and a price. What reads them is a different question -- the layer that
    * admits a transaction reads both, exactly as it already reads the rest of
    * the schedule for the charge every transaction pays. So a delta reaching
    * past the machine here would be writing a second copy of a number the
    * machine already holds, which is the arrangement `besu-eth/besu` @
    * `fdf1247c6d` avoids the same way: its transaction validator is constructed
    * with `evm.getMaxInitcodeSize()` rather than with a limit of its own.
    */
  val component: Component = Component.evm(ProposalId.Eip(3860), initcodeBound, initcodeMetering)
