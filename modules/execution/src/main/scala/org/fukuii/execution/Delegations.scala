package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.fukuii.evm.WorldState
import org.fukuii.types.{Authority, Authorization, Delegation}

/** What a set-code transaction's authorizations do to the world before it runs.
  *
  * ==Every failure here is a SKIP, and that is the whole shape of it==
  *
  * An authorization can fail six ways -- a chain it does not name, a nonce at
  * the ceiling, a signature outside the document's bounds, an authority holding
  * code that is not a designation, a nonce that has moved, or a signature
  * recovering to nothing -- and **not one of them refuses the transaction**. The
  * transaction runs, pays for every slot it stated, and the authorities that
  * failed are simply not set.
  *
  * That is why nothing here returns a reason. A caller could not act on one:
  * there is no refusal to raise and no receipt field to record it in, so a
  * reason would be a value nothing reads. `ethereum/execution-specs` @
  * `0cc100eb1` writes the same shape, returning `None` from
  * `validate_authorization` and doing `continue`
  * (`src/ethereum/forks/prague/vm/eoa_delegation.py:210-215`).
  *
  * ==The authority is reached before it is validated, and the order is
  * observable==
  *
  * An authority is added to the reached set as soon as it is recovered, BEFORE
  * the code and nonce checks that may then reject it
  * (`eoa_delegation.py:175-189`). So an authorization that fails those checks
  * still leaves its authority warm, and a later operation touching that account
  * pays the lower price. **A build validating first and reaching second charges
  * more gas for the same block**, which is a state root apart on any block
  * carrying a failed authorization over an account it later touches.
  *
  * ==The refund is earned on EXISTENCE, and the document's prose says something
  * narrower==
  *
  * The executable specification tests `account_exists`
  * (`eoa_delegation.py:217`). `ethereum/EIPs` `00cc881b` rewrote the same rule's
  * prose from *"exists in the trie"* to *"is not empty"* on 2025-04-30, after
  * two public testnets were already running the fork. The two coincide wherever
  * empty accounts do not persist, which is everywhere from EIP-161 onward, and
  * this build follows the executable specification because that is what the
  * published corpus is filled from. **The divergence is unobservable at every
  * fork carrying this document**, and is recorded rather than resolved.
  */
object Delegations:

  /** What a transaction's whole authorization list produced.
    *
    * @param refund
    *   what the authorities that already existed earned back.
    * @param reached
    *   every authority whose signature recovered, including those an authorization
    *   then failed to set. They are warm for the rest of the transaction.
    */
  final case class Applied(refund: BigInt, reached: Set[Address])

  /** Applies `authorizations`, answering the refund they earned and every
    * authority they reached.
    *
    * **Both halves are returned because both are observable.** The refund moves
    * what the transaction is charged; the reached set moves what every later
    * operation touching one of those accounts is charged, and it includes
    * authorities whose authorization was then rejected.
    */
  def set(
      authorizations: Seq[Authorization],
      world: WorldState,
      chainId: UInt64,
      perAuthorization: BigInt,
      refundPerExisting: BigInt
  ): Applied =
    var refund = BigInt(0)
    var reached = Set.empty[Address]
    authorizations.foreach: authorization =>
      val outcome = authorityOf(authorization, world, chainId)
      reached = reached ++ outcome.reached
      outcome.authority.foreach: authority =>
        if world.accountExists(authority) then refund += perAuthorization - refundPerExisting
        world.setCode(authority, codeFor(authorization))
        world.setNonce(authority, UInt64.fromBits(world.nonceOf(authority).toBigInt.toLong + 1))
    Applied(refund, reached)

  /** The account an authorization sets, and the account it reached.
    *
    * **The two differ exactly when a state check rejects it**, which is why one
    * function answers both. The authority is reached as soon as it is
    * recovered; the code and nonce checks run after, and either may then leave
    * `authority` empty with `reached` still holding it.
    */
  private def authorityOf(authorization: Authorization, world: WorldState, chainId: UInt64): Outcome =
    val names = authorization.chainId.toBigInt
    if names != chainId.toBigInt && names != 0 then Outcome.Nothing
    else if authorization.nonce.toBigInt >= UInt64.MaxValue.toBigInt then Outcome.Nothing
    else
      Authority.of(authorization) match
        case None            => Outcome.Nothing
        case Some(authority) =>
          val held = world.codeOf(authority)
          val applies =
            (held.isEmpty || Delegation.isDesignation(held)) &&
              world.nonceOf(authority).toBigInt == authorization.nonce.toBigInt
          Outcome(if applies then Some(authority) else None, Set(authority))

  /** What one authorization produced: the authority it set, if any, and the
    * authority it reached, which is present whenever the signature recovered.
    */
  final private case class Outcome(authority: Option[Address], reached: Set[Address])

  private object Outcome:
    val Nothing: Outcome = Outcome(None, Set.empty)

  /** The code an authorization writes: a designation, or nothing where it names
    * the zero address.
    *
    * **Naming the zero address is how a delegation is UNDONE**, and it is the
    * reason this is not simply `designating(address)`. An account that has
    * delegated cannot deploy code to clear it, so the document reserves the zero
    * address to mean "hold no code at all"
    * (`eoa_delegation.py:224-228`).
    */
  private def codeFor(authorization: Authorization): Bytes =
    if authorization.address.toBytes.forall(_ == 0.toByte) then Bytes.Empty
    else Delegation.designating(authorization.address)
