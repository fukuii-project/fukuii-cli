package org.fukuii.consensus.pow.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.Hash
import org.fukuii.consensus.pow.{Ethash, EthashCache, ProofOfWorkEngine}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.RlpCodec
import org.fukuii.types.{BlockHeader, Seal}

/** The published `PoWTests` corpus, run once and reported as counts.
  *
  * ==Every case is checked at eight points, because the tier is two cases==
  *
  * `DifficultyTests` is eighteen thousand cases stating one answer each, so a
  * count of divergences is a useful instrument on its own. This tier is **two**
  * cases, so a bare pass over both is close to no instrument at all. What makes
  * it worth running is that each case states the seed, both sizes and the seal
  * hash beside the answer -- so a single case exercises the epoch arithmetic,
  * the seed chain, both size searches, the header encoding minus its seal, the
  * cache construction and the mixing loop, and reports which of them moved.
  *
  * ==What this tier CANNOT see, stated here rather than left to be assumed==
  *
  * Both published cases sit at **epoch zero**, in all three corpora, and the
  * three files are byte-identical. So the tier is blind to the seed chain
  * iterating at all, to the size search moving off its initial value, to every
  * epoch boundary, and to ECIP-1099 in every respect -- the proposal postdates
  * the fixture and no published case exercises it. Those are covered by
  * hand-written cases in `EthashSpec`, against values read from the clients and
  * the proposal, and they are not certification.
  *
  * ==Computed once, because generating the cache is the expensive part==
  *
  * A cache is tens of megabytes and about a million 512-bit digests. Both cases
  * share an epoch, so one cache answers both, and every spec asserting something
  * about coverage asserts it about the same run.
  */
object EthashCorpus:

  /** Where the tier sits under the corpus root. */
  val Corpus: String = "ethereum/tests PoWTests"

  private def directory(under: Path): Path = under.resolve("ethereum/tests/PoWTests")

  /** The engine under certification.
    *
    * No ECIP-1099 activation, because this tier is Ethereum's and the proposal
    * is Ethereum Classic's. An engine carrying one would answer identically at
    * every case here -- all of them precede any activation a network states --
    * and choosing the engine that cannot is what keeps that independence
    * testable rather than asserted.
    */
  private val engine: ProofOfWorkEngine = ProofOfWorkEngine()

  lazy val report: Option[CorpusReport] = FixtureCorpus.root.map(root => assemble(directory(root)))

  private def assemble(under: Path): CorpusReport =
    val files = FixtureCorpus.jsonFilesUnder(under)
    val outcomes = files.flatMap { file =>
      FixtureCorpus
        .read(file)
        .flatMap(PoWFixture.decodeFile(file.getFileName.toString, _)) match
        case Left(error) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        case Right(fixtures) => fixtures.map(outcomeOf)
    }
    CorpusReport(Corpus, files.length, outcomes)

  /** Caches already grown, keyed by epoch.
    *
    * Not an optimization to be traded away: without it the tier costs one cache
    * per case rather than one per epoch, and the point of the cache being a
    * parameter rather than a field is that a caller can do exactly this.
    */
  private var grown: Map[BigInt, EthashCache] = Map.empty

  private def cacheFor(epoch: BigInt): EthashCache =
    grown.get(epoch) match
      case Some(cache) => cache
      case None        =>
        val cache = Ethash.cacheFor(epoch, Ethash.EpochLength)
        grown = grown.updated(epoch, cache)
        cache

  /** What running one case established, with a throw recorded as a divergence.
    *
    * A skip means there was nothing here to compare; a throw means the rule
    * broke on something there was. The boundary `DifficultyCorpus` draws for the
    * same reason, drawn again because the two harnesses share no runner.
    */
  private[certification] def outcomeOf(fixture: PoWFixture): CaseOutcome =
    val verdict =
      try
        RlpCodec.decodeFrom[BlockHeader](fixture.header.toIArray) match
          case Left(error)   => Verdict.Diverged(Vector("the case's header did not decode: " + error.toString))
          case Right(header) => checked(fixture, header)
      catch
        case NonFatal(cause) => Verdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    CaseOutcome(fixture.name, verdict)

  /** The eight things one case states, each compared on its own.
    *
    * Reported together rather than short-circuiting, so a case that moved two
    * steps says so. A divergence naming one step when two moved sends the next
    * reader to the wrong place.
    */
  private def checked(fixture: PoWFixture, header: BlockHeader): Verdict =
    val epoch = engine.epochOf(header.number.toBigInt)
    val cache = cacheFor(epoch)
    val sealHash = engine.sealHash(header)
    val solution = Ethash.evaluateLight(cache, fixture.datasetSize, sealHash, fixture.nonce.toIArray)
    val faults = Vector(
      differs("seal hash", sealHash, fixture.headerHash),
      differs("seed", Ethash.seedFor(epoch, Ethash.EpochLength), fixture.seed),
      unequal("cache size", Ethash.cacheSize(epoch), fixture.cacheSize),
      unequal("dataset size", Ethash.datasetSize(epoch), fixture.datasetSize),
      differs("cache digest", digestOf(cache), fixture.cacheHash),
      differs("mixed hash", solution.mixHash, fixture.mixHash),
      differs("result", solution.result, fixture.result),
      sealDiffers(fixture, header)
    ).flatten
    if faults.isEmpty then Verdict.Agreed else Verdict.Diverged(faults)

  /** The header's own two seal elements against the case's separate statement of
    * them.
    *
    * The case states `nonce` and `mixHash` beside a header that already carries
    * both, so they are a check on the decoder rather than on the algorithm --
    * and the one case in this tier where the answer is knowable without running
    * anything expensive.
    */
  private def sealDiffers(fixture: PoWFixture, header: BlockHeader): Option[String] =
    header.seal match
      case Seal.AuthorityRound(_, _)            => Some("the case's header decoded to the authority-round seal")
      case Seal.MixHashAndNonce(mixHash, nonce) =>
        Vector(
          differs("header's own mixed hash", mixHash, fixture.mixHash),
          Option.when(!sameBytes(nonce.toBytes, fixture.nonce.toIArray))("the header's nonce is not the case's")
        ).flatten.headOption

  private def sameBytes(left: IArray[Byte], right: IArray[Byte]): Boolean =
    left.length == right.length && left.indices.forall(i => left(i) == right(i))

  /** A digest over the cache as the bytes it was built from.
    *
    * The words are little-endian, so writing them back out in that order
    * reproduces the buffer exactly. **What the published `cache_hash` digests is
    * established by this agreeing with it**, because no client in the corpus
    * asserts the field -- so a divergence here is as likely to mean the field
    * means something else as to mean the cache is wrong, and the report says
    * which field moved rather than what it implies.
    */
  private def digestOf(cache: EthashCache): Hash =
    val out = new Array[Byte](cache.words.length * 4)
    var i = 0
    while i < cache.words.length do
      out(i * 4) = cache.words(i).toByte
      out(i * 4 + 1) = (cache.words(i) >>> 8).toByte
      out(i * 4 + 2) = (cache.words(i) >>> 16).toByte
      out(i * 4 + 3) = (cache.words(i) >>> 24).toByte
      i += 1
    Keccak256.hash(IArray.unsafeFromArray(out))

  private def differs(what: String, answered: Hash, expected: Hash): Option[String] =
    Option.when(answered != expected)(what + ": answered " + answered.toString + " rather than " + expected.toString)

  private def unequal(what: String, answered: Long, expected: Long): Option[String] =
    Option.when(answered != expected)(
      what + ": answered " + answered.toString + " rather than " + expected.toString
    )
