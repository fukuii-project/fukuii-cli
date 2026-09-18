package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes}
import org.fukuii.evm.{EvmFixtures, GasSchedule, Word}
import org.fukuii.types.TransactionType
import org.scalatest.flatspec.AnyFlatSpec

/** The half of EIP-7623 that is settled before the machine runs.
  *
  * ==Why the two refusals are kept apart, which is what this file pins==
  *
  * The executable specification compares the limit against the greater of the
  * regular charge and the floor and raises ONE error for either
  * (`ethereum/execution-specs` @ `0cc100eb1`,
  * `src/ethereum/forks/prague/transactions.py:576`). The published corpus states
  * two names, `INTRINSIC_GAS_TOO_LOW` and `INTRINSIC_GAS_BELOW_FLOOR_GAS_COST`,
  * in **separate cases** rather than as alternatives on one --
  * `tests-v20.0.1` carries 76 of each across the floor's own state tests. So the
  * two are separable, and a build producing one refusal for both would satisfy
  * each published name with the other rule's refusal.
  *
  * `ethereum/go-ethereum` @ `02872e9ef` separates them too, as `ErrIntrinsicGas`
  * and `ErrFloorDataGas` (`core/error.go:82`).
  *
  * ==The boundary is pinned on both sides, because the arithmetic is narrow==
  *
  * A token is a zero byte, or four per non-zero byte, and the two charges over
  * the same bytes differ by six gas per token -- ten under the floor against
  * four under the regular charge. A transaction can therefore clear one and fail
  * the other by a single unit of gas, which is the case the corpus states and
  * the case a build comparing against the wrong figure passes.
  */
class CalldataFloorAdmissionSpec extends AnyFlatSpec:

  private val sender: Address = EvmFixtures.address(0x11)

  private val recipient: Address = EvmFixtures.address(0x22)

  /** A fork below the document: it prices a token at nothing, which
    * [[IntrinsicGas.calldataFloorOf]] reports as stating no floor at all.
    *
    * **The two calldata prices are the real ones rather than the shared
    * fixture's**, and that is load-bearing rather than tidiness. The fixture
    * schedule prices a zero byte at 19, above the floor's 10 per token, so the
    * floor could never exceed the regular charge and no case in this file would
    * be decided by the rule it names. The document's own neighbours are 4 per
    * zero byte and 16 per non-zero byte, under which a zero byte costs 4
    * regular against 10 under the floor -- which is what makes the floor bind at
    * all.
    */
  private val below: GasSchedule =
    EvmFixtures.rules.schedule.copy(
      transactionDataPerZeroByte = BigInt(4),
      transactionDataPerNonZeroByte = BigInt(16)
    )

  /** The same schedule with the document's price, so no case here is decided by
    * any other difference between two forks.
    */
  private val stating: GasSchedule = below.copy(transactionCalldataTokenFloor = BigInt(10))

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

  private def offer(data: Bytes, gasLimit: BigInt): OfferedTransaction =
    OfferedTransaction(
      transactionType = TransactionType.Legacy,
      sender = sender,
      nonce = 0,
      fee = FeeOffer.Fixed(BigInt(1)),
      gasLimit = gasLimit,
      to = Some(recipient),
      value = 0,
      data = data,
      accessList = Seq.empty,
      blobs = None
    )

  private def refusal(schedule: GasSchedule, data: Bytes, gasLimit: BigInt): Option[Refusal] =
    TransactionAdmission.admit(
      offer(data, gasLimit),
      world(),
      BigInt(30000000),
      None,
      None,
      rules,
      schedule,
      None
    ) match
      case Admission.Refused(reason) => Some(reason)
      case _                         => None

  /** One zero byte: one token, so the floor exceeds the regular charge by six. */
  private val oneZeroByte: Bytes = Bytes.fromArray(Array[Byte](0))

  private val regularCharge: BigInt = below.transactionBase + below.transactionDataPerZeroByte

  private val floorCharge: BigInt = below.transactionBase + 10

  "a limit between the charge and the floor" should "be refused under the floor's own name" in
    // The case the corpus states: the regular charge is met and the floor is
    // not. A build folding the two refusals into one reports the charge's name
    // here and diverges on every such published case.
    assert(
      refusal(stating, oneZeroByte, floorCharge - 1).contains(Refusal.IntrinsicGasBelowFloor),
      "the charge passed and the floor did not, and the refusal must say which"
    )

  it should "clear the regular charge in that case, or it tests nothing" in
    // The control. Were the limit also below the regular charge, the case above
    // would hold for a build that never checked the floor at all.
    assert(
      floorCharge - 1 >= regularCharge,
      "the limit must pass the charge, or the floor is not what refused it"
    )

  "a limit below the regular charge" should "be refused under the charge's name, not the floor's" in
    // The ordering. Both rules are broken here, and the specification's own
    // comparison puts the charge first.
    assert(
      refusal(stating, oneZeroByte, regularCharge - 1).contains(Refusal.IntrinsicGasTooLow),
      "a transaction failing both is refused by the first, as the specification orders them"
    )

  "a limit meeting the floor exactly" should "be admitted" in
    // The other side of the boundary. Without it, a comparison refusing one gas
    // too much would pass every case above.
    assert(
      refusal(stating, oneZeroByte, floorCharge).isEmpty,
      "the floor is a minimum the limit may equal"
    )

  "a fork stating no floor" should "admit a limit the floor would have refused" in
    // The absence, and the reason it is an absence rather than a zero price:
    // this limit is above the regular charge and below what the base alone
    // would make a floor, so a build reading a zero price as a floor of the
    // base refuses a transaction every pre-document network admitted.
    assert(
      refusal(below, oneZeroByte, floorCharge - 1).isEmpty,
      "no floor is stated, so only the regular charge decides"
    )

  "a transaction carrying no calldata" should "be admitted at the charge under both forks" in
    // The floor is the base where there are no tokens, and the regular charge
    // is at least the base, so the floor can never bind. Stated because it is
    // the commonest transaction there is.
    assert(
      refusal(stating, Bytes.Empty, below.transactionBase).isEmpty &&
        refusal(below, Bytes.Empty, below.transactionBase).isEmpty,
      "a plain transfer is unaffected by the document at either fork"
    )
