package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.proposals.eip.Eip2
import org.fukuii.evm.{EvmFixtures, GasSchedule, Word}
import org.fukuii.execution.{
  Admission,
  AdmittedTransaction,
  IntrinsicGas,
  OfferedTransaction,
  Refusal,
  TransactionAdmission
}
import org.fukuii.types.TransactionType

/** Every reason admission can refuse a transaction, each reached on purpose.
  *
  * ==The expectations are the specification's, not this driver's==
  *
  * A test that asserts a function does what that function's own branches say is
  * circular, and these refusals are exactly where that is easy to write by
  * accident. So each expectation below is anchored to `execution-specs`'
  * `forks/frontier/fork.py`, whose `check_transaction` raises, in order,
  * `GasUsedExceedsLimitError`, `NonceMismatchError`, `InsufficientBalanceError`
  * and `InvalidSenderError` -- and to `frontier/transactions.py` for the
  * intrinsic charge and the nonce ceiling.
  *
  * **One of these was nearly asserted the wrong way round.** A sender holding
  * code reads like EIP-3607, which activated at London, so refusing it at
  * Frontier looks anachronistic. The specification settles it: `frontier`'s
  * `check_transaction` carries `if sender_account.code_hash != EMPTY_CODE_HASH:
  * raise InvalidSenderError("not EOA")`, and every fork from Frontier onward
  * carries it. The rule is applied retroactively, as EIP-7610's is. **Read the
  * fork's own source before deciding a rule is too late for it.**
  *
  * ==Why a written case rather than a published one==
  *
  * **Published fixtures for all three of the branches no Frontier case reaches
  * DO exist**, and an earlier reading here claimed otherwise from a sweep of one
  * corpus tier at forks through Berlin. Corrected: the generated corpus carries
  * them in `state_tests` and in `blockchain_tests`, and `ethereum/tests` carries
  * a `TransactionTests` tier of its own for transaction validity.
  *
  * **None of them is at Frontier or Homestead** -- the corpus states a
  * fork-invariant rule once, at the fork whose proposal introduced it, so these
  * arrive at London and later. Consuming them means either reaching that fork or
  * wiring a corpus tier this harness does not read. **So this is a bridge, and
  * the published cases supersede it when the forks carrying them are certified.**
  *
  * ==Why it exists beside the corpus at all==
  *
  * The corpus is not in this repository, so a clone proved exactly one refusal:
  * the one embedded in `FixtureCalibrationSpec`. The rest were carried by cases
  * that do not run without a corpus on disk, which for anyone who clones is the
  * same as not being carried.
  *
  * ==The refusals fail closed, which is why the risk is subtle==
  *
  * None of these admits what it should refuse; the risk is that **nothing shows
  * a branch refuses for the reason it states.** A condition never executed can
  * be inverted, or made to answer with its neighbor's reason, and every test
  * that exists would still pass -- the more so because a refused transaction
  * leaves the state root at its pre-state value whichever branch refused it, so
  * the corpus's own strongest check cannot tell two reasons apart.
  *
  * ==The admitted case is the control and is not optional==
  *
  * A file of tests that each expect a refusal is satisfied in its entirety by
  * an admission that refuses everything. The first test below is what makes the
  * rest of them mean what they say.
  */
