package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.types.TransactionType
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-7702 switches on, and why it is three rules rather than
  * one.
  *
  * The assertions that matter most here are the ones about the gates being
  * SEPARATE. A single flag would make three states inexpressible, and each of
  * the three is a state some fork is actually in: a fork below the document
  * carries none of them; a fork that stopped carrying the form would still
  * follow designations written while it did; and the marker bytes are ordinary
  * code wherever designations are not followed.
  */
class Eip7702Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.cancun

  private val adopted: UpgradeRules = base.adopting(Eip7702.component)

  "adopting EIP-7702" should "carry the set-code transaction format" in
    assert(
      adopted.admission.admittedTypes.contains(TransactionType.SetCode),
      "the format the document introduces"
    )

  it should "not have carried it before it was adopted" in
    assert(
      !base.admission.admittedTypes.contains(TransactionType.SetCode),
      "the fork below refuses the format, which is what makes admitting it a delta"
    )

  it should "keep a delegated account able to send its own transactions" in
    // Without this the format strands every account that uses it: a delegated
    // account holds code, a sender holding code is refused, and it could never
    // send the transaction that undoes its own delegation.
    assert(
      adopted.admission.admitsDelegations,
      "an account holding a designation is still an externally owned account"
    )

  it should "follow a designation when a delegated account is called" in
    assert(
      adopted.evm.followsDelegations,
      "calling a delegated account runs the code it names"
    )

  it should "read a designation as ordinary code at the fork below" in
    // The half that makes the gate necessary rather than cosmetic. Below the
    // document nothing can have written a designation, so following the bytes
    // would run another account's code for an account that merely deployed
    // something beginning with the marker.
    assert(
      !base.evm.followsDelegations && !base.admission.admitsDelegations,
      "neither reading is in force below the document"
    )

  it should "price an authorization at twenty-five thousand" in
    assert(
      adopted.evm.schedule.transactionPerAuthorization == BigInt(25000),
      "charged per authorization stated, whether or not it applies"
    )

  it should "rebate an existing authority to twelve and a half thousand" in
    assert(
      adopted.evm.schedule.refundPerExistingAuthority == BigInt(12500),
      "the document prices the common case high and rebates the cheaper one"
    )

  it should "charge nothing per authorization at the fork below" in
    // The zero that makes the intrinsic charge's term unconditional: a fork
    // below the document multiplies a price of zero by a count that is itself
    // zero, so the arithmetic needs no case.
    assert(
      base.evm.schedule.transactionPerAuthorization == BigInt(0) &&
        base.evm.schedule.refundPerExistingAuthority == BigInt(0),
      "no fork below the document charges or rebates for an authorization"
    )

  it should "leave the opcode table untouched" in
    // The document adds no operation. Everything it changes is either a rule or
    // a price, which is why a delegated call is invisible to the table.
    assert(
      adopted.evm.table == base.evm.table,
      "no operation is added, and none is repriced in the table"
    )

  it should "leave the header rules untouched" in
    assert(
      adopted.header == base.header,
      "nothing the document does reaches a header field"
    )
