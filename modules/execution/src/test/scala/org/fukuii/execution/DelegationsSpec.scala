package org.fukuii.execution

import org.fukuii.bytes.{Address, UInt8, UInt64, UInt256}
import org.fukuii.evm.{EvmFixtures, Word}
import org.fukuii.types.{Authority, Authorization, Delegation}
import org.scalatest.flatspec.AnyFlatSpec

/** What a transaction's authorizations do to the world, and what they do not.
  *
  * ==Every rejection is a skip, so the assertions are about ABSENCE OF EFFECT==
  *
  * There is no refusal to observe and no reason returned, so a rejected
  * authorization is visible only as the world being unchanged where it would
  * otherwise have been written. Each negative case below therefore asserts that
  * the authority holds no code -- which is also what it held before, so each
  * case is paired with the positive that proves the fixture could have written
  * one.
  *
  * ==The signatures are real, and that is what makes the cases reachable==
  *
  * An authorization is applied only if it recovers, so a fabricated signature
  * would make every case below pass for the wrong reason -- the authority would
  * be unset because nothing recovered, not because the rule under test
  * rejected it. The published authorization from the corpus is used as the
  * base, and each case varies exactly one field away from it.
  */
class DelegationsSpec extends AnyFlatSpec:

  private def address(hex: String): Address =
    Address
      .fromBytes(IArray.from(hex.grouped(2).map(Integer.parseInt(_, 16).toByte)))
      .getOrElse(throw new AssertionError("fixture address"))

  private def word(hex: String): UInt256 =
    UInt256.fromBigInt(BigInt(hex, 16)).getOrElse(throw new AssertionError("fixture word"))

  private def parity(value: Int): UInt8 =
    UInt8.fromInt(value).getOrElse(throw new AssertionError("fixture parity"))

  /** A published authorization, whose signer the corpus states. */
  private val authorization: Authorization =
    Authorization(
      chainId = word("00"),
      address = address("41cf017e452a3fcc217d5766606da91e821350ea"),
      nonce = UInt64.fromBits(1),
      yParity = parity(0),
      r = word("8c7b98657a1005efa5cdf7eeb7531bc808e71f76e16cd0ea4cd52736928ae0bc"),
      s = word("5fc7d7d930069b01ff2839b54dae341a204f1b0b1d5026b432775d9f1dce36d2")
    )

  private val authority: Address = address("f6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff")

  private val target: Address = address("41cf017e452a3fcc217d5766606da91e821350ea")

  private val chainId: UInt64 = UInt64.fromBits(1)

  private val PerAuthorization: BigInt = BigInt(25000)

  private val RefundPerExisting: BigInt = BigInt(12500)

  /** A world where the authority's nonce already matches what it signed for, so
    * the authorization applies unless the case under test stops it.
    */
  private def world(): EvmFixtures.MapWorldState =
    val built = new EvmFixtures.MapWorldState
    built.setNonce(authority, UInt64.fromBits(1))
    built

  private def applied(
      auth: Authorization = authorization,
      chain: UInt64 = chainId,
      prepared: EvmFixtures.MapWorldState = world()
  ): (Delegations.Applied, EvmFixtures.MapWorldState) =
    val outcome = Delegations.set(Seq(auth), prepared, chain, PerAuthorization, RefundPerExisting)
    (outcome, prepared)

  "an authorization that applies" should "write a designation naming its target" in {
    val (_, w) = applied()
    assert(
      Delegation.targetOf(w.codeOf(authority)).contains(target),
      "the authority now runs the code of the account it named"
    )
  }
  it should "move the authority's own nonce on by one" in {
    val (_, w) = applied()
    assert(
      w.nonceOf(authority) == UInt64.fromBits(2),
      "an authorization is spent, so the same one cannot be replayed"
    )
  }
  it should "reach the authority" in {
    val (outcome, _) = applied()
    assert(
      outcome.reached.contains(authority),
      "the account is warm for the rest of the transaction"
    )
  }
  "an authorization naming another chain" should "not be applied" in {
    val (_, w) = applied(auth = authorization.copy(chainId = word("63")))
    assert(
      w.codeOf(authority).isEmpty,
      "a chain the transaction is not on, so the authorization is skipped"
    )
  }
  "an authorization naming chain zero" should "be applied on any chain" in {
    // Zero is the document's wildcard, and it is the value the published
    // authorization actually carries -- so this is the case every other case
    // here rests on.
    val (_, w) = applied(chain = UInt64.fromBits(999))
    assert(
      Delegation.targetOf(w.codeOf(authority)).contains(target),
      "a zero chain identifier names every chain"
    )
  }
  "an authorization whose nonce has moved" should "not be applied" in {
    val prepared = world()
    prepared.setNonce(authority, UInt64.fromBits(2))
    val (_, w) = applied(prepared = prepared)
    assert(
      w.codeOf(authority).isEmpty,
      "the authority signed for a nonce it no longer holds"
    )
  }
  it should "still have reached the authority" in {
    // THE ORDERING, and the reason it is asserted separately: the authority is
    // reached as soon as its signature recovers, before the nonce is compared.
    // A build validating first and reaching second charges more gas for the
    // same block.
    val prepared = world()
    prepared.setNonce(authority, UInt64.fromBits(2))
    val (outcome, _) = applied(prepared = prepared)
    assert(
      outcome.reached.contains(authority),
      "a rejected authorization still leaves its authority warm"
    )
  }
  "an authority holding ordinary code" should "not be applied" in {
    val prepared = world()
    prepared.setCode(authority, EvmFixtures.bytesOf("0x6001"))
    val (_, w) = applied(prepared = prepared)
    assert(
      !Delegation.isDesignation(w.codeOf(authority)),
      "a contract cannot be made to delegate"
    )
  }
  "an authority already delegated" should "be applied over" in {
    // The carve-out that makes a delegation changeable: code stops an
    // authorization unless that code is itself a designation.
    val prepared = world()
    prepared.setCode(authority, Delegation.designating(address("00000000000000000000000000000000000000aa")))
    val (_, w) = applied(prepared = prepared)
    assert(
      Delegation.targetOf(w.codeOf(authority)).contains(target),
      "a delegated account may re-delegate, which is what keeps one revocable"
    )
  }
  "an authorization naming the zero address" should "clear the designation" in {
    // How a delegation is UNDONE. A delegated account cannot deploy code to
    // clear itself, so the document reserves the zero address for it.
    //
    // **The authority is recovered rather than assumed, and that is forced.**
    // The target is inside the signed digest, so changing it to the zero address
    // changes which account signed -- a case written against the original
    // authority would find it untouched and pass for the wrong reason, having
    // tested that a non-recovering authorization writes nothing.
    val revoking = authorization.copy(address = address("0000000000000000000000000000000000000000"))
    val revoker = Authority.of(revoking).getOrElse(fail("the revoking authorization must recover"))
    val prepared = world()
    prepared.setNonce(revoker, UInt64.fromBits(1))
    prepared.setCode(revoker, Delegation.designating(target))
    val (_, w) = applied(auth = revoking, prepared = prepared)
    assert(
      w.codeOf(revoker).isEmpty,
      "naming the zero address writes no code at all, rather than a designation to nowhere"
    )
  }

  it should "have been delegated before the revocation, or that case tests nothing" in {
    // The control. Were the account holding no code to begin with, the case
    // above would hold for a build that ignored the authorization entirely.
    val revoking = authorization.copy(address = address("0000000000000000000000000000000000000000"))
    val revoker = Authority.of(revoking).getOrElse(fail("the revoking authorization must recover"))
    val prepared = world()
    prepared.setCode(revoker, Delegation.designating(target))
    assert(
      Delegation.isDesignation(prepared.codeOf(revoker)),
      "the fixture must start delegated, or clearing it is unobservable"
    )
  }
  "an authority that already exists" should "earn the rebate" in {
    val prepared = world()
    prepared.setBalance(authority, Word(BigInt(1)))
    val (outcome, _) = applied(prepared = prepared)
    assert(
      outcome.refund == PerAuthorization - RefundPerExisting,
      "the charge was paid at the higher figure and the difference comes back"
    )
  }
  "an authorization that does not apply" should "earn nothing" in {
    val prepared = world()
    prepared.setNonce(authority, UInt64.fromBits(2))
    val (outcome, _) = applied(prepared = prepared)
    assert(
      outcome.refund == BigInt(0),
      "a skipped authorization sets nothing and rebates nothing, though it was charged for"
    )
  }
