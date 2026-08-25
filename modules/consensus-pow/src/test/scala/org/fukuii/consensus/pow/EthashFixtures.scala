package org.fukuii.consensus.pow

import org.fukuii.bytes.{Bytes, Hash, UInt256, UInt64}
import org.fukuii.chainspec.ConsensusRules
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{BlockHeader, BlockNonce, Bloom, Seal}

/** What more than one spec in this module needs and none of them should build
  * twice.
  *
  * ==The first epoch's cache is generated once for the whole module==
  *
  * It is sixteen megabytes and about a million 512-bit digests, and every spec
  * that touches a real header needs the same one. A `lazy val` here is what
  * makes the module's cost one cache rather than one per spec, which is the
  * caller-side half of [[Ethash]]'s reason for taking a cache as a parameter.
  */
object EthashFixtures:

  /** The cache the first epoch's headers are checked against. */
  lazy val firstEpochCache: EthashCache = Ethash.cacheFor(BigInt(0), Ethash.EpochLength)

  /** A header whose seal is one this engine would accept.
    *
    * ==Sealed by computing the answer rather than by searching for it==
    *
    * A miner tries nonces until one clears a target. A test cannot, so the
    * difficulty is set to something the first nonce already clears and the
    * mixed hash is filled in from what that nonce actually produces. **The
    * header is therefore genuinely well-sealed** -- every step the rule runs is
    * the step it would run against a mined block -- and only the search is
    * skipped.
    *
    * This works because [[EthashEngine.sealHash]] excludes the seal, so
    * the digest a nonce is sought against does not move when the mixed hash is
    * written back into the header. A test that had to re-derive it afterwards
    * would be asserting that property rather than using it.
    */
  def sealedHeader(
      engine: EthashEngine,
      rules: ConsensusRules,
      cache: EthashCache,
      number: Long,
      nonce: BlockNonce,
      difficulty: BigInt
  ): BlockHeader =
    val unsealed = headerAt(number, difficulty, Seal.MixHashAndNonce(EvmFixtures.hash(0), nonce))
    val solution = Ethash.evaluateLight(
      cache,
      Ethash.datasetSize(engine.epochOf(rules, BigInt(number))),
      engine.sealHash(unsealed),
      nonce.toBytes
    )
    unsealed.copy(seal = Seal.MixHashAndNonce(solution.mixHash, nonce))

  /** A header carrying whatever seal it is handed.
    *
    * Every field the seal rule does not read is left at its zero rather than
    * invented, for the reason `DifficultyCorpus` gives: a plausible value in a
    * field nothing reads suggests something stated one.
    */
  def headerAt(number: Long, difficulty: BigInt, seal: Seal): BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = EvmFixtures.hash(0),
      beneficiary = EvmFixtures.address(0),
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = UInt256
        .fromBigInt(difficulty)
        .getOrElse(throw new IllegalStateException("a difficulty wider than a header can carry")),
      number = UInt64
        .fromBigInt(BigInt(number))
        .getOrElse(throw new IllegalStateException("a block number a header cannot carry")),
      gasLimit = UInt64.Zero,
      gasUsed = UInt64.Zero,
      timestamp = UInt64.Zero,
      extraData = Bytes.Empty,
      seal = seal
    )

  /** A nonce from eight bytes of hex. */
  def nonceOf(hex: String): BlockNonce =
    BlockNonce.fromHex(hex).getOrElse(throw new IllegalStateException("not an eight-byte nonce: " + hex))

  /** A hash from thirty-two bytes of hex. */
  def hashOf(hex: String): Hash =
    Hash.fromHex(hex).getOrElse(throw new IllegalStateException("not a thirty-two-byte hash: " + hex))
