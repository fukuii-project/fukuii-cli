package org.fukuii.evm

import java.nio.charset.StandardCharsets.UTF_8
import org.fukuii.bytes.Bytes
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** The precompiles against other implementations' published vectors.
  *
  * The sibling [[PrecompileSpec]] covers each one's contract stated as a
  * behavior — what it charges, what it pads, what it does with an input that
  * makes no sense. This file is the corpora, and its value is that almost none
  * of the values in it were derived here.
  *
  * **Almost, because one table is derived and says so.** No published corpus
  * reaches every combination of the three lengths modular exponentiation's
  * input declares, and the combinations are where its two settled answers live,
  * so [[lengthCombinations]] is worked out from the specification instead.
  * Every row of it that a published case does reach is cross-checked against
  * that case in the same table, which is what keeps the derivation from being
  * the only thing that says it is right.
  *
  * ==Where each table comes from==
  *
  *   - `ecrecover`, first five rows: go-ethereum
  *     `core/vm/testdata/precompiles/ecRecover.json` @ `ethereum/go-ethereum,
  *     6bb0588ad`, names and all.
  *   - `ecrecover`, remaining rows: besu
  *     `evm/src/test/java/org/hyperledger/besu/evm/precompile/ECRECPrecompiledContractTest.java`
  *     @ `besu-eth/besu, c2addd9424`, whose expectation is a bare address that
  *     its assertion widens into a word — recorded widened here, since that is
  *     what the precompile answers. No row is shared between the two, so they
  *     are two corpora rather than one copied twice.
  *   - `ripemd160`: nethermind
  *     `src/Nethermind/Nethermind.Core.Test/RipemdTests.cs` @
  *     `NethermindEth/nethermind, c35ce1b1ab`, already in the padded form the
  *     precompile answers with. Each was recomputed with `openssl
  *     dgst -ripemd160` and agreed.
  *   - `sha256`: computed on this machine by `sha256sum` (GNU coreutils), and
  *     cross-checked in process against the JDK's provider by
  *     [[org.fukuii.crypto.Sha256PropSpec]].
  *   - `modexp`: the `modexp-vectors.txt` resource, written by
  *     `scripts/gen-modexp-vectors.py` from two corpora it names. Its own
  *     header records that geth's and nethermind's published files are
  *     IDENTICAL — same names, inputs, outputs and gas, in the same order — so
  *     they are one corpus read once rather than two agreeing, and that four of
  *     besu's five extra rows state besu's machine-integer ceiling rather than
  *     the specification's figure and are left out for that reason.
  *
  * ==The three `InvalidHighV` rows are the ones that earn their place==
  *
  * Each carries a `v` whose LOW BYTE is 27 or 28 and whose word is not, and the
  * third differs from a valid `v` by one bit in a byte nothing reads by
  * accident. An implementation taking `v` from the low byte alone passes every
  * other row here and fails these.
  */
class PrecompilePropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val precompiles = EvmFixtures.precompiles

  private def of(address: org.fukuii.bytes.Address): Precompile = precompiles.at(address).get

  private def bytes(hex: String): Bytes = EvmFixtures.bytesOf(hex)

  private def ascii(s: String): Bytes = Bytes.fromArray(s.getBytes(UTF_8))

  private def filling(size: Int): Bytes = Bytes.fromArray(new Array[Byte](size))

  private val recoveries = Table(
    ("name", "input", "expectedHex"),
    (
      "geth CallEcrecoverUnrecoverableKey",
      "a8b53bdf3306a35a7103ab5504a0c9b492295564b6202b1942a84ef300107281" +
        "000000000000000000000000000000000000000000000000000000000000001b" +
        "307835653165303366353363653138623737326363623030393366663731663366" +
        "3533663563373562373464636233316138356161386238383932623465386211" +
        "22334455667788991011121314151617181920212223242526272829303132",
      ""
    ),
    (
      "geth ValidKey",
      "18c547e4f7b0f325ad1e56f57e26c745b09a3e503d86e00e5255ff7f715d3d1c" +
        "000000000000000000000000000000000000000000000000000000000000001c" +
        "73b1693892219d736caba55bdb67216e485557ea6b6af75f37096c9aa6a5a75f" +
        "eeb940b1d03b21e36b0e47e79769f095fe2ab855bd91e3a38756b7d75a9c4549",
      "000000000000000000000000a94f5374fce5edbc8e2a8697c15331677e6ebf0b"
    ),
    (
      "geth InvalidHighV-bits-1",
      "18c547e4f7b0f325ad1e56f57e26c745b09a3e503d86e00e5255ff7f715d3d1c" +
        "100000000000000000000000000000000000000000000000000000000000001c" +
        "73b1693892219d736caba55bdb67216e485557ea6b6af75f37096c9aa6a5a75f" +
        "eeb940b1d03b21e36b0e47e79769f095fe2ab855bd91e3a38756b7d75a9c4549",
      ""
    ),
    (
      "geth InvalidHighV-bits-2",
      "18c547e4f7b0f325ad1e56f57e26c745b09a3e503d86e00e5255ff7f715d3d1c" +
        "000000000000000000000000000000000000001000000000000000000000001c" +
        "73b1693892219d736caba55bdb67216e485557ea6b6af75f37096c9aa6a5a75f" +
        "eeb940b1d03b21e36b0e47e79769f095fe2ab855bd91e3a38756b7d75a9c4549",
      ""
    ),
    (
      "geth InvalidHighV-bits-3",
      "18c547e4f7b0f325ad1e56f57e26c745b09a3e503d86e00e5255ff7f715d3d1c" +
        "000000000000000000000000000000000000001000000000000000000000011c" +
        "73b1693892219d736caba55bdb67216e485557ea6b6af75f37096c9aa6a5a75f" +
        "eeb940b1d03b21e36b0e47e79769f095fe2ab855bd91e3a38756b7d75a9c4549",
      ""
    ),
    (
      "besu unrecoverable",
      "acb1c19ac0832320815b5e886c6b73ad7d6177853d44b026f2a7a9e11bb899fc" +
        "000000000000000000000000000000000000000000000000000000000000001c" +
        "89ea49159b334f9aebbf54481b69d000d285baa341899db355a4030f6838394e" +
        "540e9f9fa17bef441e32d98d5f4554cfefdc6a56101352e4b92efafd0d9646e8",
      ""
    ),
    (
      "besu 1",
      "0049872459827432342344987245982743234234498724598274323423429943" +
        "000000000000000000000000000000000000000000000000000000000000001b" +
        "e8359c341771db7f9ea3a662a1741d27775ce277961470028e054ed3285aab8e" +
        "31f63eaac35c4e6178abbc2a1073040ac9bbb0b67f2bc89a2e9593ba9abe8c53",
      "0000000000000000000000000c65a9d9ffc02c7c99e36e32ce0f950c7804ceda"
    ),
    (
      "besu 2",
      "82f3df49d3645876de6313df2bbe9fbce593f21341a7b03acdb9423bc171fcc9" +
        "000000000000000000000000000000000000000000000000000000000000001c" +
        "ba13918f50da910f2d55a7ea64cf716ba31dad91856f45908dde900530377d8a" +
        "112d60f36900d18eb8f9d3b4f85a697b545085614509e3520e4b762e35d0d6bd",
      "000000000000000000000000c6e93f4c1920eaeaa1e699f76a7a8c18e3056074"
    ),
    (
      "besu 3",
      "0fcdd8f8c550589cbae6183bc40713beb8d11898a201d13d6d5e40bc9ebf221d" +
        "000000000000000000000000000000000000000000000000000000000000001c" +
        "3824317158d005cbe49614fa05798ea00f2ca9db302a5e92d55bcaecd33d33da" +
        "3c7de48ebec95be7b5111a7812febed1421f839d4d480c98501b78666aefdcd3",
      "000000000000000000000000fe26206ad0a5897a478dd046c56164553adaea20"
    ),
    (
      "besu 4",
      "eb9a6731fa269c24c2535aa00a4b31d7117a2791188120c71aacd97664d1cc16" +
        "000000000000000000000000000000000000000000000000000000000000001c" +
        "707c68dd904de055d735f9e5c4dfba46296ad38ff6ba8f0e5e3f4ae83243b84a" +
        "5b1920d628abc74e4f3a09449011a9664ab9885f74fdc7628d417599207bde74",
      "00000000000000000000000039c0f4fbcd41581d5b440a7c9f964b903037e09e"
    ),
    (
      "besu 5",
      "f3ae1d9176371dd31accd73bb6bbaee561a041f5ac291a548880e1abe7b19e38" +
        "000000000000000000000000000000000000000000000000000000000000001b" +
        "116bd86d971b70ed540dc7c13756a99ff17644ed433781a3ffcc7359541d02fc" +
        "4a2358ffc21682ece870e633cc8537f04be4ff75a142ecd35a951fc95fd1de57",
      "00000000000000000000000064849cfbf0353f2c80e2a1e558982f7c4738d9f1"
    )
  )

  private val digests = Table(
    ("input", "sha256Hex", "ripemd160Hex"),
    (
      "",
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "0000000000000000000000009c1185a5c5e9fc54612808977ee8f548b2258d31"
    ),
    (
      "abc",
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      "0000000000000000000000008eb208f7e05d987a9b044a8e98c6b087f15a0bfc"
    ),
    (
      "message digest",
      "f7846f55cf23e14eebeab5b4e1550cad5b509e3348fbc4efa3a1413d393cb650",
      "0000000000000000000000005d0689ef49d2fae572b881b123a85ffa21595f36"
    ),
    (
      "abcdefghijklmnopqrstuvwxyz",
      "71c480df93d6ae2f1efad1447c66c9525e316218cf51fc8d9ed832f2daf18b73",
      "000000000000000000000000f71c27109c692c1b56bbdceb5b9d2865b3708dbc"
    )
  )

  /** The three per-word prices, checked against a partial word rather than a
    * whole one — an implementation dividing without rounding up prices a
    * three-byte input at zero words and passes every whole-word case.
    */
  /** How many whole words an input of this many bytes is charged for.
    *
    * The word count is the rule under test; what a word costs is a network's and
    * is read from the schedule beside it. Written as precomputed prices this
    * table certified one network's figures instead, and could not fail for a
    * machine that ignored its schedule.
    */
  private val perWordPrices = Table(
    ("bytes", "words"),
    (0, 0),
    (1, 1),
    (31, 1),
    (32, 1),
    (33, 2),
    (64, 2),
    (65, 3)
  )

  private val schedule = EvmFixtures.schedule

  // ── Modular exponentiation ───────────────────────────────────────────────

  /** Priced at this fork's own divisor, which is what every gas figure in the
    * corpus was generated against.
    */
  private val modExp = Precompile.ModExp(BigInt(20), BigInt(0), Precompile.ModExpComplexity.Piecewise)

  /** One published case: what it charges and what it answers, either of which
    * the corpus that supplied it may leave unstated.
    */
  private case class ModExpVector(input: Bytes, gas: Option[BigInt], answer: Option[Bytes], name: String)

  private val modExpCorpus: Seq[ModExpVector] =
    val stream = Option(getClass.getResourceAsStream("/modexp-vectors.txt"))
    val source = stream.map(scala.io.Source.fromInputStream(_))
    try
      source.toSeq.flatMap(_.getLines()).filterNot(line => line.isEmpty || line.startsWith("#")).map { line =>
        val parts = line.split(' ')
        ModExpVector(
          bytes(parts(0)),
          Option.when(parts(1) != "-")(BigInt(parts(1))),
          Option.when(parts(2) != "-")(bytes(parts(2).drop(2))),
          parts(3)
        )
      }
    finally source.foreach(_.close())

  /** The eight ways the three declared lengths can each be empty or not, with
    * the answer the specification gives for each.
    *
    * Four of the eight are reached by a published case and carry its
    * identifier; the other four are not published anywhere and are worked out
    * from `ethereum/execution-specs` @ `20f7f6271a`,
    * `forks/byzantium/vm/precompiled_contracts/modexp.py`.
    *
    * **Three rows answer nothing for the same rule and one for a different
    * one**, which is why the combinations are worth enumerating rather than
    * sampling. An empty base with an empty modulus is answered before any
    * operand is read, whatever the exponent says; an empty modulus with a base
    * that is not empty is not that case at all, and answers nothing only
    * because a modulus read out of no bytes is zero and an answer is as wide as
    * the modulus was declared.
    */
  private val lengthCombinations = Table(
    ("baseLength", "exponentLength", "modulusLength", "operands", "answer", "published as"),
    (0, 0, 0, "", "", "eest/base_0x-exponent_0x-modulus_0x"),
    (0, 0, 1, "02", "01", "eest/base_0x-exponent_0x-modulus_0x02"),
    (0, 1, 0, "03", "", "derived: both empty, answered before the exponent is read"),
    (0, 1, 1, "0305", "00", "eest/base_0x-exponent_0x01-modulus_0x02 has this shape"),
    (1, 0, 0, "07", "", "derived: a modulus of no bytes is zero, and zero bytes wide"),
    (1, 0, 1, "0705", "01", "derived: anything to the power of nothing is one"),
    (1, 1, 0, "0703", "", "derived: a modulus of no bytes is zero, and zero bytes wide"),
    (1, 1, 1, "070305", "03", "derived: 7**3 mod 5")
  )

  private def declared(length: Int): String = Word(BigInt(length)).toBytes.toHex

  property("ecrecover answers what go-ethereum and besu publish") {
    forAll(recoveries) { (name: String, input: String, expectedHex: String) =>
      assert(of(PrecompileSet.EcRecover).run(bytes(input)).map(_.toHex) == Right(expectedHex), name)
    }
  }

  property("ecrecover charges the same for every one of them") {
    forAll(recoveries) { (name: String, input: String, _: String) =>
      assert(
        of(PrecompileSet.EcRecover).gasFor(bytes(input)) == EvmFixtures.schedule.precompileEcRecover,
        "flat price for " + name
      )
    }
  }

  property("sha256 answers the digest, unpadded") {
    forAll(digests) { (input: String, sha256Hex: String, _: String) =>
      assert(of(PrecompileSet.Sha256).run(ascii(input)).map(_.toHex) == Right(sha256Hex), "sha256 of " + input)
    }
  }

  property("ripemd160 answers the digest in the low end of a word") {
    forAll(digests) { (input: String, _: String, ripemd160Hex: String) =>
      assert(of(PrecompileSet.Ripemd160).run(ascii(input)).map(_.toHex) == Right(ripemd160Hex), "ripemd160 of " + input)
    }
  }

  property("identity answers exactly what it was given") {
    forAll(digests) { (input: String, _: String, _: String) =>
      assert(of(PrecompileSet.Identity).run(ascii(input)) == Right(ascii(input)), "identity of " + input)
    }
  }

  property("sha256 charges its base plus one per started word") {
    forAll(perWordPrices) { (size: Int, words: Int) =>
      assert(
        of(PrecompileSet.Sha256).gasFor(filling(size)) ==
          schedule.precompileSha256Base + schedule.precompileSha256PerWord * words,
        "sha256 over " + size + " bytes"
      )
    }
  }

  property("ripemd160 charges its base plus one per started word") {
    forAll(perWordPrices) { (size: Int, words: Int) =>
      assert(
        of(PrecompileSet.Ripemd160).gasFor(filling(size)) ==
          schedule.precompileRipemd160Base + schedule.precompileRipemd160PerWord * words,
        "ripemd160 over " + size + " bytes"
      )
    }
  }

  property("identity charges its base plus one per started word") {
    forAll(perWordPrices) { (size: Int, words: Int) =>
      assert(
        of(PrecompileSet.Identity).gasFor(filling(size)) ==
          schedule.precompileIdentityBase + schedule.precompileIdentityPerWord * words,
        "identity over " + size + " bytes"
      )
    }
  }

  property("the modexp corpus loaded, with both halves of it substantial") {
    // `forAll` over an empty table SUCCEEDS, so a resource that failed to load
    // would leave the three properties below checking nothing and reporting
    // green. The counts are not asserted exactly -- the resource is
    // regenerable and an exact figure would be a maintained value -- but a
    // corpus that stopped stating gas, or stopped stating answers, has to show
    // up as a failure rather than as a smaller pass.
    val priced = modExpCorpus.count(_.gas.isDefined)
    val answered = modExpCorpus.count(_.answer.isDefined)
    assert(
      priced > 10 && answered > 10,
      "expected a substantial corpus of both kinds; got priced=" + priced.toString +
        " answered=" + answered.toString
    )
  }

  property("modexp charges what the corpus states") {
    val priced = Table("vector", modExpCorpus.filter(_.gas.isDefined)*)
    forAll(priced) { (vector: ModExpVector) =>
      assert(modExp.gasFor(vector.input) == vector.gas.get, vector.name)
    }
  }

  property("modexp answers what the corpus states") {
    val answered = Table("vector", modExpCorpus.filter(_.answer.isDefined)*)
    forAll(answered) { (vector: ModExpVector) =>
      assert(modExp.run(vector.input) == Right(vector.answer.get), vector.name)
    }
  }

  property("modexp answers each combination of empty and non-empty declared lengths") {
    forAll(lengthCombinations) {
      (
          baseLength: Int,
          exponentLength: Int,
          modulusLength: Int,
          operands: String,
          answer: String,
          source: String
      ) =>
        val input =
          bytes(declared(baseLength) + declared(exponentLength) + declared(modulusLength) + operands)
        assert(modExp.run(input).map(_.toHex) == Right(answer), source)
    }
  }
