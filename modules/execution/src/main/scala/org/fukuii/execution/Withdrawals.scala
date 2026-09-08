package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.evm.{Interpreter, Word, WorldState}
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.Trie
import org.fukuii.types.Withdrawal

/** What a block does with the withdrawals it carries: the balances they credit,
  * and the commitment its header states over them.
  *
  * ==A withdrawal is not a transaction, and almost every rule around one is
  * absent==
  *
  * EIP-4895 calls them *"system-level operations"* precisely to say what they
  * are not: *"These operations create unconditional balance increases to the
  * specified recipients"*, and *"This operation has no associated gas costs"*
  * (`ethereum/EIPs` @ `dbfa6bee8` (2026-08-26), `EIPS/eip-4895.md`, Final). So
  * there is no sender, no signature, no nonce, no charge, no receipt and no
  * refusal -- which is why nothing here returns an [[Either]] and why a
  * withdrawal cannot make a block invalid by failing. It can only make one
  * invalid by disagreeing with the commitment, and that is [[root]].
  *
  * ==The amount is in Gwei, and this is the whole of the hazard==
  *
  * *"a nonzero `amount` of ether given in Gwei (1e9 wei) as a `uint64` value"*,
  * and later, in the state-transition section, *"Recall that the `amount` is
  * given in units of Gwei so a conversion to units of wei must be performed
  * when working with account balances in the execution state"*. The document
  * says it twice because the failure is invisible: crediting the field as it
  * stands under-pays by a factor of a billion **uniformly**, so every balance is
  * self-consistent, every root is stable, and nothing about the result looks
  * wrong except its magnitude.
  *
  * `ethereum/execution-specs` @ `20f7f6271a` (2026-08-26) writes the factor into
  * the call itself -- `forks/shanghai/fork.py:649`,
  * `create_ether(wd_state, wd.address, U256(wd.amount) * U256(10**9))` -- and
  * `ethereum/go-ethereum` @ `e9e35a42f8` (2026-08-26) comments it,
  * `consensus/beacon/consensus.go:353-355`: *"Convert amount from gwei to
  * wei"*, over `amount.Mul(amount, uint256.NewInt(params.GWei))`.
  * `besu-eth/besu` @ `fdf1247c6d` (2026-08-26) makes the unit a type instead,
  * `WithdrawalsProcessor` calling `withdrawal.getAmount().getAsWei()` on a
  * `GWei`. **This build's [[org.fukuii.types.Withdrawal]] carries a bare
  * `UInt64` documented as Gwei**, so the conversion is a rule here rather than
  * a property of the value, and it is asserted against published balances.
  */
