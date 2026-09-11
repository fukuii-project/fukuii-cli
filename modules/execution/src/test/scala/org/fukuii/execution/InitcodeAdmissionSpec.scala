package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes}
import org.fukuii.evm.{EvmFixtures, Word}
import org.fukuii.types.TransactionType
import org.scalatest.flatspec.AnyFlatSpec

/** The half of EIP-3860 that is settled before the machine runs.
  *
  * ==Why it is asserted here rather than left to a machine spec==
  *
  * Rules 1 and 2 of `ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-3860.md` (Final)
  * are about a transaction and not about an operation, so **no execution of
  * `CREATE` can reveal either of them missing**. A build implementing only the
  * two rules inside the machine admits a transaction the network rejects and
  * undercharges every transaction that deploys, and every case about the
  * machine still passes.
  *
  * ==The bound is the machine's value, read here==
  *
  * It is one number bounding both a create transaction's data and a create
  * operation's operand, so it is passed in rather than held on this facet. A
  * copy on each would be one number with two definitions, which is the shape a
  * fork could then move at one site and not the other.
  */
class InitcodeAdmissionSpec extends AnyFlatSpec:

  private val sender: Address = EvmFixtures.address(0x11)

  private val recipient: Address = EvmFixtures.address(0x22)

  private val schedule = EvmFixtures.rules.schedule

  private val bound: Int = 64

  /** Rules carrying only the format every network has always carried, so no
    * case here is decided by a rule it is not about.
    */
  private val rules: AdmissionRules =
    AdmissionRules(
      admittedTypes = Set(TransactionType.Legacy),
      signatureMayCarryChainId = false,
      signatureSMustBeLow = false
    )

  private def world(): EvmFixtures.MapWorldState =
    val built = new EvmFixtures.MapWorldState
    built.setBalance(sender, Word(BigInt(10).pow(20)))
    built

  private def offer(data: Bytes, deploys: Boolean): OfferedTransaction =
    OfferedTransaction(
      transactionType = TransactionType.Legacy,
      sender = sender,
      nonce = 0,
      fee = FeeOffer.Fixed(BigInt(1)),
      // Comfortably above the intrinsic charge for anything this file offers,
      // so a case about the bound is never decided by the charge above it.
      gasLimit = BigInt(10000000),
      to = if deploys then None else Some(recipient),
      value = 0,
      data = data,
      accessList = Seq.empty,
      blobs = None
    )

  private def verdict(data: Bytes, deploys: Boolean, bounded: Option[Int]): Admission =
    TransactionAdmission.admit(
      offer(data, deploys),
      world(),
      BigInt(30000000),
      None,
      // These rules account for no blob gas, so a block under them sets no
      // charge for it and has no allowance to spend.
      None,
      rules,
      schedule,
      bounded
    )

  private def refusal(verdict: Admission): Option[Refusal] = verdict match
    case Admission.Refused(reason) => Some(reason)
    case Admission.Admitted(_)     => None

  private def bytes(length: Int): Bytes = Bytes.fromArray(Array.fill(length)(1.toByte))

  "a deploying transaction over the bound" should "be refused" in
    // "If length of transaction data (`initcode`) in a create transaction
    // exceeds `MAX_INITCODE_SIZE`, transaction is invalid."
    assert(
      refusal(verdict(bytes(bound + 1), deploys = true, bounded = Some(bound))).contains(Refusal.InitcodeTooLarge),
      "the refusal names this rule and not the charge it sits beside"
    )

  "a deploying transaction exactly at the bound" should "be admitted" in
    // One of the four cases the document's own test list names, and the
    // boundary the comparison being strictly greater fixes. Without it the pair
    // above admits an off-by-one in either direction.
    assert(
      refusal(verdict(bytes(bound), deploys = true, bounded = Some(bound))).isEmpty,
      "data of exactly the bound is admitted, the comparison being strictly greater"
    )

  "a CALLING transaction carrying more than the bound" should "be admitted" in
    // The bound is on a create transaction's data alone. A call's data is an
    // argument rather than code, and reading the bound over every transaction
    // would refuse calls no network refuses -- which no fixture of a deploying
    // transaction could show.
    assert(
      refusal(verdict(bytes(bound + 1), deploys = false, bounded = Some(bound))).isEmpty,
      "a call of any length is admitted, at every fork"
    )

  "a deploying transaction over the bound under rules that bound nothing" should "be admitted" in
    // The negative control. Every case above is asserting that a bound was
    // read; this one asserts that its absence is read too, so a build carrying
    // a figure of its own rather than the rules' fails here.
    assert(
      refusal(verdict(bytes(bound + 1), deploys = true, bounded = None)).isEmpty,
      "with no bound in force nothing is oversized, whatever the length"
    )

  "a transaction breaking the bound and the charge at once" should "be refused for the charge" in {
    // Both rules can be broken by one transaction, and which is reported is the
    // order's to decide. `ethereum/execution-specs` @ `20f7f6271a` raises
    // `InsufficientTransactionGasError` and then `InitCodeTooLargeError` on the
    // next line (`forks/shanghai/transactions.py:339-341`), so the charge is
    // read first.
    val starved = offer(bytes(bound + 1), deploys = true).copy(gasLimit = BigInt(1))
    val answer =
      TransactionAdmission.admit(starved, world(), BigInt(30000000), None, None, rules, schedule, Some(bound))
    assert(refusal(answer).contains(Refusal.IntrinsicGasTooLow), "the specification's order puts the charge first")
  }
