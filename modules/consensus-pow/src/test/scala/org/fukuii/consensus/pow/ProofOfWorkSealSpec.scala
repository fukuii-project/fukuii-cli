package org.fukuii.consensus.pow

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, Hash, UInt256, UInt64}
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{BlockHeader, BlockNonce, Seal}

/** The seal rule as the engine exposes it: what the digest is taken over, and
  * every way a header can be refused.
  *
  * ==Each refusal is reached by breaking exactly one thing about a header that
  * otherwise passes==
  *
  * A negative case built from scratch proves nothing -- almost any header fails
  * this rule, so a test asserting one does could pass while the rule refused
  * everything. Every case below starts from a header this engine accepts and
  * changes one field, so the fault named is the fault caused.
  *
  * ==What is NOT asserted here, and where it is==
  *
  * That the seal hash is the digest the ecosystem computes. Nothing in this
  * file could establish it: re-deriving the preimage from the same encoder
  * would compare the rule against itself. It is settled in
  * `certification.EthashCorpus` against the published `header_hash`, which is
  * an outside statement of the same value.
  */
class ProofOfWorkSealSpec extends AnyFlatSpec:

  private val engine: ProofOfWorkEngine = ProofOfWorkEngine()

  private val cache: EthashCache = EthashFixtures.firstEpochCache

  private val nonce: BlockNonce = EthashFixtures.nonceOf("0102030405060708")

  private val otherNonce: BlockNonce = EthashFixtures.nonceOf("ffffffffffffffff")

  /** A difficulty every result clears, so the mixed-hash half is what is under
    * test until a case says otherwise. See [[EthashFixtures.sealedHeader]].
    */
  private val accepted: BlockHeader = EthashFixtures.sealedHeader(engine, cache, 1L, nonce, BigInt(1))

  private val acceptedMix: Hash = accepted.seal match
    case Seal.MixHashAndNonce(mixHash, _) => mixHash
    case Seal.AuthorityRound(_, _)        => EvmFixtures.hash(0)

  private def wordOf(value: BigInt): UInt256 =
    UInt256.fromBigInt(value).getOrElse(throw new IllegalStateException("a value no header field can carry"))

  /** A difficulty no thirty-two-byte result can clear.
    *
    * Two to the 255th, whose target is two, so a result clears only by being at
    * most two -- a one in two-to-the-255 event rather than a bound this suite
    * could reach by accident.
    */
  private val impossible: BigInt = BigInt(1) << 255

  /** A header sealed AT the impossible difficulty rather than one whose stated
    * difficulty was raised afterwards.
    *
    * **Raising it afterwards does not reach this fault**, because the
    * difficulty is inside the seal preimage: the digest moves, the nonce no
    * longer produces the mixed hash the header carries, and the rule refuses it
    * for the wrong reason one check earlier. Sealing at the difficulty is what
    * leaves the mixed hash correct and the work insufficient, which is the only
    * state that reaches the second check.
    */
  private val overclaimed: BlockHeader =
    EthashFixtures.sealedHeader(engine, cache, 1L, nonce, impossible)

  private val tampered: BlockHeader =
    accepted.copy(seal = Seal.MixHashAndNonce(EvmFixtures.hash(9), nonce))

  private val authorityRound: BlockHeader =
    EthashFixtures.headerAt(1L, BigInt(1), Seal.AuthorityRound(UInt64.Zero, Bytes.Empty))

  /** Whether the engine actually CONSULTS its ECIP-1099 parameter, which every
    * case above is blind to.
    *
    * ==Measured, not assumed: without these the parameter could be ignored
    * entirely and nothing would fail==
    *
    * Every other case in this file, and both published cases in the certified
    * tier, run on an engine with no activation set -- so the parameter and the
    * absent one answer identically and an engine that never read it would pass
    * all of them. Replacing `epochOf`'s use of the parameter with a constant
    * `None` was seeded and caught by nothing until these cases existed.
    *
    * The activation height below is arbitrary and small, chosen so the epochs
    * either side of it are cheap to name. A network's real height is the chain
    * specification's -- see [[ProofOfWorkEngine]] on why none is defaulted.
    */
  private val classicish: ProofOfWorkEngine = ProofOfWorkEngine(ecip1099Activation = Some(BigInt(60000)))

  /** A cache of one row, tagged with an epoch.
    *
    * The contents never matter: every case below is refused, or not, on the
    * epoch alone, which [[ProofOfWorkEngine.verifySeal]] compares before it
    * evaluates anything. Building a real cache for a post-activation epoch
    * would cost twenty megabytes to assert something about an integer.
    */
  private def tagged(epoch: BigInt): EthashCache =
    Ethash.cacheFrom(64L, EvmFixtures.hash(0), epoch)

  "the seal hash" should "not move when the mixed hash does" in
    assert(
      engine.sealHash(accepted) == engine.sealHash(tampered),
      "a nonce is sought against a digest that excludes the answer, or no search could terminate"
    )

  it should "not move when the nonce does" in
    assert(
      engine.sealHash(accepted) ==
        engine.sealHash(accepted.copy(seal = Seal.MixHashAndNonce(acceptedMix, otherNonce))),
      "the nonce is the thing being searched for, so it cannot appear in the preimage"
    )

  it should "move when a field outside the seal does" in
    assert(
      engine.sealHash(accepted) != engine.sealHash(accepted.copy(extraData = Bytes.fromIArray(IArray(1.toByte)))),
      "every field but the seal is committed to, or a miner's work would not bind the block it was done for"
    )

  it should "move when the difficulty does" in
    assert(
      engine.sealHash(accepted) != engine.sealHash(accepted.copy(difficulty = wordOf(impossible))),
      "the difficulty is inside the preimage, so a miner cannot restate the work its own nonce was done for"
    )

  it should "not be the header's own hash" in
    assert(
      engine.sealHash(accepted) != accepted.hash,
      "the block hash is taken over the whole header including the seal, and these are two different digests"
    )

  "a header this engine sealed" should "be accepted" in
    assert(
      engine.verifySeal(accepted, cache).isRight,
      "the mixed hash was taken from what this nonce actually produces and the difficulty is one"
    )

  it should "answer the mixed hash the header carries" in
    assert(
      engine.verifySeal(accepted, cache).map(_.mixHash) == Right(acceptedMix),
      "the accepted solution is the header's own, not a second evaluation of something else"
    )

  "a header carrying the authority-round seal" should "be refused as the wrong engine" in
    assert(
      engine.verifySeal(authorityRound, cache) == Left(SealFault.WrongEngine),
      "Seal records that widening the seal to a sum moved a refusal out of the decoder, and this is where it lands"
    )

  "a header stating no difficulty" should "be refused before a target is computed" in
    assert(
      engine.verifySeal(accepted.copy(difficulty = wordOf(BigInt(0))), cache) == Left(SealFault.NoDifficulty),
      "the division producing a target is what would fail otherwise, naming arithmetic rather than the header"
    )

  "a cache from another epoch" should "be refused as the caller's fault and not the block's" in
    assert(
      engine.verifySeal(accepted, cache.copy(epoch = BigInt(1))) == Left(SealFault.WrongEpoch(BigInt(0), BigInt(1))),
      "a cache from the wrong epoch answers a well-formed digest about nothing, which is not an invalid block"
    )

  "a header whose mixed hash was tampered with" should "be refused as a wrong mixed hash" in
    assert(
      engine.verifySeal(tampered, cache).swap.toOption.exists {
        case SealFault.WrongMixHash(claimed, answered) => claimed == EvmFixtures.hash(9) && answered == acceptedMix
        case _                                         => false
      },
      "the fault reports both, because a miner lying about its own work is a different finding from bad work"
    )

  "a header claiming more work than its nonce did" should "be refused as above the target" in
    assert(
      engine.verifySeal(overclaimed, cache).swap.toOption.exists {
        case SealFault.AboveTarget(_, difficulty) => difficulty == impossible
        case _                                    => false
      },
      "the mixed hash still matches, so the only thing left to refuse is the work, which is the second check"
    )

  "an engine running ECIP-1099" should "read the legacy epoch below the activation" in
    assert(
      classicish.epochOf(BigInt(59999)) == BigInt(1),
      "59999 is in the second legacy epoch, and the proposal does not apply below its own activation"
    )

  it should "halve the epoch at the activation" in
    assert(
      classicish.epochOf(BigInt(60000)) == BigInt(1) && engine.epochOf(BigInt(60000)) == BigInt(2),
      "the same height is epoch two without the proposal and epoch one with it, which is what shrinks the dataset"
    )

  it should "refuse the cache the legacy epoch would have called for" in
    assert(
      classicish.verifySeal(EthashFixtures.headerAt(60000L, BigInt(1), accepted.seal), tagged(BigInt(2))) ==
        Left(SealFault.WrongEpoch(BigInt(1), BigInt(2))),
      "an engine that ignored its own activation would accept this cache, and every other case here would still pass"
    )

  it should "accept the cache its own epoch calls for as far as the epoch check" in
    assert(
      classicish.verifySeal(EthashFixtures.headerAt(60000L, BigInt(1), accepted.seal), tagged(BigInt(1))) !=
        Left(SealFault.WrongEpoch(BigInt(1), BigInt(1))),
      "the negative case above is only evidence if the positive one gets past the same check"
    )
