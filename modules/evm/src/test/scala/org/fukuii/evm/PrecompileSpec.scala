package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** Each precompile's contract, stated as a behavior rather than as a vector.
  *
  * The sibling [[PrecompilePropSpec]] carries the corpora — go-ethereum's,
  * besu's and nethermind's published vectors — and everything here is a fact
  * about the shape of the answer that no vector in those corpora reaches: the
  * padding of a short input, the truncation of a long one, and what an input
  * the precompile cannot make sense of produces.
  *
  * Expected behavior is `ethereum/execution-specs` at `ccaaaba58`,
  * `frontier/vm/precompiled_contracts/`, read against `ethereum/go-ethereum` at
  * `6bb0588ad`, `core/vm/contracts.go`.
  *
  * ==What is deliberately NOT here==
  *
  * The bounds on `r` and `s` at the curve order. Those belong to
  * `Secp256k1.recoverPublicKey` and its own suite already pins them at the
  * boundary; what this file pins is that an out-of-range value produces an
  * empty ANSWER rather than a fault, which is the precompile's own contract and
  * not the curve's.
  */
class PrecompileSpec extends AnyFlatSpec:

  private val precompiles = PrecompileSet.baseline(GasSchedule.Baseline)

  private val ecRecover = precompiles.at(PrecompileSet.EcRecover).get
  private val sha256 = precompiles.at(PrecompileSet.Sha256).get
  private val ripemd160 = precompiles.at(PrecompileSet.Ripemd160).get
  private val identity = precompiles.at(PrecompileSet.Identity).get

  /** go-ethereum's `ValidKey` vector, taken apart so that one field at a time
    * can be moved. `ethereum/go-ethereum, 6bb0588ad`,
    * `core/vm/testdata/precompiles/ecRecover.json`.
    */
  private val messageHash = "18c547e4f7b0f325ad1e56f57e26c745b09a3e503d86e00e5255ff7f715d3d1c"

  private val validR = BigInt("73b1693892219d736caba55bdb67216e485557ea6b6af75f37096c9aa6a5a75f", 16)

  private val validS = BigInt("eeb940b1d03b21e36b0e47e79769f095fe2ab855bd91e3a38756b7d75a9c4549", 16)

  private val signer = "000000000000000000000000a94f5374fce5edbc8e2a8697c15331677e6ebf0b"

  /** Above the curve order by construction, and so out of range without this
    * file having to carry a curve constant of its own.
    */
  private val aboveEveryOrder = Word.MaxValue.toBigInt

  private def signature(v: BigInt, r: BigInt, s: BigInt): Bytes =
    EvmFixtures.bytesOf(messageHash + hex(v) + hex(r) + hex(s))

  private def hex(value: BigInt): String = Word(value).toBytes.toHex

  private def filling(size: Int): Bytes = Bytes.fromArray(new Array[Byte](size))

  // ── ecrecover: the assembled vector, then one field moved at a time ──────

  "ecrecover" should "recover the published signer from the assembled vector" in
    assert(
      ecRecover.run(signature(BigInt(28), validR, validS)).toHex == signer,
      "the assembly below is what every negative case moves one field of, so it has to be right first"
    )

  it should "answer nothing for a recovery identifier one below the pair it admits" in
    assert(ecRecover.run(signature(BigInt(26), validR, validS)).isEmpty, "26 is not a value this fork admits")

  it should "answer nothing for a recovery identifier one above the pair it admits" in
    assert(
      ecRecover.run(signature(BigInt(29), validR, validS)).isEmpty,
      "29 maps onto a recovery identifier the curve has and this fork does not admit, so only this check rejects it"
    )

  it should "answer nothing for a zero r" in
    assert(ecRecover.run(signature(BigInt(28), BigInt(0), validS)).isEmpty, "r must be at least one")

  it should "answer nothing for a zero s" in
    assert(ecRecover.run(signature(BigInt(28), validR, BigInt(0))).isEmpty, "s must be at least one")

  it should "answer nothing for an r above the curve order" in
    assert(
      ecRecover.run(signature(BigInt(28), aboveEveryOrder, validS)).isEmpty,
      "an out-of-range r answers, not faults"
    )

  it should "answer nothing for an s above the curve order" in
    assert(
      ecRecover.run(signature(BigInt(28), validR, aboveEveryOrder)).isEmpty,
      "an out-of-range s answers, not faults"
    )

  it should "answer nothing for an empty input" in
    assert(
      ecRecover.run(Bytes.Empty).isEmpty,
      "a short input is read as one whose remaining fields are zero, and a zero v is not admitted"
    )

  it should "ignore everything past the signature it reads" in
    assert(
      ecRecover.run(Bytes.fromArray(signature(BigInt(28), validR, validS).toArray ++ new Array[Byte](64))).toHex ==
        signer,
      "the input it reads is a fixed width, so trailing bytes change nothing"
    )

  it should "charge the same for an input it cannot make sense of as for one it can" in
    assert(
      ecRecover.gasFor(Bytes.Empty) == ecRecover.gasFor(signature(BigInt(28), validR, validS)),
      "the charge is settled before the input is looked at"
    )

  // ── The digests and the copy ─────────────────────────────────────────────

  "sha256" should "answer a whole word" in
    assert(sha256.run(filling(7)).length == Word.Width, "the digest is already a word wide and is not padded")

  "ripemd160" should "answer a whole word, not the width of its digest" in
    assert(ripemd160.run(filling(7)).length == Word.Width, "the 20-byte digest sits in the low end of a word")

  it should "leave the bytes above its digest zero" in
    assert(
      ripemd160.run(filling(7)).toHex.take((Word.Width - org.fukuii.crypto.Ripemd160.Width) * 2) == "0" * 24,
      "the padding is on the left"
    )

  "identity" should "answer nothing for an empty input" in
    assert(identity.run(Bytes.Empty).isEmpty, "a copy of nothing is nothing")

  it should "charge only its base for an empty input" in
    assert(identity.gasFor(Bytes.Empty) == GasSchedule.Baseline.precompileIdentityBase, "no bytes is no words")
