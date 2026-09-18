package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{EvmRules, GasSchedule}
import org.fukuii.execution.AdmissionRules
import org.fukuii.types.TransactionType

/** EIP-7702 -- an account that runs another account's code.
  *
  * ==What it adds is a designation, not a new kind of account==
  *
  * An externally owned account signs an authorization naming a contract, and
  * that account then holds twenty-three bytes of code: a three-byte marker and
  * the address it named. Every rule that loads code meets that, so the account
  * behaves as the named contract while remaining, for every other purpose, the
  * account it was -- its own balance, its own storage, its own nonce, and its
  * own ability to send transactions. `org.fukuii.types.Delegation` is the
  * reading of those bytes.
  *
  * ==Four seams move, and they are gated by THREE separate rules==
  *
  * The gates are separate because the questions come apart:
  *
  *   - `admittedTypes` decides whether the transaction FORM is carried;
  *   - `admitsDelegations` decides whether an account holding those bytes is
  *     still an externally owned account for the purpose of sending;
  *   - `followsDelegations` decides whether calling such an account runs the
  *     code it names.
  *
  * **A fork keeps reading designations long after it stops carrying the form**,
  * and the bytes are ordinary code at every fork below this document -- where
  * following them would run some other account's code for an account that
  * merely deployed something beginning with the marker. One flag for all three
  * would make those states inexpressible.
  *
  * ==The charge is per authorization STATED, and part comes back==
  *
  * Each authorization costs 25,000 at the intrinsic charge, whether or not it
  * turns out to apply -- it is validated against state nothing has touched yet,
  * so admission cannot know. An authorization whose authority already exists
  * earns back the difference to 12,500, which is the document pricing the
  * common case high and rebating the cheaper one.
  * `ethereum/execution-specs` @ `0cc100eb1`,
  * `src/ethereum/forks/prague/vm/gas.py:61` and
  * `src/ethereum/forks/prague/vm/eoa_delegation.py:32`.
  *
  * ==An authorization that fails is skipped, never a refusal==
  *
  * Six checks can reject one and none of them refuses the transaction carrying
  * it. `org.fukuii.execution.Delegations` holds that, and holds the ordering
  * fact that matters most: an authority is reached -- and so warm -- from the
  * moment its signature recovers, including when a later check then rejects it.
  *
  * **The one refusal this document does add is an EMPTY list**, which is a
  * transaction asking for nothing the format exists to express.
  */
object Eip7702:

  /** What one authorization costs, and what an existing authority earns back.
    *
    * The pair moves together: the refund is defined as a rebate against the
    * charge, so a fork holding one without the other would rebate against a
    * figure it does not charge.
    */
  val authorizationPricing: GasSchedule => GasSchedule =
    _.copy(
      transactionPerAuthorization = BigInt(25000),
      refundPerExistingAuthority = BigInt(12500)
    )

  /** The format, and the carve-out that keeps a delegated account able to send.
    *
    * **Both, because admitting the format alone would strand every account that
    * used it**: an account that has delegated holds code, and a sender holding
    * code is refused. It could then never send the transaction that undoes its
    * own delegation.
    */
  val admitsSetCodeFormat: AdmissionRules => AdmissionRules =
    rules => rules.copy(admittedTypes = rules.admittedTypes + TransactionType.SetCode, admitsDelegations = true)

  /** Calling a delegated account runs the code it names. */
  val followsDesignations: EvmRules => EvmRules = _.copy(followsDelegations = true)

  /** Adopting the document, which is adopting all of it at once.
    *
    * **The parts are not separately adoptable and the component is what says
    * so.** Admitting the format without following designations would settle
    * every set-code transaction as a plain call to an account whose code nothing
    * reads -- the authorizations would apply, the designations would be written,
    * and every call to them would run the marker bytes as code.
    */
  val component: Component =
    Component(
      ProposalId.Eip(7702),
      rules =>
        rules.copy(
          evm = followsDesignations(rules.evm.copy(schedule = authorizationPricing(rules.evm.schedule))),
          admission = admitsSetCodeFormat(rules.admission)
        )
    )