object Withdrawals:

  /** How many wei one Gwei is.
    *
    * Stated as a power rather than as a literal with nine zeros in it, which is
    * a digit-count nobody can check by looking. The specification writes
    * `U256(10**9)` and go-ethereum writes `GWei = 1e9`; both are the exponent
    * rather than the expansion.
    */
  val WeiPerGwei: BigInt = BigInt(10).pow(9)

  /** The commitment a header states over `withdrawals`.
    *
    * ==Not a second derivation, deliberately==
    *
    * [[org.fukuii.trie.Trie.rootOfIndexed]] is the one this project has, and
    * the proposal's own words are why it is shared rather than written here:
    * the commitment *"is constructed identically to the transactions root"*.
    * What this adds is the encoding, which is the half the three consumers do
    * NOT share -- a withdrawal is its four-element list, where a typed
    * transaction is its envelope.
    *
    * ==An empty list has a commitment, and it is not absence==
    *
    * A block at this fork carrying no withdrawals commits to the empty trie's
    * root rather than to nothing, and its header still carries the field. That
    * is what makes [[org.fukuii.chainspec.HeaderRules]]'s presence rule and this
    * value independent: the first says whether the header has a root at all,
    * this says what it must be.
    */
  def root(withdrawals: Seq[Withdrawal]): Hash =
    Trie.rootOfIndexed(withdrawals.map(w => Bytes.fromIArray(RlpCodec.encodeTo(w))))

  /** Credits each of `withdrawals` to its recipient, in the order given.
    *
    * ==Written through, because there is nothing to roll back==
    *
    * The proposal states the change *"is unconditional and **MUST** not fail"*,
    * so the journal both of the surveyed sources open around this step --
    * the specification's `wd_state = TransactionState(parent=block_env.state)`
    * folded in by `incorporate_tx_into_block`, and besu's `blockUpdater.updater()`
    * closed by `commit()` -- is a grouping and never a rollback point. Nothing
    * here can reach a state a caller would want to discard, so no journal is
    * opened and each credit is visible to the one after it, which is what a
    * recipient named twice in one list requires.
    *
    * ==Crediting nothing must leave nothing behind, and that is not what the
    * obvious code does==
    *
    * [[org.fukuii.evm.WorldState]] is total in its writes: setting a balance on
    * an address holding no account brings one into being. So a zero credit,
    * written plainly, CREATES an empty account -- a different state root from
    * the one every source reaches, produced by code that looks like it did
    * nothing.
    *
    * The three sources close it three ways and agree. The specification's
    * `create_ether` reaches `modify_state`, which is documented *"If, after
    * modification, the account exists and has zero nonce, empty code, and zero
    * balance, it is destroyed"* and runs `account_exists_and_is_empty` then
    * `destroy_account` (`state_tracker.py:490-492`). besu's
    * `WithdrawalsProcessor` calls `getOrCreate` and then
    * `clearAccountsThatAreEmpty()`. go-ethereum reaches it through its EIP-158
    * sweep and says so of the case directly: *"Zero amount withdrawal, account
    * is accessed potential without state changes"*.
    *
    * **The removal is not gated on the fork's empty-account rule**, matching the
    * two sources that make withdrawals their own step rather than go-ethereum,
    * which reaches it through a sweep its own EIP-158 flag controls. On the
    * network that defines both, the fork carrying this proposal sits far above
    * the one that introduced empty-account clearing, so the two readings agree
    * wherever this runs and the choice is unobservable. Written as a guard it
    * would read as a rule that varies, and no source varies it.
    *
    * **The existence half of the pair below is implied rather than
    * load-bearing**, which is the opposite of what it is at the one other site
    * in this module that writes this shape.
    * [[org.fukuii.evm.WorldState]] is total in its writes, so the credit above
    * has already brought the recipient into being by the time it is asked and
    * the answer is always yes. It is written out because the specification's
    * `account_exists_and_is_empty` is the same redundant pair after the same
    * total write, and because [[TransactionProcessor]] reaches this shape over
    * accounts a transaction merely touched, where it genuinely does the work.
    *
    * @param destroyAccount
    *   removes an account and the storage under it.
    *   [[TransactionProcessor.settle]] states the contract, which this does not
    *   restate; it is reached here for the empty-account rule above and for
    *   nothing else.
    */
  def credit(withdrawals: Seq[Withdrawal], world: WorldState, destroyAccount: Address => Unit): Unit =
    withdrawals.foreach: withdrawal =>
      val recipient = withdrawal.address
      raiseBalance(world, recipient, weiOf(withdrawal))
      if world.accountExists(recipient) && Interpreter.deadAt(world, recipient) then destroyAccount(recipient)

  /** What `withdrawal` is worth in the units a balance is held in. */
  private def weiOf(withdrawal: Withdrawal): BigInt = withdrawal.amount.toBigInt * WeiPerGwei

  /** `recipient`'s balance raised by `credit`, refusing a result no account can
    * hold.
    *
    * The bound is [[TransactionProcessor]]'s reasoning arriving at a credit
    * nobody signed: [[org.fukuii.evm.Word]] wraps, so a sum past the ceiling
    * answers a small balance rather than failing, and the arithmetic is
    * therefore done in arbitrary precision and bounded before the word is built.
    *
    * The widest credit this proposal admits is `2^64 - 1` Gwei, which is under
    * `2^94` wei, so reaching the ceiling needs a recipient already holding
    * within that of `2^256` -- more than the ether that exists, and a state no
    * chain reaches. A caller that supplied one has a broken precondition and is
    * told so.
    */
  private def raiseBalance(world: WorldState, recipient: Address, credit: BigInt): Unit =
    val raised = world.balanceOf(recipient).toBigInt + credit
    if raised > Word.MaxValue.toBigInt then
      throw new IllegalStateException(
        "a withdrawal raised " + recipient.toString + " to a balance no account can hold: " + raised.toString
      )
    world.setBalance(recipient, Word(raised))
