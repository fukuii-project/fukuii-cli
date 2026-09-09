package org.fukuii.consensus.pos

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.types.{Bloom, Withdrawal}

/** Payloads taken verbatim from the published engine fixtures, with the block
  * hashes their senders computed.
  *
  * ==Why these and not a payload built here==
  *
  * A payload assembled in this tree could only carry a block hash this tree
  * computed, so a test over it would assert that the translation agrees with
  * itself. These carry a hash produced by the tool that generated the fixture,
  * so reproducing it is evidence about the derivation rather than about its own
  * consistency — every constant the merge fixed, both derived commitments, the
  * seal, the tail depth and the field order all have to be right at once or the
  * hash differs.
  *
  * ==Where they come from==
  *
  * `ethereum/execution-specs-fixtures` at the `tests-v20.0.1` release,
  * `blockchain_tests_engine`, the first case under each label carrying one
  * transaction — and, for the second, withdrawals. Both are cases the corpus
  * expects to be accepted, so their payloads are well-formed.
  *
  * **This is not the corpus being wired in.** These are two hand-picked vectors
  * transcribed into source, the way this project's other published vectors are.
  * A harness that reads the release is a later phase's, and it is what would
  * turn two cases into thousands.
  *
  * ==One transcription note, and it is a finding about the carrier==
  *
  * The Engine API writes a `QUANTITY` in minimal hex, so `0x1` and `0x7270e00`
  * are both well-formed on the wire and both have an odd number of digits.
  * [[org.fukuii.bytes.Hex]] rejects an odd-length body outright — deliberately,
  * since a decoder that pads an unexpected input turns a corrupt value into a
  * plausible one. So the two are not the same encoding, and something has to
  * bridge them.
  *
  * That something belongs to whatever decodes the JSON, not here. This file
  * bridges it in one helper so the vectors stay exactly as published rather than
  * being normalized on the way in.
  */
object PublishedPayloads:

  private def hash(hex: String): Hash = Hash.fromHex(hex).toOption.get

  private def address(hex: String): Address = Address.fromHex(hex).toOption.get

  private def data(hex: String): Bytes = Bytes.fromHex(hex).toOption.get

  /** A `QUANTITY` as the Engine API writes it, left-padded to a whole byte. */
  private def evenBodied(hex: String): String =
    val body = if hex.startsWith("0x") then hex.substring(2) else hex
    if body.length % 2 == 0 then "0x" + body else "0x0" + body

  private def quantity(hex: String): UInt64 = UInt64.fromHex(evenBodied(hex)).toOption.get

  private def wideQuantity(hex: String): UInt256 = UInt256.fromHex(evenBodied(hex)).toOption.get

  /** `tests/berlin/eip2929_gas_cost_increases/test_call.py::test_call_insufficient_balance`
    * at `fork_Paris`, an `ExecutionPayloadV1` offered to `engine_newPayloadV1`.
    *
    * Its randomness is thirty-two zero bytes, which is a legal value and is the
    * one a fixture generator has no reason to vary. That is worth noticing
    * rather than passing over: a zero randomness is exactly the value the
    * machine refuses to invent for itself, and a translation that dropped the
    * field entirely would still produce this block's hash. The Shanghai vector
    * has the same property, so **neither of these two proves the randomness is
    * carried** — the header-level assertion that does is in the spec beside
    * them.
    */
  val paris: ExecutionPayload = ExecutionPayload(
    parentHash = hash("0x3cbcfa1f88d769ab2ca90f49f5e20a64dbc2763ae7150cff0d96c6c25b513c28"),
    feeRecipient = address("0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba"),
    stateRoot = hash("0xe0004e8a3179716a51b4014140e54ed1270620dcf2ab207840a669dc84df7b83"),
    receiptsRoot = hash("0x123bdabf9f0af556d1aea4d284beab3570b4f30d2eaf751ebc68700322a95d00"),
    logsBloom = Bloom.Empty,
    prevRandao = hash("0x0000000000000000000000000000000000000000000000000000000000000000"),
    blockNumber = quantity("0x1"),
    gasLimit = quantity("0x7270e00"),
    gasUsed = quantity("0xd5ee"),
    timestamp = quantity("0x3e8"),
    extraData = data("0x00"),
    baseFeePerGas = wideQuantity("0x7"),
    blockHash = hash("0x99ca946930cd7211940b27d0898c8857f83a5c972a585309b5c7ca05a21157b6"),
    transactions = Seq(
      data(
        "0xf861800a8407270e00945cacb0cd1bea69d7453cab8dfe870a90f5fff30b808025a0d80f21c7c1f860ff80b3ad53" +
          "06ca0aef738658ec1467da66436f3c69a881fa4aa02ecc62e0418b0ad1521a9de4ff3cc8bc2a892ab70bd87aa9d2d8dfa45071d0c4"
      )
    )
  )

  /** `tests/shanghai/eip4895_withdrawals/test_withdrawals.py::test_balance_within_block`
    * at `fork_Shanghai`, an `ExecutionPayloadV2` offered to
    * `engine_newPayloadV2`.
    *
    * One withdrawal, so the withdrawals root it commits to is a real one-entry
    * trie rather than the empty root — which is what makes this vector prove
    * the second derived commitment rather than only the first.
    *
    * Its `extraData` is empty where the Paris vector's is one zero byte, and
    * the two encode differently. That pair is worth having: an encoder that
    * treated the two alike would reproduce one hash and not the other.
    */
  val shanghai: ExecutionPayload = ExecutionPayload(
    parentHash = hash("0xfec37f03b1cb60307b04e4eddb6b3eef93120a9e289efdc86b0669ae4c7d0dc2"),
    feeRecipient = address("0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba"),
    stateRoot = hash("0x2fc9534ae100e4979277d2d8e52d2c92bd025e787f77822e804560e94c22a75d"),
    receiptsRoot = hash("0xedd51b898476d717efe499381596337599116f4fc38d97ae73732bbdea9962f6"),
    logsBloom = Bloom.Empty,
    prevRandao = hash("0x0000000000000000000000000000000000000000000000000000000000000000"),
    blockNumber = quantity("0x1"),
    gasLimit = quantity("0x7270e00"),
    gasUsed = quantity("0xb3fc"),
    timestamp = quantity("0xc"),
    extraData = data("0x"),
    baseFeePerGas = wideQuantity("0x7"),
    blockHash = hash("0xc9bf538fc2224a8a3ca44b1aef06d620cc1168476753d8c22566eb40a339ac55"),
    transactions = Seq(
      data(
        "0xf881800a8407270e009451a5edfae3e6e028f277ae4f3f72a1411a7eaaae80a00000000000000000000000006047" +
          "8971839c84963db1986c096a4f416ff31f4226a0e4ad022c6e9afa9f0172c74455a2ae7566e46440c48718ce26f1391eb1422d50a0" +
          "668a26a6611407e0a18f3e9b290e3d7a522870c2b781f90ea6ffde1d340dc986"
      )
    ),
    appended = Some(
      PayloadWithdrawals(
        Seq(
          Withdrawal(
            index = quantity("0x0"),
            validatorIndex = quantity("0x0"),
            address = address("0x60478971839c84963db1986c096a4f416ff31f42"),
            amount = quantity("0x1")
          )
        )
      )
    )
  )
