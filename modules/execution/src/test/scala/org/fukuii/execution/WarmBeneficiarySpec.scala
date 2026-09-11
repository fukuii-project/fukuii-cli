package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes}
import org.fukuii.evm.{EvmFixtures, EvmRules, JournaledWorldState, StateAccessMetering, Word}
import org.scalatest.flatspec.AnyFlatSpec

/** Whether the account a block pays its fees to is reached at the reduced price
  * from the start.
  *
  * ==Asserted as a CHARGE, because that is the only thing the rule changes==
  *
  * EIP-3651 adds one address to a set. Nothing about a transaction's outcome
  * moves: the same state is reached, the same value is pushed, and the only
  * observable is how much gas the first reach at that account cost. So each
  * case below runs one `BALANCE` against the block's own beneficiary and reads
  * what it spent.
  *
  * ==The pair is the assertion, not either half==
  *
  * A single run cannot distinguish a build that always warms the beneficiary
  * from one that never does -- both produce a figure. The two rule sets differ
  * in exactly one member, so the difference between their two figures is the
  * rule and nothing else.
  */
class WarmBeneficiarySpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  private val sender: Address = EvmFixtures.address(0x11)

  private val recipient: Address = EvmFixtures.address(0x22)

  /** The beneficiary, chosen so a `PUSH1` can name it: an address built from a
    * repeated byte cannot be pushed as one operand, and the operand is what the
    * program below reaches it by.
    */
  private val beneficiary: Address = Address.fromBytesTruncating(IArray(0x44.toByte))

  private val block = EvmFixtures.block.copy(coinbase = beneficiary, gasLimit = BigInt(10000000))

  /** Pushes the beneficiary's address, asks its balance, and stops. */
  private val readsTheBeneficiary: Bytes = EvmFixtures.bytesOf("0x60443100")

  /** Reaching costs by whether it has been reached before in this transaction,
    * which is the scheme EIP-3651's own frontmatter requires.
    */
  private val metered: EvmRules =
    EvmFixtures.rules.copy(stateAccessMetering = StateAccessMetering.WarmCold)

  private val warming: EvmRules = metered.copy(coinbaseStartsWarm = true)

  private def spent(rules: EvmRules): BigInt =
    val base = new EvmFixtures.MapWorldState
    base.setBalance(sender, Word(BigInt(10).pow(18)))
    base.setCode(recipient, readsTheBeneficiary)
    val admitted = AdmittedTransaction(
      sender = sender,
      nonce = 0,
      gasPrice = 3,
      baseFeePerGas = 0,
      gasLimit = 100000,
      to = Some(recipient),
      value = 0,
      data = Bytes.Empty,
      accessList = Seq.empty,
      intrinsicGas = IntrinsicGas.of(schedule, Bytes.Empty, deploys = false, Seq.empty),
      blobGasUsed = BigInt(0),
      blobGasPrice = BigInt(0),
      blobVersionedHashes = Seq.empty
    )
    TransactionProcessor
      .settle(
        admitted,
        new JournaledWorldState(base),
        _ => (),
        block,
        EvmFixtures.blockHashAt,
        EvmFixtures.chainId,
        rules,
        ExecutionRules(touchedEmptyAccountsAreDeleted = false, receiptCarriesStatus = false, maxRefundQuotient = 2)
      )
      .gasUsed

  "the block's beneficiary" should "cost the first-reach price where the rules have not adopted the proposal" in
    assert(
      spent(metered) == schedule.transactionBase + schedule.veryLow + schedule.coldAccountAccess + schedule.zero,
      "below EIP-3651 the beneficiary is in no set, so reaching it is a first reach like any other"
    )

  it should "cost the repeat-reach price where they have" in
    assert(
      spent(warming) == schedule.transactionBase + schedule.veryLow + schedule.warmAccess + schedule.zero,
      "the address is in the set before the outermost invocation begins, so the reach is a repeat"
    )

  it should "differ between the two by exactly the two prices" in
    // The pair stated as one figure, which is what makes either case above
    // meaningful: a build ignoring the member entirely produces the same number
    // twice and fails here whichever number that is.
    assert(
      spent(metered) - spent(warming) == schedule.coldAccountAccess - schedule.warmAccess,
      "one member of the rules moves one reach from the first-reach price to the repeat one"
    )

  "a settled-metering network" should "charge the same either way" in
    // The document's `requires: 2929` read from the other side. Below the
    // warm-and-cold scheme there is no set to be a member of, so the member is
    // inert -- and it is inert because nothing is seeded, not because anything
    // guards it.
    assert(
      spent(EvmFixtures.rules) == spent(EvmFixtures.rules.copy(coinbaseStartsWarm = true)),
      "where reaching costs one price, being in the set before the start changes nothing"
    )
