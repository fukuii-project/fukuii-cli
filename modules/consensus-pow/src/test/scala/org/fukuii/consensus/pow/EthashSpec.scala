package org.fukuii.consensus.pow

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.Hash
import org.fukuii.crypto.Keccak256

/** The epoch arithmetic every later step is indexed by, and the one thing
  * ECIP-1099 changes about it.
  *
  * ==Why these are hand-written and not certification==
  *
  * The published `PoWTests` tier is two cases, both at epoch zero, so it cannot
  * see the seed chain iterate, the size search move, or any epoch boundary --
  * and ECIP-1099 postdates it entirely, so no published case exercises the
  * proposal at all. Every value below is read from the specification or from a
  * client at a stated ref and named where it is used.
  */
class EthashSpec extends AnyFlatSpec:

  /** Mordor's activation, from ECIP-1099's own parameter list.
    *
    * The proposal states `ETCHASH_FORK_BLOCK := 2_520_000` for that network and
    * `ethereumclassic/core-geth` @ `4185df450` writes the same figure into
    * `TestCalcEpochLength` with the comment `// mordor`. It is used here as an
    * arbitrary activation to exercise the predicate, not as a network's
    * configuration -- see [[EthashEngine]] on why no default is carried.
    */
  private val MordorActivation: BigInt = BigInt(2520000)

  private val never: Option[BigInt] = None
  private val atMordor: Option[BigInt] = Some(MordorActivation)

  private val zeroSeed: Hash =
    Hash.fromBytesTruncating(IArray.fill(Hash.Width)(0.toByte))

  "epochAt" should "put the first thirty thousand blocks in epoch zero" in
    assert(
      Vector(BigInt(0), BigInt(29999)).forall(Ethash.epochAt(_, never) == BigInt(0)),
      "execution-specs @ ccaaaba58 parameterizes test_epoch with exactly 0 and 29999 answering 0"
    )

  it should "step at the first multiple of the epoch length" in
    assert(Ethash.epochAt(BigInt(30000), never) == BigInt(1), "30000 is the first block of epoch one")

  "epochLengthAt" should "answer the legacy length below an ECIP-1099 activation" in
    assert(
      Ethash.epochLengthAt(MordorActivation - 1, atMordor) == Ethash.EpochLength,
      "the proposal's own predicate is blockNum < activationBlock answering oldEpochLength"
    )

  it should "answer the new length at the activation height itself" in
    assert(
      Ethash.epochLengthAt(MordorActivation, atMordor) == Ethash.Ecip1099EpochLength,
      "the proposal answers newEpochLength from the activation block onward, not from the one after"
    )

  it should "answer the legacy length at every height on a network that never adopts it" in
    assert(
      Ethash.epochLengthAt(MordorActivation, never) == Ethash.EpochLength,
      "an absent activation is a network the proposal was never applied to"
    )

  "the ECIP-1099 epoch length" should "be twice the legacy one" in
    assert(
      Ethash.Ecip1099EpochLength == Ethash.EpochLength * 2,
      "the proposal states 60000 against 30000, and besu-etc @ eb4248c99 writes it as EPOCH_LENGTH * 2"
    )

  "the epoch number" should "halve at an ECIP-1099 activation" in
    assert(
      Ethash.epochAt(MordorActivation, atMordor) * 2 == Ethash.epochAt(MordorActivation, never),
      "the proposal states the epoch halving upon activation, which is what shrinks the dataset"
    )

  /** The property neither client explains and the proposal does. */
  "the seed under ECIP-1099" should "be the legacy seed of twice the epoch" in
    assert(
      Ethash.seedFor(BigInt(84), Ethash.Ecip1099EpochLength) == Ethash.seedFor(BigInt(168), Ethash.EpochLength),
      "ECIP-1099 keeps the legacy divisor in seedHash so seeds do not overlap, so epoch e seeds as legacy 2e"
    )

  it should "not be the legacy seed of the same epoch number" in
    assert(
      Ethash.seedFor(BigInt(84), Ethash.Ecip1099EpochLength) != Ethash.seedFor(BigInt(84), Ethash.EpochLength),
      "reusing one divisor throughout is the plausible wrong reading, and it produces a different seed"
    )

  "seedFor" should "be thirty-two zero bytes at the first epoch" in
    assert(
      Ethash.seedFor(BigInt(0), Ethash.EpochLength) == zeroSeed,
      "execution-specs @ ccaaaba58 asserts generate_seed(0) == b'\\x00' * 32"
    )

  it should "be one digest of that at the second" in
    assert(
      Ethash.seedFor(BigInt(1), Ethash.EpochLength) == Keccak256.hash(zeroSeed.toBytes),
      "execution-specs @ ccaaaba58 asserts generate_seed(EPOCH_SIZE) == keccak256(b'\\x00' * 32)"
    )

  "cacheSize" should "be the published figure for the first epoch" in
    assert(
      Ethash.cacheSize(BigInt(0)) == 16776896L,
      "execution-specs @ ccaaaba58 asserts cache_size(0) == 16776896, and openethereum @ v3.0.1 asserts the same"
    )

  "datasetSize" should "be the published figure for the first epoch" in
    assert(
      Ethash.datasetSize(BigInt(0)) == 1073739904L,
      "execution-specs @ ccaaaba58 asserts dataset_size(0) == 1073739904, as does openethereum @ v3.0.1"
    )

  it should "be the published figure one epoch on" in
    assert(
      Ethash.datasetSize(BigInt(1)) == 1082130304L,
      "openethereum @ v3.0.1 asserts get_data_size(ETHASH_EPOCH_LENGTH) == 1082130304"
    )

  "cacheSize" should "be the published figure one epoch on" in
    assert(
      Ethash.cacheSize(BigInt(1)) == 16907456L,
      "openethereum @ v3.0.1 asserts get_cache_size(ETHASH_EPOCH_LENGTH) == 16907456"
    )

  "clears" should "admit any result at a difficulty of one" in
    assert(
      Ethash.clears(Hash.fromBytesTruncating(IArray.fill(32)(0xff.toByte)), BigInt(1)),
      "the target at difficulty one is two to the 256th, above every thirty-two-byte value"
    )

  it should "refuse a difficulty of zero rather than divide by it" in
    assert(!Ethash.clears(zeroSeed, BigInt(0)), "both clients read for this rule refuse a zero difficulty first")

  it should "read a result with its high bit set as a large number and not a negative one" in
    assert(
      !Ethash.clears(Hash.fromBytesTruncating(IArray.fill(32)(0xff.toByte)), BigInt(2)),
      "a signed reading would make half of all digests clear every target"
    )

  /** The seed chain's own bound.
    *
    * The loop is one digest per legacy epoch and allocates nothing, so an epoch
    * far enough out is CPU with no memory ceiling to stop it. [[Ethash.cacheFor]]
    * sizes its cache before it asks for a seed, which refuses such an epoch one
    * argument earlier -- but that is argument evaluation order rather than a
    * property of the method, and this suite reaches it directly.
    */
  /** The bound stated at its own boundary, one epoch either side.
    *
    * **The two cases below are the ones that can be calibrated, and the extreme
    * cases after them are not.** Removing the bound leaves the extremes running
    * some ten trillion digests, so a run seeded that way does not fail -- it
    * does not return, which is the hazard rather than a test of it. One epoch
    * past the boundary is thirty-two thousand digests: fast enough that an
    * absent bound answers a seed instead of hanging, so the refusal is what the
    * case is measuring.
    */
  "seedFor" should "refuse an epoch one past the largest a cache could be grown for" in
    assertThrows[IllegalStateException](Ethash.seedFor(BigInt(16384), Ethash.Ecip1099EpochLength))

  it should "still answer the largest epoch a buildable cache does ask for" in
    assert(
      Ethash.seedFor(BigInt(16383), Ethash.Ecip1099EpochLength) ==
        Ethash.seedFor(BigInt(32766), Ethash.EpochLength),
      "a bound refusing the epoch one below the case above would refuse a cache the sizing itself admits"
    )

  it should "refuse an epoch past any cache that could be grown from its seed" in
    assertThrows[IllegalStateException](Ethash.seedFor(BigInt(2).pow(64), Ethash.EpochLength))

  it should "refuse a plausible epoch counted in an implausible length" in
    assertThrows[IllegalStateException](Ethash.seedFor(BigInt(1), BigInt(10).pow(18)))

  it should "refuse a length of nothing rather than answer the unstirred seed" in
    assertThrows[IllegalStateException](Ethash.seedFor(BigInt(84), BigInt(0)))

  it should "refuse an epoch below zero rather than answer the unstirred seed" in
    assertThrows[IllegalStateException](Ethash.seedFor(BigInt(-1), Ethash.EpochLength))

  /** The evaluation's size, which is narrowed to a machine integer and is not
    * the cache's to state.
    *
    * Each of the three ways it can be wrong fails differently and only one
    * announces itself, so all three are refused rather than the one that raises
    * on its own.
    */
  "evaluateLight" should "refuse a size of nothing rather than divide by a row count of zero" in
    assertThrows[IllegalStateException](
      Ethash.evaluateLight(EthashFixtures.firstEpochCache, 0L, zeroSeed, IArray.fill(8)(0.toByte))
    )

  it should "refuse a size that is not a whole number of rows" in
    assertThrows[IllegalStateException](
      Ethash.evaluateLight(EthashFixtures.firstEpochCache, 129L, zeroSeed, IArray.fill(8)(0.toByte))
    )

  it should "refuse a row count past what a machine integer holds" in
    assertThrows[IllegalStateException](
      Ethash.evaluateLight(
        EthashFixtures.firstEpochCache,
        (BigInt(2).pow(40) * 128).toLong,
        zeroSeed,
        IArray.fill(8)(0.toByte)
      )
    )
