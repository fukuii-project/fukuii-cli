package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.Bytes
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.execution.IntrinsicGas
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-7623 changes, and the one thing a zero price must not do.
  *
  * The document states a second price over calldata and reprices nothing, so the
  * rule-set delta is one figure. What the assertions below are really about is
  * the SHAPE of its absence: the floor adds the transaction base, so a fork
  * without the document must report no floor at all rather than a floor of the
  * base -- and the two are only distinguishable by asking.
  */
class Eip7623Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.cancun

  private val adopted: UpgradeRules = base.adopting(Eip7623.component)

  /** One zero byte: one token, the smallest calldata that moves the floor. */
  private val OneZeroByte: Bytes = Bytes.fromArray(Array[Byte](0))

  /** One non-zero byte: four tokens, which is the token count's whole rule. */
  private val OneNonZeroByte: Bytes = Bytes.fromArray(Array[Byte](1))

  "adopting EIP-7623" should "price a calldata token at ten" in
    assert(
      adopted.evm.schedule.transactionCalldataTokenFloor == BigInt(10),
      "the figure the floor multiplies its token count by"
    )

  it should "leave the per-byte prices the charge is built from alone" in
    // The whole design of the document: raising those would have charged every
    // transaction more, which is what the floor exists to avoid.
    assert(
      adopted.evm.schedule.transactionDataPerZeroByte == base.evm.schedule.transactionDataPerZeroByte &&
        adopted.evm.schedule.transactionDataPerNonZeroByte == base.evm.schedule.transactionDataPerNonZeroByte,
      "a second price over the same bytes, not a repricing of the first"
    )

  it should "state no floor at the fork below it" in
    // The assertion this file exists for. A zero price yields `0 * tokens +
    // 21000` if the base is added unconditionally, and 21,000 is a figure a
    // refunded transaction can settle below -- so a fork without the document
    // reporting a floor of the base would overcharge it.
    assert(
      IntrinsicGas.calldataFloorOf(base.evm.schedule, OneZeroByte).isEmpty,
      "absent, and specifically not the transaction base"
    )

  it should "count a zero byte as one token" in
    assert(
      IntrinsicGas
        .calldataFloorOf(adopted.evm.schedule, OneZeroByte)
        .contains(BigInt(10) + adopted.evm.schedule.transactionBase),
      "one token at ten, over the base the fork already states"
    )

  it should "count a non-zero byte as four" in
    assert(
      IntrinsicGas
        .calldataFloorOf(adopted.evm.schedule, OneNonZeroByte)
        .contains(BigInt(40) + adopted.evm.schedule.transactionBase),
      "four tokens at ten, which is what makes the two byte classes differ under the floor"
    )

  it should "state the base alone for a transaction carrying no calldata" in
    assert(
      IntrinsicGas
        .calldataFloorOf(adopted.evm.schedule, Bytes.Empty)
        .contains(adopted.evm.schedule.transactionBase),
      "no tokens, so the floor is the base -- which is below what any such transaction spends, so it never binds"
    )

  it should "leave the opcode table untouched" in
    assert(
      adopted.evm.table == base.evm.table,
      "a charge paid before execution reaches no operation"
    )