class FrontierAdmissionSpec extends AnyFlatSpec:

  private val sender: Address = EvmFixtures.address(0x11)
  private val recipient: Address = EvmFixtures.address(0x22)

  /** What the block this transaction would go into has left to give. */
  private val allowance: BigInt = 1000000

  /** A transaction this fork admits, from which each refusal is one edit away. */
  private def admissible(
      kind: TransactionType = TransactionType.Legacy,
      nonce: BigInt = 0,
      gasPrice: BigInt = 1,
      gasLimit: BigInt = 100000,
      value: BigInt = 0,
      to: Option[Address] = Some(recipient),
      data: Bytes = Bytes.Empty
  ): OfferedTransaction =
    OfferedTransaction(kind, sender, nonce, gasPrice, gasLimit, to, value, data, Seq.empty)

  /** The rules with EIP-2's creation surcharge applied. */
  private val charging: GasSchedule = ethereum.Upgrades.frontier.evm.applying(Eip2.creationCharge).schedule

  private def charged(transaction: OfferedTransaction, schedule: GasSchedule): BigInt =
    IntrinsicGas.of(schedule, transaction.data, transaction.to.isEmpty, transaction.accessList)

  /** A world in which [[admissible]] is admitted, from which each refusal is
    * likewise one edit away.
    */
  private def world(balance: BigInt = 1000000, nonce: Long = 0, code: Bytes = Bytes.Empty): EvmFixtures.MapWorldState =
    val built = new EvmFixtures.MapWorldState
    built.setBalance(sender, Word(balance))
    built.setNonce(sender, UInt64.fromBigInt(BigInt(nonce)).getOrElse(UInt64.Zero))
    built.setCode(sender, code)
    built

  private def verdict(
      transaction: OfferedTransaction,
      state: EvmFixtures.MapWorldState = world(),
      available: BigInt = allowance
  ): Admission =
    TransactionAdmission.admit(
      transaction,
      state,
      available,
      ethereum.Upgrades.frontier.admission,
      ethereum.Upgrades.genesisPrices
    )

  /** What an admitted transaction of the shape above hands to settlement. */
  private def settling(transaction: OfferedTransaction, intrinsic: BigInt): Admission =
    Admission.Admitted(
      AdmittedTransaction(
        transaction.sender,
        transaction.nonce,
        transaction.gasPrice,
        transaction.gasLimit,
        transaction.to,
        transaction.value,
        transaction.data,
        transaction.accessList,
        intrinsic
      )
    )

  "admission" should "admit a transaction that breaks none of its rules" in
    assert(
      verdict(admissible()) == settling(admissible(), ethereum.Upgrades.genesisPrices.transactionBase),
      verdict(admissible()).toString
    )

  it should "refuse a transaction of a format this fork does not carry" in
    assert(
      verdict(admissible(kind = TransactionType.AccessList)) == Admission.Refused(Refusal.TypeNotAdmitted),
      verdict(admissible(kind = TransactionType.AccessList)).toString
    )

  it should "refuse a transaction whose limit cannot pay the intrinsic charge" in
    // One below the charge, so the boundary is pinned and not merely the region.
    assert(
      verdict(admissible(gasLimit = ethereum.Upgrades.genesisPrices.transactionBase - 1)) ==
        Admission.Refused(Refusal.IntrinsicGasTooLow),
      verdict(admissible(gasLimit = ethereum.Upgrades.genesisPrices.transactionBase - 1)).toString
    )

  it should "admit a transaction whose limit exactly meets the intrinsic charge" in {
    // The other side of the same boundary. Without it, an inverted comparison
    // refusing one gas too much would pass the test above.
    val exact = admissible(gasLimit = ethereum.Upgrades.genesisPrices.transactionBase)
    assert(
      verdict(exact) == settling(exact, ethereum.Upgrades.genesisPrices.transactionBase),
      verdict(exact).toString
    )
  }

  it should "refuse a transaction whose nonce cannot be signed for" in
    assert(
      verdict(admissible(nonce = TransactionAdmission.NonceLimit)) == Admission.Refused(Refusal.NonceIsMax),
      verdict(admissible(nonce = TransactionAdmission.NonceLimit)).toString
    )

  it should "refuse a transaction asking for more gas than the block has left" in
    // Funded well past the fee this asks for, so the allowance rule is the only
    // one broken. Left at the default balance it would break the funding rule
    // too, and the test would pass on whichever branch happened to come first.
    assert(
      verdict(admissible(gasLimit = allowance + 1), world(balance = BigInt(10).pow(18))) ==
        Admission.Refused(Refusal.GasAllowanceExceeded),
      verdict(admissible(gasLimit = allowance + 1), world(balance = BigInt(10).pow(18))).toString
    )

  it should "measure that allowance against what the block has left, not against its limit" in
    // The same transaction, admissible against a whole empty block and refused
    // once earlier transactions have taken all but a little of it. Nothing else
    // about the transaction or the world changes, so only the remainder can
    // account for the difference.
    assert(
      verdict(admissible(), world(balance = BigInt(10).pow(18)), available = 99999) ==
        Admission.Refused(Refusal.GasAllowanceExceeded),
      verdict(admissible(), world(balance = BigInt(10).pow(18)), available = 99999).toString
    )

  it should "refuse a transaction whose nonce is not the sender's next" in
    assert(
      verdict(admissible(nonce = 5)) == Admission.Refused(Refusal.NonceMismatch),
      verdict(admissible(nonce = 5)).toString
    )

  it should "refuse a transaction the sender cannot fund" in
    // The fee alone exceeds the balance, with no value transferred, so this
    // reaches the branch through the term the fixture corpus never varies.
    assert(
      verdict(admissible(gasPrice = 2), world(balance = 1)) ==
        Admission.Refused(Refusal.InsufficientAccountFunds),
      verdict(admissible(gasPrice = 2), world(balance = 1)).toString
    )

  it should "refuse a transaction sent from an account holding code" in
    assert(
      verdict(admissible(), world(code = EvmFixtures.bytesOf("0x600160005500"))) ==
        Admission.Refused(Refusal.SenderNotEoa),
      verdict(admissible(), world(code = EvmFixtures.bytesOf("0x600160005500"))).toString
    )

  // ── What a transaction pays before it runs ──────────────────────────────

  "the intrinsic charge" should "ask a deployment no more than a call at Frontier" in
    // The specification names no creation constant at this fork and its charge is
    // a base plus the data, so the two shapes cost the same. Without this the
    // surcharge below would have nothing to be a delta over.
    assert(
      charged(admissible(to = None), ethereum.Upgrades.genesisPrices) ==
        charged(admissible(), ethereum.Upgrades.genesisPrices)
    )

  it should "ask a deployment thirty-two thousand more once the proposal is applied" in
    assert(
      charged(admissible(to = None), charging) - charged(admissible(to = None), ethereum.Upgrades.genesisPrices) ==
        BigInt(32000)
    )

  it should "leave a call charged exactly what it was" in
    // The control. A surcharge applied to every transaction rather than only to
    // a deployment would satisfy both cases above and be wrong for every call on
    // the network.
    assert(charged(admissible(), charging) == charged(admissible(), ethereum.Upgrades.genesisPrices))

  it should "still charge a deployment for the data it carries" in
    // The surcharge is added to the data charge rather than replacing it, so a
    // deployment with a payload pays for both.
    assert(
      charged(admissible(to = None, data = EvmFixtures.bytesOf("0xff")), charging) -
        charged(admissible(to = None), charging) == BigInt(68)
    )
