package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, Hash, Hex, UInt256, UInt64}
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{BaseFeeTail, BlockHeader, BlockNonce, Bloom, Seal, Withdrawal, WithdrawalsTail}

/** Crediting withdrawals, and the commitment a header states over them.
  *
  * ==Every figure here is published, and none of it was computed by this
  * project==
  *
  * The balances and the roots below are read out of
  * `ethereum/execution-specs-fixtures` `tests-v20.0.1`,
  * `fixtures/blockchain_tests/for_shanghai/shanghai/eip4895_withdrawals/withdrawals/`,
  * whose `zero_amount.json` and `withdrawals_root.json` were produced by a
  * different implementation from a different reading. That is what makes them
  * known answers rather than a restatement of this code's own behavior --
  * an assertion derived the same way as the implementation agrees with it
  * however wrong both are.
  *
  * ==The unit conversion is asserted against a balance, never against a
  * factor==
  *
  * A test comparing the credit to `amount * WeiPerGwei` would pass against a
  * build that dropped the conversion and a constant of one, so the figures
  * below are the wei balances the corpus states and the Gwei amounts that
  * produced them. The widest of them, `2^64 - 1` Gwei, is where an arithmetic
  * that overflowed rather than a conversion that vanished would show.
  */
class WithdrawalsSpec extends AnyFlatSpec:

  private def recipient(byte: Int): Address = EvmFixtures.address(byte)

  private def count(n: BigInt): UInt64 =
    UInt64.fromBigInt(n).getOrElse(throw new IllegalStateException("wider than the field: " + n.toString))

  /** A withdrawal of `gwei` to `to`, the two counters being unread by every
    * rule under test and set to the position anyway.
    */
  private def withdrawal(index: Int, to: Address, gwei: BigInt): Withdrawal =
    Withdrawal(count(BigInt(index)), count(BigInt(index)), to, count(gwei))

  /** A state, and the accounts a destruction removed from it.
    *
    * The destruction is a parameter in production, so a spec that ignored it
    * would assert the withdrawal's own write and never the rule that undoes it.
    */
  private def worldWithDestruction(): (EvmFixtures.MapWorldState, Address => Unit) =
    val world = new EvmFixtures.MapWorldState
    val destroy: Address => Unit = address =>
      val _ = world.balances.remove(address)
      val _ = world.nonces.remove(address)
      val _ = world.codes.remove(address)
      val _ = world.present.remove(address)
    (world, destroy)

  private def credited(withdrawals: Seq[Withdrawal]): EvmFixtures.MapWorldState =
    val (world, destroy) = worldWithDestruction()
    Withdrawals.credit(withdrawals, world, destroy)
    world

  /** The recipient every row of `withdrawals_root.json` withdraws to. */
  private val CorpusRecipient: Address =
    Address.fromHex("0x60478971839c84963db1986c096a4f416ff31f42").toOption.get

  private def corpusWithdrawal(index: Int): Withdrawal =
    Withdrawal(count(BigInt(index)), UInt64.Zero, CorpusRecipient, count(BigInt(1)))

  private def rootHex(withdrawals: Seq[Withdrawal]): String =
    "0x" + Hex.encode(Withdrawals.root(withdrawals).toBytes)

  /** A header committing to `root`, built through the tail chain a real one
    * carries rather than through a bare option.
    *
    * The root is a header element behind the base fee, so a header cannot state
    * one without also stating a charge -- and going through
    * `org.fukuii.types.BlockHeader` rather than comparing two options is what
    * makes the case below exercise that nesting instead of asserting that two
    * equal values are equal.
    */
  private def headerCommittingTo(root: Option[Hash]): BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = EvmFixtures.hash(0),
      beneficiary = EvmFixtures.address(0),
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = UInt256.Zero,
      number = UInt64.fromBits(1L),
      gasLimit = UInt64.fromBits(30000000L),
      gasUsed = UInt64.Zero,
      timestamp = UInt64.fromBits(12L),
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(EvmFixtures.hash(0), BlockNonce.Zero),
      tail = Some(BaseFeeTail(UInt256.Zero, root.map(WithdrawalsTail(_))))
    )

  // ── The unit ──────────────────────────────────────────────────────────────

  /** `0x3b9aca00`, the balance the corpus states for a withdrawal of one Gwei.
    */
  private val OneGweiInWei: BigInt = BigInt("1000000000")

  /** `0x3b9ac9ffffffffffc4653600`, the balance the corpus states for a
    * withdrawal of the widest amount the field can hold.
    */
  private val MaxAmountInWei: BigInt = BigInt("18446744073709551615000000000")

  "credit" should "raise a recipient by a billion wei for each Gwei withdrawn" in {
    val world = credited(Seq(withdrawal(1, recipient(0x95), BigInt(1))))
    assert(
      world.balanceOf(recipient(0x95)).toBigInt == OneGweiInWei,
      "one Gwei is the published balance 0x3b9aca00, not 1"
    )
  }

  it should "convert the widest amount the field can hold without losing it" in {
    val widest = BigInt(2).pow(64) - 1
    val world = credited(Seq(withdrawal(2, recipient(0x62), widest)))
    assert(
      world.balanceOf(recipient(0x62)).toBigInt == MaxAmountInWei,
      "the published balance for 0xffffffffffffffff Gwei"
    )
  }

  it should "add to a balance the recipient already held" in {
    val (world, destroy) = worldWithDestruction()
    world.setBalance(recipient(0x95), org.fukuii.evm.Word(BigInt(7)))
    Withdrawals.credit(Seq(withdrawal(1, recipient(0x95), BigInt(1))), world, destroy)
    assert(world.balanceOf(recipient(0x95)).toBigInt == OneGweiInWei + 7, "a credit is not a set")
  }

  it should "let a recipient named twice in one list see the earlier credit" in {
    val world = credited(Seq(withdrawal(0, recipient(0x95), BigInt(1)), withdrawal(1, recipient(0x95), BigInt(2))))
    assert(world.balanceOf(recipient(0x95)).toBigInt == OneGweiInWei * 3, "both credits land on one account")
  }

  // ── Crediting nothing ─────────────────────────────────────────────────────

  it should "leave no account behind when it credits nothing to an address holding none" in {
    val world = credited(Seq(withdrawal(0, recipient(0xc0), BigInt(0))))
    assert(
      !world.accountExists(recipient(0xc0)),
      "the corpus states this address in neither its pre nor its post state"
    )
  }

  it should "leave an existing account alone when it credits nothing to it" in {
    val (world, destroy) = worldWithDestruction()
    world.setNonce(recipient(0x37), count(BigInt(1)))
    Withdrawals.credit(Seq(withdrawal(0, recipient(0x37), BigInt(0))), world, destroy)
    assert(
      world.accountExists(recipient(0x37)) && world.nonceOf(recipient(0x37)).toBigInt == 1,
      "an account with a nonce is not empty and survives a credit of nothing"
    )
  }

  it should "bring a recipient into being when it credits something" in {
    val world = credited(Seq(withdrawal(1, recipient(0x95), BigInt(1))))
    assert(world.accountExists(recipient(0x95)), "the corpus states this address in its post state and not its pre")
  }

  it should "reach the corpus's whole four-withdrawal answer at once" in {
    // The published case mixes both zero-amount readings with both magnitudes,
    // so a build that got any one of the four wrong disagrees here.
    val (world, destroy) = worldWithDestruction()
    world.setNonce(recipient(0x37), count(BigInt(1)))
    Withdrawals.credit(
      Seq(
        withdrawal(0, recipient(0xc0), BigInt(0)),
        withdrawal(0, recipient(0x37), BigInt(0)),
        withdrawal(1, recipient(0x95), BigInt(1)),
        withdrawal(2, recipient(0x62), BigInt(2).pow(64) - 1)
      ),
      world,
      destroy
    )
    val reached = Seq(
      world.accountExists(recipient(0xc0)),
      world.nonceOf(recipient(0x37)).toBigInt == 1,
      world.balanceOf(recipient(0x37)).toBigInt == 0,
      world.balanceOf(recipient(0x95)).toBigInt == OneGweiInWei,
      world.balanceOf(recipient(0x62)).toBigInt == MaxAmountInWei
    )
    assert(reached == Seq(false, true, true, true, true), "the published post state, account by account")
  }

  // ── The commitment ────────────────────────────────────────────────────────

  "root" should "commit to an empty list as the corpus states" in
    assert(
      rootHex(Seq.empty) == "0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421",
      "the published root for n_withdrawals_0"
    )

  it should "commit to one withdrawal as the corpus states" in
    assert(
      rootHex(Seq(corpusWithdrawal(0))) == "0xe6df9c8b0020ff28198b5dfe5c841369f278bc988171df7319309d7f40919f61",
      "the published root for n_withdrawals_1"
    )

  it should "commit to sixteen withdrawals as the corpus states" in
    // Sixteen rows identical but for their own index field, so this fails on a
    // build that keyed the trie by anything the index does not decide -- and it
    // is the case that fills every branch slot.
    assert(
      rootHex((0 until 16).map(corpusWithdrawal)) ==
        "0x5af0ff570b857504880dde02de01748d319be84497b7bcb51619a2b450f69dd7",
      "the published root for n_withdrawals_16"
    )

  it should "differ from the empty commitment once a list has an entry" in
    assert(rootHex(Seq(corpusWithdrawal(0))) != rootHex(Seq.empty), "an entry is not nothing")

  "a header holding a commitment" should "agree with the one over the block's own list" in
    assert(
      headerCommittingTo(Some(Withdrawals.root(Seq(corpusWithdrawal(0))))).withdrawalsRoot ==
        Some(Withdrawals.root(Seq(corpusWithdrawal(0)))),
      "the check a caller holding a header and a body performs"
    )

  it should "disagree with the one over a different list" in
    assert(
      headerCommittingTo(Some(Withdrawals.root(Seq(corpusWithdrawal(0))))).withdrawalsRoot !=
        Some(Withdrawals.root((0 until 16).map(corpusWithdrawal))),
      "a commitment over one list does not match another"
    )

  it should "disagree with a block that produced none" in
    // The presence half of the same comparison, which is what catches a body
    // carrying withdrawals at a height whose headers state no root: the option
    // settles presence and value in one step.
    assert(
      headerCommittingTo(Some(Withdrawals.root(Seq.empty))).withdrawalsRoot != Option.empty[Hash],
      "the empty commitment is not the absence of one"
    )

  "a header holding no commitment" should "disagree with a block that produced one" in
    assert(
      headerCommittingTo(None).withdrawalsRoot != Some(Withdrawals.root(Seq.empty)),
      "a header that omits the field cannot match a block whose body carried a list"
    )
