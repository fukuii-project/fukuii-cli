package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes}
import org.fukuii.evm.{EvmFixtures, Word}
import org.fukuii.types.TransactionType
import org.scalatest.flatspec.AnyFlatSpec

/** What a transaction offers to pay, and the two rules that read it
  * differently.
  *
  * ==The balance case is the one worth having==
  *
  * A fee-market transaction states a cap and a tip, and the two rules that read
  * them disagree about which figure to use: the balance check reads the cap, the
  * up-front charge reads what the offer resolves to. **Collapsing them admits a
  * transaction every conformant node refuses**, and the failure is a narrower
  * refusal set rather than a wrong value -- so a state fixture asserting a
  * successful transaction still agrees, and nothing in a corpus reports it.
  * That is why it is asserted here directly rather than left to a tier.
  */
class FeeOfferSpec extends AnyFlatSpec:

  private val sender: Address = EvmFixtures.address(0x11)
  private val recipient: Address = EvmFixtures.address(0x22)

  private val Schedule = EvmFixtures.rules.schedule

  /** Admission rules carrying the capped format, which is what a fork adopting
    * the fee market produces.
    *
    * Built here rather than taken from a network's own rules, because this
    * module sits below the one that authors them -- and because a fixture
    * naming a fork would make these cases read as being about that fork rather
    * than about the offer.
    */
  private val WithMarket: AdmissionRules =
    AdmissionRules(
      admittedTypes = Set(TransactionType.Legacy, TransactionType.AccessList, TransactionType.DynamicFee),
      signatureMayCarryChainId = true,
      signatureSMustBeLow = true
    )

  private def world(balance: BigInt): EvmFixtures.MapWorldState =
    val w = new EvmFixtures.MapWorldState
    w.setBalance(sender, Word(balance))
    w

  /** Comfortably above whatever the fixture schedule charges intrinsically, so
    * no case here is decided by a rule it is not about.
    */
  private val GasLimit: BigInt = BigInt(100000)

  private def offer(fee: FeeOffer, gasLimit: BigInt = GasLimit, value: BigInt = 0): OfferedTransaction =
    OfferedTransaction(
      transactionType = fee match
        case FeeOffer.Fixed(_)     => TransactionType.Legacy
        case FeeOffer.Capped(_, _) => TransactionType.DynamicFee
      ,
      sender = sender,
      nonce = 0,
      fee = fee,
      gasLimit = gasLimit,
      to = Some(recipient),
      value = value,
      data = Bytes.Empty,
      accessList = Seq.empty
    )

  private def verdict(
      fee: FeeOffer,
      charge: Option[BigInt],
      balance: BigInt = BigInt(10).pow(20)
  ): Admission =
    TransactionAdmission.admit(offer(fee), world(balance), BigInt(30000000), charge, WithMarket, Schedule)

  private def refusal(a: Admission): Option[Refusal] = a match
    case Admission.Refused(reason) => Some(reason)
    case Admission.Admitted(_)     => None

  private def priceOf(a: Admission): Option[BigInt] = a match
    case Admission.Admitted(t) => Some(t.gasPrice)
    case Admission.Refused(_)  => None

  // ── What the offer resolves to ────────────────────────────────────────────

  "a fixed offer" should "pay its stated price whatever the block charges" in
    assert(
      FeeOffer.Fixed(BigInt(100)).effective(BigInt(30)) == BigInt(100),
      "a format predating the market states a price rather than a cap over one"
    )

  "a capped offer" should "pay the charge plus its tip where the cap leaves room" in
    assert(
      FeeOffer.Capped(maxFeePerGas = 100, maxPriorityFeePerGas = 5).effective(BigInt(30)) == BigInt(35),
      "the tip is affordable, so it is paid in full on top of the charge"
    )

  it should "pay no more than its cap where the tip would exceed it" in
    // The min is the whole point: a tip of 90 over a charge of 30 would be 120
    // against a cap of 100, and the cap is what the sender agreed to.
    assert(
      FeeOffer.Capped(maxFeePerGas = 100, maxPriorityFeePerGas = 90).effective(BigInt(30)) == BigInt(100),
      "the resolved price never exceeds the cap"
    )

  it should "pay exactly the charge where it offers no tip" in
    assert(
      FeeOffer.Capped(maxFeePerGas = 100, maxPriorityFeePerGas = 0).effective(BigInt(30)) == BigInt(30),
      "a zero tip resolves to the charge alone"
    )

  "the cap" should "be the stated price for a fixed offer and the maximum for a capped one" in
    assert(
      FeeOffer.Fixed(BigInt(7)).cap == BigInt(7) &&
        FeeOffer.Capped(maxFeePerGas = 9, maxPriorityFeePerGas = 4).cap == BigInt(9),
      "one accessor, because one rule reads it whichever format stated it"
    )

  // ── The two new refusals ──────────────────────────────────────────────────

  "a capped offer whose tip exceeds its cap" should "be refused" in
    assert(
      refusal(verdict(FeeOffer.Capped(maxFeePerGas = 10, maxPriorityFeePerGas = 11), Some(BigInt(1)))) ==
        Some(Refusal.PriorityFeeAboveFeeCap),
      "the specification refuses this before it recovers a sender"
    )

  it should "be refused even where the block sets no charge at all" in
    // It is a property of the transaction alone, so it must not depend on a
    // market existing. Asserting it at no charge is what separates this rule
    // from the cap-versus-charge one below.
    assert(
      refusal(verdict(FeeOffer.Capped(maxFeePerGas = 10, maxPriorityFeePerGas = 11), None)) ==
        Some(Refusal.PriorityFeeAboveFeeCap),
      "a transaction failing it is malformed at any charge, including none"
    )

  "a capped offer below the block's charge" should "be refused" in
    assert(
      refusal(verdict(FeeOffer.Capped(maxFeePerGas = 10, maxPriorityFeePerGas = 1), Some(BigInt(50)))) ==
        Some(Refusal.FeeCapBelowBaseFee),
      "what the transaction will pay cannot cover what the block charges"
    )

  "a FIXED offer below the block's charge" should "be refused by the same rule" in
    // The half a reader expects least, and it is why this reason is one case
    // rather than two: adopting the fee market changes admission for the
    // formats that predate it.
    assert(
      refusal(verdict(FeeOffer.Fixed(BigInt(10)), Some(BigInt(50)))) == Some(Refusal.FeeCapBelowBaseFee),
      "a fixed-price transaction at a fork with a market is refused exactly as a capped one is"
    )

  it should "be admitted below a fork that sets no charge" in
    assert(
      refusal(verdict(FeeOffer.Fixed(BigInt(10)), None)).isEmpty,
      "otherwise the assertion above holds for a reason that is not the charge"
    )

  // ── The balance check reads the CAP ───────────────────────────────────────

  "the balance check" should "be made against the cap rather than the resolved price" in {
    // Cap 100, tip 0, charge 30 -- so the price actually paid is 30 and the
    // most the sender could pay is 100. A balance of fifty times the limit
    // covers the resolved price comfortably and does not cover the cap.
    val balance = GasLimit * 50
    assert(
      refusal(verdict(FeeOffer.Capped(maxFeePerGas = 100, maxPriorityFeePerGas = 0), Some(BigInt(30)), balance)) ==
        Some(Refusal.InsufficientAccountFunds),
      "reading the resolved price here admits a transaction every conformant node refuses"
    )
  }

  it should "admit the same transaction once the balance covers the cap" in {
    val balance = GasLimit * 100
    assert(
      refusal(
        verdict(FeeOffer.Capped(maxFeePerGas = 100, maxPriorityFeePerGas = 0), Some(BigInt(30)), balance)
      ).isEmpty,
      "otherwise the assertion above holds because the balance was short of both figures"
    )
  }

  // ── What admission hands settlement ───────────────────────────────────────

  "admission" should "hand settlement the resolved price, not the cap" in
    assert(
      priceOf(verdict(FeeOffer.Capped(maxFeePerGas = 100, maxPriorityFeePerGas = 5), Some(BigInt(30)))) ==
        Some(BigInt(35)),
      "settlement spends one price and this is where two figures become one"
    )
