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
  * Expected behavior for the four a chain can place from genesis is
  * `ethereum/execution-specs` at `ccaaaba58`,
  * `frontier/vm/precompiled_contracts/`, read against `ethereum/go-ethereum` at
  * `6bb0588ad`, `core/vm/contracts.go`. For modular exponentiation it is
  * `ethereum/execution-specs` at `20f7f6271a`,
  * `forks/byzantium/vm/precompiled_contracts/modexp.py`, read against
  * `ethereum/go-ethereum-pow` at `v1.10.26`, `core/vm/contracts.go`, and
  * against the proposal itself at `ethereum/EIPs` `9e393a79`,
  * `EIPS/eip-198.md`.
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

  private val precompiles = EvmFixtures.precompiles

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

  /** Priced as this fork prices it, so the figures below are the network's own
    * rather than the fixture schedule's.
    *
    * The fixture schedule holds a divisor no network uses, which is what the
    * last case here reads: an implementation dividing by a constant answers the
    * same for both and this file would not notice.
    */
  private val modExp = Precompile.ModExp(BigInt(20))

  /** The three declared lengths, as the input carries them: one word each, in
    * the order the proposal lays them out.
    */
  private def declaring(baseLength: BigInt, exponentLength: BigInt, modulusLength: BigInt): String =
    hex(baseLength) + hex(exponentLength) + hex(modulusLength)

  private def modExpInput(
      baseLength: BigInt,
      exponentLength: BigInt,
      modulusLength: BigInt,
      operands: String
  ): Bytes =
    EvmFixtures.bytesOf(declaring(baseLength, exponentLength, modulusLength) + operands)

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
    assert(identity.gasFor(Bytes.Empty) == EvmFixtures.schedule.precompileIdentityBase, "no bytes is no words")

  // ── Modular exponentiation ───────────────────────────────────────────────

  "modexp" should "answer nothing at all where the base and the modulus are both declared empty" in
    // The specification returns there before reading an operand, and this input
    // is why that order matters: the exponent is declared wider than any buffer
    // can hold, so an implementation reading the operands first would try to
    // build it. `ethereum/execution-specs` @ `20f7f6271a`, modexp.py:48-50.
    assert(
      modExp.run(modExpInput(0, BigInt(2).pow(200), 0, "ff")).isEmpty,
      "a pair of empty declared lengths is answered before anything is read"
    )

  it should "charge nothing for that input" in
    // The same fact from the pricing side, and the reason the return above is
    // reachable at all: the difficulty term is the square of the larger
    // declared length, which is zero, so the whole product is zero however long
    // the exponent claims to be.
    assert(
      modExp.gasFor(modExpInput(0, BigInt(2).pow(200), 0, "ff")) == BigInt(0),
      "zero difficulty prices the call at zero"
    )

  it should "answer nothing for an input of no bytes at all" in
    assert(modExp.run(Bytes.Empty).isEmpty, "three lengths read past the end are three zeroes")

  it should "answer at the modulus's declared width where the value is narrower" in
    // 7**2 mod 11 = 5 in a modulus declared four bytes wide.
    assert(
      modExp.run(modExpInput(1, 1, 4, "0702" + "0000000b")).toHex == "00000005",
      "the answer is as wide as the modulus was declared, not as wide as the value"
    )

  it should "answer in zeroes at that width where the modulus is zero" in
    // "if modulus == 0: evm.output = Bytes(b"\x00") * modulus_length",
    // modexp.py:60-61.
    assert(
      modExp.run(modExpInput(1, 1, 3, "0702" + "000000")).toHex == "000000",
      "a modulus of zero is answered in zeroes rather than refused"
    )

  it should "answer in zeroes at that width where the modulus is one" in
    // Everything is congruent to zero modulo one, including a base raised to
    // the power of nothing -- which is the arm an implementation shortcutting a
    // zero exponent to one would answer 0x000001 for.
    assert(
      modExp.run(modExpInput(1, 0, 3, "07" + "000001")).toHex == "000000",
      "a modulus of one leaves nothing behind, whatever the exponent is"
    )

  it should "read a modulus the data cuts short as one padded with zeroes" in
    // The proposal's fifth worked example: "it attempts to grab 32 bytes for
    // the modulus starting from 0x80 - but there is no further data, so it
    // right-pads it with 31 zero bytes". `ethereum/EIPs` @ `9e393a79`,
    // EIPS/eip-198.md.
    assert(
      modExp.run(modExpInput(1, 2, 32, "03" + "ffff" + "80")).toHex ==
        "3b01b01ac41f2d6e917c6d6a221ce793802469026d9ab7578fa2e79e4da6aaab",
      "a modulus shorter than its declared length is read as one padded on the right"
    )

  it should "answer the same where the data supplies those zeroes and one byte more" in
    // The proposal's fourth example is the fifth with the padding written out
    // and "the remaining 0x07 byte" after it, and it states the two parse
    // alike. Asserted as an equality between the two rather than as a repeated
    // figure, because what the document claims is that they agree.
    assert(
      modExp.run(modExpInput(1, 2, 32, "03" + "ffff" + "80" + "00" * 31 + "07")) ==
        modExp.run(modExpInput(1, 2, 32, "03" + "ffff" + "80")),
      "the padded form and the truncated form are the same call"
    )

  it should "charge the same for both of them" in
    assert(
      modExp.gasFor(modExpInput(1, 2, 32, "03" + "ffff" + "80" + "00" * 31 + "07")) ==
        modExp.gasFor(modExpInput(1, 2, 32, "03" + "ffff" + "80")),
      "bytes past the last operand reach neither the answer nor the price"
    )

  it should "charge under two hundred for a small call, and nothing at all for some" in
    // The floor of two hundred belongs to the later proposal that also moves
    // the divisor, and is not this fork's. Eleven published cases at this fork
    // cost zero -- `ethereum/execution-specs-fixtures` release `tests@v20.0.1`,
    // `state_tests/for_byzantium/byzantium/eip198_modexp_precompile` -- and a
    // floor here would refuse every one of them at the price they were
    // generated against.
    assert(modExp.gasFor(modExpInput(0, 0, 1, "02")) == BigInt(0), "the smallest calls this fork admits are free")

  it should "state a charge no 64-bit gas limit could meet, exactly rather than at a ceiling" in
    // A base declared 2**42 bytes wide. The difficulty term squares it, so the
    // product overruns every machine integer; the figure below is what the
    // specification's arbitrary-precision arithmetic gives.
    //
    // `besu-eth/besu` @ `fdf1247c6d` answers 28928590731427686 for this input,
    // which is what its `square()` gives once `clampedMultiply` has pinned the
    // product at `Long.MAX_VALUE`. Both refuse the call at any gas a
    // transaction can state, so the difference is not observable on a chain --
    // and a build that adopted the ceiling would be asserting a number the
    // specification does not have.
    assert(
      modExp.gasFor(modExpInput(BigInt(2).pow(42), 0, 0, "")) == BigInt("60446291086284574991820"),
      "the charge was narrowed or saturated somewhere"
    )

  it should "take only the exponent's leading word into the price" in {
    // Two calls whose exponents agree in their first word and differ past it.
    // The price counts eight for every byte past that word and the position of
    // the highest set bit within it, so it cannot see the difference -- while
    // the answer can, and does.
    val leading = "80" + "00" * 31
    val quiet = modExpInput(1, 40, 1, "02" + leading + "00" * 8 + "05")
    val loud = modExpInput(1, 40, 1, "02" + leading + "00" * 7 + "01" + "05")
    assert(
      modExp.gasFor(quiet) == modExp.gasFor(loud) && modExp.run(quiet) != modExp.run(loud),
      "the two differ in the price, or agree in the answer"
    )
  }

  it should "divide by the divisor it was built with rather than by a constant" in {
    // The one property no published vector can establish, because every corpus
    // was generated at this fork's own divisor. An entry reading a literal
    // answers the same for both.
    val input = modExpInput(1, 32, 32, "03" + "ff" * 32 + "ff" * 32)
    assert(
      Precompile.ModExp(BigInt(20)).gasFor(input) == Precompile.ModExp(BigInt(10)).gasFor(input) / 2,
      "the divisor is not the one the entry was built with"
    )
  }
