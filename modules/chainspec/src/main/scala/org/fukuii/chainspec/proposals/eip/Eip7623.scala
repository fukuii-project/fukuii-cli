package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.GasSchedule

/** EIP-7623 -- a floor on what a calldata-heavy transaction pays.
  *
  * ==The problem it prices, and why a second price rather than a repricing==
  *
  * Calldata is charged per byte before a transaction runs, and a transaction
  * that carries a great deal of data and then does very little work pays for the
  * data at that rate and nothing more. Blocks built almost entirely of such
  * transactions are far larger than the gas they consume suggests, which is the
  * bound the document is written against.
  *
  * **Raising the per-byte price would have charged every transaction more**,
  * including the ones whose data is incidental to real work. So the document
  * leaves the existing prices where they are and states a FLOOR instead: a
  * transaction pays the greater of what it actually spent and a figure derived
  * from its calldata alone. A transaction doing substantial work is above the
  * floor and pays exactly what it did before; only the data-heavy case moves.
  *
  * ==Tokens, which are the document's own unit==
  *
  * The floor counts calldata in tokens -- one per zero byte, four per non-zero
  * byte -- and charges `tokens * 10 + 21000`. This rule set contributes the 10;
  * the base is the one the fork already states, and the token count belongs to
  * the layer that has the calldata.
  *
  * ==Where the floor binds is TWO places, and the second is ordered==
  *
  * `org.fukuii.execution.TransactionAdmission` refuses a transaction whose limit
  * cannot cover the floor, and `org.fukuii.execution.TransactionProcessor`
  * charges the floor where the transaction settled below it. **The second is
  * applied after the refund and not before it** -- the specification reduces the
  * figure by the refund and then raises it to the floor
  * (`ethereum/execution-specs` @ `0cc100eb1`,
  * `src/ethereum/forks/prague/fork.py:922-924`) -- because a refund applied
  * afterwards could carry the charge back below the floor, which is the outcome
  * the floor exists to prevent.
  *
  * ==Why this document states a price and not a flag==
  *
  * A fork below it states no floor at all rather than a floor of zero, and those
  * are different facts: the floor adds the transaction base, so a zero price
  * still yields 21,000 -- a figure a refunded transaction can legitimately
  * settle below, since the refund is capped at a fifth of what was spent rather
  * than at the base. `org.fukuii.execution.IntrinsicGas.calldataFloorOf` is
  * where the price becomes an absence, and carries that reasoning.
  */
object Eip7623:

  /** What one calldata token costs under the floor.
    *
    * `ethereum/execution-specs` @ `0cc100eb1`,
    * `src/ethereum/forks/prague/vm/gas.py:102`,
    * `TX_DATA_TOKEN_FLOOR: Final[Uint] = Uint(10)`, against the standard token
    * price of 4 at `:101` -- the two are stated side by side there, which is
    * what makes the floor a second price over the same bytes rather than a
    * replacement for the first.
    */
  val calldataTokenFloor: GasSchedule => GasSchedule =
    _.copy(transactionCalldataTokenFloor = BigInt(10))

  /** Adopting the document, which is adopting its one price. */
  val component: Component =
    Component(
      ProposalId.Eip(7623),
      rules => rules.copy(evm = rules.evm.copy(schedule = calldataTokenFloor(rules.evm.schedule)))
    )
