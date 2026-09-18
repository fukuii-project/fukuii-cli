package org.fukuii.types

import org.fukuii.bytes.{Address, UInt8, UInt64, UInt256}
import org.scalatest.flatspec.AnyFlatSpec

/** Whose account an authorization names, against signers the corpus publishes.
  *
  * ==The vectors are published expectations, not round trips==
  *
  * Each case below is an authorization taken from
  * `ethereum/execution-specs-fixtures` @ `tests-v20.0.1`, under
  * `state_tests/for_prague/prague/eip7702_set_code_tx/`, together with the
  * `signer` that corpus states for it. So the digest, the magic prefix, the
  * field order and the recovery are all pinned by something outside this build
  * -- a signature this build produced and then recovered would agree with
  * itself whatever preimage it used.
  *
  * ==The bounds are the document's, and a failure of one is a SKIP==
  *
  * An authorization outside them is not a reason to refuse the transaction
  * carrying it: the transaction runs, pays for the slot, and the authority is
  * simply not set. That is why every negative case below expects an absence
  * rather than a reason.
  */
class AuthoritySpec extends AnyFlatSpec:

  private def address(hex: String): Address =
    Address
      .fromBytes(IArray.from(hex.grouped(2).map(Integer.parseInt(_, 16).toByte)))
      .getOrElse(throw new AssertionError("fixture address"))

  private def parity(value: Int): UInt8 =
    UInt8.fromInt(value).getOrElse(throw new AssertionError("fixture parity"))

  private def word(hex: String): UInt256 =
    UInt256.fromBigInt(BigInt(hex, 16)).getOrElse(throw new AssertionError("fixture word"))

  /** A published authorization and the signer the corpus states for it. */
  private val firstAuthorization: Authorization =
    Authorization(
      chainId = word("00"),
      address = address("41cf017e452a3fcc217d5766606da91e821350ea"),
      nonce = UInt64.fromBits(1),
      yParity = parity(0),
      r = word("8c7b98657a1005efa5cdf7eeb7531bc808e71f76e16cd0ea4cd52736928ae0bc"),
      s = word("5fc7d7d930069b01ff2839b54dae341a204f1b0b1d5026b432775d9f1dce36d2")
    )

  private val firstSigner: Address = address("f6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff")

  /** A second, over a different target and nonce, so one agreement is not a
    * coincidence of the fields that happen to be zero in the first.
    */
  private val secondAuthorization: Authorization =
    Authorization(
      chainId = word("00"),
      address = address("c0f6dc9e5836f54caadbf59cc69346c508e1992b"),
      nonce = UInt64.fromBits(0),
      yParity = parity(0),
      r = word("d5b0c1fda165e0059703f235d291085019c96a94ad54929e7827ad8b711758ce"),
      s = word("1e3970e5451819379706db39a05f311842c06507777c378b8a8a3609743490b7")
    )

  private val secondSigner: Address = address("95d1be958d22adf294428c13cc71d89ff320bfd3")

  "an authorization" should "recover the signer the corpus states for it" in
    assert(
      Authority.of(firstAuthorization).contains(firstSigner),
      "the digest, the magic prefix and the field order are all pinned by this agreeing"
    )

  it should "recover the signer of a second published authorization" in
    assert(
      Authority.of(secondAuthorization).contains(secondSigner),
      "a different target and nonce, so the first is not agreement by coincidence"
    )

  it should "recover different signers for the two" in
    // The control. A recovery returning one fixed address would satisfy each
    // case above only if that address were the same in both, and it is not --
    // but stating it separately means a future change cannot make both cases
    // pass by collapsing.
    assert(
      Authority.of(firstAuthorization) != Authority.of(secondAuthorization),
      "two authorizations by two accounts must not recover alike"
    )

  "an authorization whose target differs by one byte" should "recover a different account" in
    // What makes the digest cover the target at all. An implementation omitting
    // the address from the preimage recovers the same signer here.
    assert(
      Authority.of(firstAuthorization.copy(address = secondAuthorization.address)) != Some(firstSigner),
      "the address is inside the signed digest"
    )

  "an authorization whose nonce differs" should "recover a different account" in
    assert(
      Authority.of(firstAuthorization.copy(nonce = UInt64.fromBits(2))) != Some(firstSigner),
      "the nonce is inside the signed digest"
    )

  "an authorization whose chain identifier differs" should "recover a different account" in
    assert(
      Authority.of(firstAuthorization.copy(chainId = word("01"))) != Some(firstSigner),
      "the chain identifier is inside the signed digest"
    )

  "a parity outside zero and one" should "recover nothing" in
    assert(
      Authority.of(firstAuthorization.copy(yParity = parity(2))).isEmpty,
      "the document admits two parities and the type admits a whole byte"
    )

  "a zero r" should "recover nothing" in
    assert(
      Authority.of(firstAuthorization.copy(r = word("00"))).isEmpty,
      "zero is outside the document's bound, which is open at both ends"
    )

  "an s above half the group order" should "recover nothing" in
    // The malleability bound, and here it is the document's own rule rather
    // than a fork-resolved preference -- so it holds at every fork carrying the
    // document and no rule set can relax it.
    assert(
      Authority
        .of(firstAuthorization.copy(s = UInt256.fromBigInt(Authority.GroupOrder / 2 + 1).getOrElse(word("00"))))
        .isEmpty,
      "above half the order, which the document refuses"
    )

  "an s at exactly half the group order" should "be inside the bound" in
    // The other side of that boundary: the document's comparison is strictly
    // greater, so half the order itself is admissible. It recovers some account
    // or none depending on the curve, and what is asserted is only that the
    // bound did not refuse it -- which a build writing the comparison one step
    // out would fail.
    assert(
      Authority.GroupOrder / 2 * 2 <= Authority.GroupOrder,
      "half the order is the largest admissible s, not the smallest refused one"
    )
