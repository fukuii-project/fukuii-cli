package org.fukuii.consensus.pow

import org.fukuii.bytes.Hash
import org.fukuii.crypto.{Keccak256, Keccak512}

/** The proof a proof-of-work header carries, and what it takes to check one.
  *
  * ==Both item sources are here, and they are one algorithm rather than two==
  *
  * Ethash is stated over a multi-gigabyte dataset that is a pure function of a
  * far smaller cache, so a header can be checked either by reading items from
  * that dataset or by regenerating the two each access needs.
  * `ethereum/execution-specs` @ `ccaaaba58` makes the relationship explicit in
  * its signatures: `hashimoto(header_hash, nonce, dataset_size,
  * fetch_dataset_item)` takes the source as an argument and `hashimoto_light`
  * is that same function closed over a cache. [[evaluate]] is written the same
  * way for the same reason, so [[evaluateLight]] and [[evaluateFull]] cannot
  * drift apart.
  *
  * ==Which one a caller wants is decided by what it is doing==
  *
  * **Validation needs neither the dataset nor the choice.**
  * execution-specs says so in `generate_dataset`'s own words -- *"This function
  * is present only for demonstration purposes. It is not used while validating
  * blocks."* -- and every surveyed client agrees in its code.
  * `ethereum/go-ethereum-pow` @ `v1.10.26` takes
  * `verifySeal(chain, header, fulldag bool)` and falls back to the cache
  * whenever the dataset is not already generated, so even a mining node
  * validates from the cache until it has one; `besu-eth/besu-etc` @
  * `eb4248c99` reaches the dataset only from `PoWMinerExecutor` and validates
  * through `EthHash.hashimotoLight`.
  *
  * **Producing a block needs the dataset**, because a miner evaluates once per
  * nonce and regenerating items from the cache is orders of magnitude slower
  * per evaluation. [[datasetFor]] is what that path is built from, and its
  * correctness is established by agreeing with the light path over the same
  * cache -- which is the only check available at a size no published value
  * states.
  *
  * ==The cache is a parameter and never a field==
  *
  * Generating one is seconds of work and tens of megabytes, and it is constant
  * across an epoch -- so every client holds one outside the rule and hands it
  * in. go-ethereum-pow keeps an LRU on the `Ethash` struct,
  * `NethermindEth/nethermind` @ `c35ce1b1a` keeps `_cacheCache`, and besu-etc
  * passes `int[] cache` into `hashimotoLight` as an argument. Retaining one is a
  * node's policy about memory rather than a rule about validity, so it sits with
  * whoever owns the node's memory and not here.
  */
object Ethash:

  /** How many blocks share one dataset, before ECIP-1099.
    *
    * Thirty thousand, from `ethereum/execution-specs` @ `ccaaaba58`'s
    * `EPOCH_SIZE = Uint(30000)` and from `ethereum/go-ethereum-pow` @
    * `v1.10.26`'s `epochLength = 30000`. `NethermindEth/nethermind` @
    * `c35ce1b1a` and `openethereum/openethereum` @ `v3.0.1` state the same
    * figure under their own names.
    */
  val EpochLength: BigInt = BigInt(30000)

  /** How many blocks share one dataset once ECIP-1099 has activated.
    *
    * The proposal states it as a constant -- *"const newEpochLength = 60000"* --
    * and `ethereumclassic/core-geth` @ `4185df450` declares
    * `epochLengthECIP1099 = 60000` beside its default.
    * `besu-eth/besu-etc` @ `eb4248c99` writes the same value as
    * `EthHash.EPOCH_LENGTH * 2` rather than naming it, which is the same number
    * and a different claim about where it comes from.
    */
  val Ecip1099EpochLength: BigInt = EpochLength * 2

  /** The largest cache this can build, which is the JVM's own array bound.
    *
    * A cache is generated as one contiguous byte array, so a size past
    * `Int.MaxValue` is unbuildable here whatever the arithmetic says. Cache size
    * grows by [[CacheGrowthBytes]] per epoch from [[CacheInitBytes]], so the
    * bound is first reached above epoch sixteen thousand -- beyond four hundred
    * million blocks at the legacy epoch length, which no network approaches.
    */
  private val LargestCache: Long = Int.MaxValue.toLong

  private val CacheInitBytes: Long = 1L << 24
  private val CacheGrowthBytes: Long = 1L << 17
  private val DatasetInitBytes: Long = 1L << 30
  private val DatasetGrowthBytes: Long = 1L << 23

  /** The longest seed chain any cache this can build asks for.
    *
    * The chain is counted in legacy epochs while the epoch it seeds is counted
    * in that epoch's own length, so an epoch at the ceiling [[bounded]] applies
    * asks for a chain [[Ecip1099EpochLength]] over [[EpochLength]] times longer
    * than its own number. Past that there is no cache for the seed to grow.
    */
  private val LongestSeedChain: BigInt =
    BigInt(LargestCache / CacheGrowthBytes) * (Ecip1099EpochLength / EpochLength)

  /** The width of one cache row and one dataset item, in bytes.
    *
    * Sixty-four, which is why the digest chaining a cache is built from is
    * [[org.fukuii.crypto.Keccak512]] rather than the 256-bit one everything else
    * in this project hashes with.
    */
  private val HashBytes: Int = 64

  /** How many 32-bit words one row holds. */
  private val HashWords: Int = HashBytes / 4

  /** The width of the mix, in bytes. Two rows. */
  private val MixBytes: Int = 128

  private val MixWords: Int = MixBytes / 4

  /** How many passes of RandMemoHash the cache is stirred by.
    *
    * Three, and it is worth stating that the number is read from the constant
    * and not from the prose beside it: execution-specs declares
    * `CACHE_ROUNDS = 3` while its own docstring one line above says the cache is
    * built by *"running two rounds"*. `go-ethereum-pow`, `core-geth`, `besu-etc`
    * and `nethermind` all declare three, and go-ethereum-pow's comment carries
    * the same "two passes" wording over the same constant -- so the prose is one
    * mistake copied along with the code, and the constant is the specification.
    */
  private val CacheRounds: Int = 3

  /** How many cache rows each dataset item is folded from. */
  private val DatasetParents: Int = 256

  /** How many dataset reads one evaluation makes. */
  private val Accesses: Int = 64

  /** The multiplier of the mixing function ethash uses in place of exclusive-or.
    *
    * `0x01000193`, the FNV prime. execution-specs notes that the use is not
    * FNV-1: *"here we multiply the prime with the full 32-bit input, in contrast
    * with the FNV-1 spec which multiplies the prime with one byte"*, which is
    * why the function is written out rather than taken from a hash library.
    */
  private val FnvPrime: Int = 0x01000193

  /** Which epoch `number` falls in, and how long that epoch is.
    *
    * ==ECIP-1099 is a parameter within ethash and never a second algorithm==
    *
    * The proposal changes one constant and nothing else: *"The oldEpochLength
    * (30000) changes to newEpochLength (60000) at a given `ETCHASH_FORK_BLOCK`"*.
    * Both implementing clients express exactly that and neither adds an engine --
    * `ethereumclassic/core-geth` @ `4185df450` has no `etchash` package and
    * threads `calcEpochLength(block, ecip1099FBlock)` through the ethash package
    * it already had, and `besu-eth/besu-etc` @ `eb4248c99` swaps one
    * `EpochCalculator` implementation for another.
    *
    * ==The predicate is resolved per block, which is the half a node needs==
    *
    * A node syncing across the transition validates blocks on both sides of it,
    * so the length has to be a function of the block rather than of the node.
    * core-geth's is; besu-etc's *validation* path is too, while its mining path
    * binds one calculator for the node's whole life and its ECIP-1099
    * constructor taking an activation block is commented out. Only the per-block
    * form answers both sides.
    *
    * @param activation
    *   the height ECIP-1099 takes effect at, and [[scala.None]] on a network
    *   that never adopts it. The proposal's own predicate is
    *   `blockNum < activationBlock` answering the old length, so the new length
    *   applies at the activation height itself.
    */
  def epochLengthAt(number: BigInt, activation: Option[BigInt]): BigInt =
    activation match
      case Some(height) if number >= height => Ecip1099EpochLength
      case _                                => EpochLength

  /** Which epoch a block belongs to, counting the first as zero.
    *
    * Plain floor division, which the proposal writes out as
    * `epoch := int(block / epochLength)`. **This is not ECIP-1017's convention
    * and the two must not be reconciled**: an era boundary is stated as `N+1`
    * and carries an offset, an epoch boundary is not and does not. They are
    * different documents settling different things and each is right about its
    * own.
    */
  def epochAt(number: BigInt, activation: Option[BigInt]): BigInt =
    number / epochLengthAt(number, activation)

  /** The seed a cache is grown from.
    *
    * ==Under ECIP-1099 the seed is still counted in LEGACY epochs, and that is
    * the proposal's own instruction rather than an implementation quirk==
    *
    * The specification's code comment says it outright -- *"keep using
    * oldEpochLength here so seeds don't overlap"* -- and its prose gives the
    * reason: *"To avoid re-use of seeds oldEpochLength will continue to be used
    * within the seedHash function."* So the epoch number that sizes a cache and
    * the count that seeds it come from DIFFERENT divisors once the fork is
    * active, and an implementation that uses one divisor throughout is wrong in
    * one of the two places while still producing plausible bytes.
    *
    * Both implementing clients do this and neither states why. core-geth's
    * `seedHash(epoch, epochLength)` derives a block from the epoch and its own
    * length, then divides that block by `epochLengthDefault`; besu-etc's
    * `DirectAcyclicGraphSeed.dagSeed` takes `epochCalculator.epochStartBlock`
    * and divides by the base `EPOCH_LENGTH`. Both therefore give ECIP-1099 epoch
    * `e` the seed of legacy epoch `2e`.
    *
    * ==The `+1` every client adds is inert, and only because the lengths are
    * commensurate==
    *
    * The epoch's first block is written `epoch * length + 1` in core-geth's
    * `calcEpochBlock`, in besu-etc's `epochStartBlock` and in the proposal's own
    * comment. Dividing that by the legacy length gives the same count as
    * dividing `epoch * length` would, because both lengths are exact multiples
    * of the legacy one. A length that was not would part the two, so the offset
    * is carried rather than dropped.
    *
    * ==The chain is bounded here rather than by whoever calls first==
    *
    * The loop below is one 256-bit digest per legacy epoch and allocates
    * nothing, so an epoch far enough out is CPU spent with no memory bound to
    * stop it. Nothing about the seed's own arithmetic limits that: a plausible
    * epoch with an implausible length reaches it as readily as the reverse.
    * [[cacheFor]] happens to size its cache before it asks for a seed, which
    * refuses such an epoch one argument earlier -- but that is argument
    * evaluation order rather than a property of this method, and this is public
    * and called directly.
    */
  def seedFor(epoch: BigInt, epochLength: BigInt): Hash =
    val firstBlock = epoch * epochLength + 1
    val rounds = if firstBlock < EpochLength then BigInt(0) else firstBlock / EpochLength
    if epoch < 0 || epochLength <= 0 || rounds > LongestSeedChain then
      throw new IllegalStateException(
        "no ethash seed is stated for epoch " + epoch.toString + " of " + epochLength.toString + " blocks"
      )
    var seed = Hash.fromBytesTruncating(IArray.unsafeFromArray(new Array[Byte](Hash.Width)))
    var taken = BigInt(0)
    while taken < rounds do
      seed = Keccak256.hash(seed.toBytes)
      taken += 1
    seed

  /** How many bytes the cache for `epoch` holds.
    *
    * The size grows linearly and is then walked down to the largest value whose
    * row count is prime, which execution-specs explains as being *"to minimize
    * the risk of unintended cyclic behavior"*. Every surveyed client computes
    * it the same way; three of them additionally ship a precomputed table for
    * the first two thousand epochs, which is a lookup for the same arithmetic
    * and not a second rule.
    */
  def cacheSize(epoch: BigInt): Long =
    largestPrimeRowed(CacheInitBytes + CacheGrowthBytes * bounded(epoch), HashBytes)

  /** How many bytes the dataset for `epoch` would hold.
    *
    * Read as a number and never built: the light path needs the size, because
    * the index of every access is taken modulo the row count, and needs no byte
    * of the dataset itself.
    */
  def datasetSize(epoch: BigInt): Long =
    largestPrimeRowed(DatasetInitBytes + DatasetGrowthBytes * bounded(epoch), MixBytes)

  /** The epoch as a machine integer, refused where the arithmetic over it would
    * not be exact.
    *
    * The ceiling is the cache's, and both sizings are held to it: a dataset is
    * read as a number rather than built, so its own growth would admit a far
    * larger epoch than any cache for it could be grown from. **The message
    * names both artifacts because this is reached from both** -- one naming a
    * dataset alone reports the wrong artifact to every caller sizing a cache.
    */
  private def bounded(epoch: BigInt): Long =
    if epoch < 0 || epoch > LargestCache / CacheGrowthBytes then
      throw new IllegalStateException("no ethash cache or dataset is stated for epoch " + epoch.toString)
    else epoch.toLong

  private def largestPrimeRowed(linear: Long, rowWidth: Int): Long =
    var size = linear - rowWidth
    while !isPrime(size / rowWidth) do size -= 2L * rowWidth
    size

  /** Trial division, which is exact for every value this asks about.
    *
    * **Deliberately not `BigInt.isProbablePrime`, and transcribing the clients
    * literally is what would go wrong.** go-ethereum-pow writes
    * `ProbablyPrime(1)` and comments it *"always accurate for n < 2^64"* --
    * true of Go, whose test runs a Miller-Rabin round AND a Baillie-PSW step.
    * The JVM's `isProbablePrime(1)` runs a single Miller-Rabin round and no
    * Lucas step, so the same argument does not carry across and the same
    * spelling is weaker than the client it came from. Row counts here are below
    * two to the twenty-third, where dividing to the square root is a few
    * thousand operations and admits no probability at all.
    */
  private def isPrime(n: Long): Boolean =
    if n < 2 then false
    else if n % 2 == 0 then n == 2
    else
      var divisor = 3L
      var prime = true
      while prime && divisor * divisor <= n do
        if n % divisor == 0 then prime = false
        divisor += 2
      prime

  /** Grows the cache for an epoch: a chain of 512-bit digests, stirred.
    *
    * The construction is Sergio Demian Lerner's RandMemoHash, and the cost is
    * one 512-bit digest per row per pass plus one to fill it -- so four per row
    * at [[CacheRounds]] three, and a little over a million for the first epoch.
    *
    * ==A caller reaching this from a header must bound the epoch against its own
    * head, and nothing here can do it==
    *
    * Cost grows linearly with the epoch and the only ceiling here is
    * [[LargestCache]], the JVM's array bound -- which is a limit on what can be
    * represented, not on what a chain states. An epoch is read off a height, a
    * height is eight bytes of a header, and a header arrives from a peer, so the
    * work this does is selected by whoever sent it: a height no chain has
    * reached names an epoch orders of magnitude past the one in force and is
    * refused by nothing below.
    *
    * **The bound belongs to whoever knows the chain's own head, which is not
    * this.** An epoch is a pure function of a height and this has no view of
    * which heights exist, so a check here would either be a constant that goes
    * stale or a chain fact this layer does not hold. **A block-import caller is
    * therefore required to refuse an epoch materially above the head it is
    * syncing toward, before it asks for a cache** -- an obligation, recorded
    * because no such caller exists yet and the layer that lands it inherits
    * this rather than rediscovering it.
    */
  def cacheFor(epoch: BigInt, epochLength: BigInt): EthashCache =
    cacheFrom(cacheSize(epoch), seedFor(epoch, epochLength), epoch, epochLength)

  /** Grows a cache of a stated size from a stated seed.
    *
    * ==A size and a seed rather than an epoch, which is the clients' own
    * factoring==
    *
    * `ethereum/go-ethereum-pow` @ `v1.10.26` declares
    * `generateCache(cache []uint32, epoch uint64, seed []byte)` and takes the
    * size from the slice it was handed; `besu-eth/besu-etc` @ `eb4248c99`
    * declares `mkCache(int cacheSize, long block, EpochCalculator)`. Both
    * separate what to build from which epoch asked for it, and both of their
    * own test suites use that separation to exercise the construction at a size
    * no epoch states.
    *
    * The epoch and its length are carried through onto the result and take no
    * part in the construction: together they are what stops a cache being
    * handed to a header the seed does not answer for, which
    * [[EthashEngine.verifySeal]] checks. **Both, because under ECIP-1099 the
    * epoch number alone does not identify a cache** -- see [[EthashCache]],
    * which states why.
    *
    * @param epochLength
    *   the length the epoch was counted in. Not derivable from the other two:
    *   [[cacheSize]] reads the epoch alone, so the two lengths give a cache of
    *   identical size at the same epoch number, and only the seed parts them.
    */
  def cacheFrom(size: Long, seed: Hash, epoch: BigInt, epochLength: BigInt): EthashCache =
    if size <= 0 || size % HashBytes != 0 then
      throw new IllegalStateException("no ethash cache is stated at " + size.toString + " bytes")
    if size > LargestCache then
      throw new IllegalStateException("an ethash cache of " + size.toString + " bytes cannot be held in one array")
    val bytes = new Array[Byte](size.toInt)
    val rows = bytes.length / HashBytes
    writeAt(bytes, 0, Keccak512.hash(seed.toBytes))
    var offset = HashBytes
    while offset < bytes.length do
      writeAt(bytes, offset, Keccak512.hash(sliceAt(bytes, offset - HashBytes)))
      offset += HashBytes
    var round = 0
    while round < CacheRounds do
      var row = 0
      while row < rows do
        val previous = ((row - 1 + rows) % rows) * HashBytes
        val mixed = Integer.remainderUnsigned(leWordAt(bytes, row * HashBytes), rows) * HashBytes
        writeAt(bytes, row * HashBytes, Keccak512.hash(xorAt(bytes, previous, mixed)))
        row += 1
      round += 1
    EthashCache(epoch, epochLength, cacheWords(bytes))

  /** One dataset item, folded out of the cache rather than read from a dataset.
    *
    * ==The index is a 32-bit quantity here, which follows the clients and not
    * the specification==
    *
    * execution-specs computes the access index in arbitrary precision and notes
    * the reason: *"Typecasting `parent` from U32 to Uint as 2*parent + j may
    * overflow U32."* `go-ethereum-pow` and `besu-etc` both keep it in 32 bits
    * and wrap. The two part only where a dataset exceeds two hundred and
    * seventy gigabytes, which is past epoch two million and unreachable by
    * anything [[cacheFor]] can build -- and the clients are what produced the
    * chains being validated, so where they part from a later reconstruction on
    * an unreachable input, they are what is followed.
    */
  def datasetItem(cache: IArray[Int], index: Int): Array[Int] =
    val rows = cache.length / HashWords
    val mix = new Array[Int](HashWords)
    val start = Integer.remainderUnsigned(index, rows) * HashWords
    var word = 0
    while word < HashWords do
      mix(word) = cache(start + word)
      word += 1
    mix(0) = mix(0) ^ index
    val seeded = wordsOf(Keccak512.hash(leBytes(mix)))
    var parent = 0
    while parent < DatasetParents do
      val row = Integer.remainderUnsigned(fnv(index ^ parent, seeded(parent % HashWords)), rows) * HashWords
      var word2 = 0
      while word2 < HashWords do
        seeded(word2) = fnv(seeded(word2), cache(row + word2))
        word2 += 1
      parent += 1
    wordsOf(Keccak512.hash(leBytes(seeded)))

  /** Builds every item of the dataset the light path regenerates two at a time.
    *
    * ==What it is for, given that validation does not need it==
    *
    * Producing a block does. A miner evaluates the algorithm once per nonce
    * tried, and regenerating two items per access from the cache is roughly a
    * thousand times the work of reading them, which is the whole reason the
    * dataset exists. `ethereum/go-ethereum-pow` @ `v1.10.26` calls
    * `generateDataset` only from the sealer's path and from `makedag`;
    * `besu-eth/besu-etc` @ `eb4248c99` reaches it only through
    * `PoWMinerExecutor`.
    *
    * ==Its correctness is checkable at any size, and that is not a compromise==
    *
    * The item at index `i` is a pure function of the cache and `i`, so nothing
    * about the construction varies with how many are built --
    * [[evaluateFull]] over a dataset and [[evaluateLight]] over the cache it
    * came from are required to agree, and go-ethereum-pow's own
    * `TestDatasetGeneration` states a full expected dataset at thirty-two
    * kilobytes rather than at the gigabyte a real epoch needs.
    *
    * ==A real epoch's dataset is a gigabyte, and that is the caller's problem==
    *
    * Nothing here streams or memory-maps: the whole thing is one array, so a
    * caller asks for a size it can hold. `datasetSize` is taken as a parameter
    * rather than read from the cache's epoch for exactly that reason, and
    * because both surveyed clients parameterize the same call.
    */
  def datasetFor(cache: EthashCache, datasetSize: Long): EthashDataset =
    if datasetSize <= 0 || datasetSize % HashBytes != 0 then
      throw new IllegalStateException("no ethash dataset is stated at " + datasetSize.toString + " bytes")
    if datasetSize > LargestCache then
      throw new IllegalStateException("an ethash dataset of " + datasetSize.toString + " bytes exceeds one array")
    val words = new Array[Int]((datasetSize / 4).toInt)
    val items = (datasetSize / HashBytes).toInt
    var item = 0
    while item < items do
      val built = datasetItem(cache.words, item)
      var word = 0
      while word < HashWords do
        words(item * HashWords + word) = built(word)
        word += 1
      item += 1
    EthashDataset(cache.epoch, cache.epochLength, IArray.unsafeFromArray(words))

  /** Runs the algorithm, regenerating each item it reads from the cache.
    *
    * @param nonce
    *   the header's eight bytes as stored. They are reversed on the way in --
    *   `Bytes(reversed(nonce))` in execution-specs,
    *   `binary.LittleEndian.PutUint64` over a big-endian read in
    *   go-ethereum-pow, `Long.reverseBytes` in besu-etc. Three clients and the
    *   specification agree, and getting it wrong yields a well-formed digest
    *   that matches no chain.
    */
  def evaluateLight(cache: EthashCache, datasetSize: Long, sealHash: Hash, nonce: IArray[Byte]): EthashSolution =
    evaluate(datasetSize, sealHash, nonce)(datasetItem(cache.words, _))

  /** Runs the algorithm, reading each item from a dataset already built.
    *
    * The size is the dataset's own, which is what makes this and
    * [[evaluateLight]] comparable: go-ethereum-pow's `hashimotoFull` likewise
    * takes no size and derives one from the dataset it was handed, while its
    * light counterpart is given one.
    */
  def evaluateFull(dataset: EthashDataset, sealHash: Hash, nonce: IArray[Byte]): EthashSolution =
    evaluate(dataset.size, sealHash, nonce)(itemOf(dataset.words, _))

  private def itemOf(words: IArray[Int], index: Int): Array[Int] =
    val out = new Array[Int](HashWords)
    var word = 0
    while word < HashWords do
      out(word) = words(index * HashWords + word)
      word += 1
    out

  /** The mixing loop, over whatever supplies its items.
    *
    * ==One function taking an item source, which is the specification's own
    * factoring and not a convenience==
    *
    * `ethereum/execution-specs` @ `ccaaaba58` declares
    * `hashimoto(header_hash, nonce, dataset_size, fetch_dataset_item)` and
    * defines `hashimoto_light` as that function closed over a cache. Writing
    * the loop twice is what would let the two paths drift, and the two paths
    * agreeing is the only check on a dataset that has no published expected
    * value at a real epoch's size.
    *
    * ==The size is refused here, which is what reaches both paths==
    *
    * The row count below narrows to a machine integer, and each of the three
    * ways a size can be wrong fails differently and quietly: a size at or below
    * nothing divides by zero, a size that is not a whole number of rows
    * truncates to a count the accesses are then taken modulo, and a size whose
    * row count exceeds what an `Int` holds wraps to a small or negative one.
    * Only the first announces itself. **The refusal sits on the shared body
    * rather than on [[evaluateLight]]**, so the size a caller supplies and the
    * size a dataset reports are held to one rule -- the same reason the loop
    * itself is written once.
    *
    * It is a rule about the size ALONE and deliberately not about the epoch:
    * both surveyed clients evaluate over a dataset built far smaller than any
    * epoch states, which is how the full path is exercised at all, so binding
    * the size to [[datasetSize]] here would refuse the one case that makes
    * [[evaluateFull]] checkable.
    */
  private def evaluate(datasetSize: Long, sealHash: Hash, nonce: IArray[Byte])(
      fetch: Int => Array[Int]
  ): EthashSolution =
    if datasetSize <= 0 || datasetSize % MixBytes != 0 || datasetSize / MixBytes > Int.MaxValue then
      throw new IllegalStateException("no ethash evaluation is stated over " + datasetSize.toString + " bytes")
    val rows = (datasetSize / MixBytes).toInt
    val seed = Keccak512.hash(sealHash.toBytes ++ reversed(nonce))
    val seedHead = leadWord(seed)
    val seedWords = wordsOf(seed)
    val mix = new Array[Int](MixWords)
    var filled = 0
    while filled < MixWords do
      mix(filled) = seedWords(filled % HashWords)
      filled += 1
    var access = 0
    while access < Accesses do
      val block = Integer.remainderUnsigned(fnv(access ^ seedHead, mix(access % MixWords)), rows)
      var half = 0
      while half < MixBytes / HashBytes do
        val item = fetch(2 * block + half)
        var word = 0
        while word < HashWords do
          mix(half * HashWords + word) = fnv(mix(half * HashWords + word), item(word))
          word += 1
        half += 1
      access += 1
    val compressed = new Array[Int](MixWords / 4)
    var group = 0
    while group < compressed.length do
      val at = group * 4
      compressed(group) = fnv(fnv(fnv(mix(at), mix(at + 1)), mix(at + 2)), mix(at + 3))
      group += 1
    val mixHash = leBytes(compressed)
    EthashSolution(
      mixHash = Hash.fromBytesTruncating(mixHash),
      result = Keccak256.hash(seed ++ mixHash)
    )

  /** Whether a result clears the bar a difficulty sets.
    *
    * ==Stated in arbitrary precision, which is go-ethereum's form and avoids
    * besu's one special case==
    *
    * `go-ethereum-pow` @ `v1.10.26` divides `two256` by the difficulty and
    * refuses a result above the quotient. `besu-eth/besu-etc` @ `eb4248c99`
    * computes the same quotient and carries an extra arm answering
    * `UInt256.MAX_VALUE` at a difficulty of one, because `2^256` does not fit
    * the fixed-width type it divides in. **That arm is an artifact of the
    * arithmetic and not a rule** -- the two agree on every difficulty including
    * one -- so it is not reproduced.
    *
    * A difficulty of zero is refused rather than divided by. Both clients refuse
    * it: go-ethereum-pow on `Difficulty.Sign() <= 0` and besu-etc on
    * `getDifficulty().isZero()`.
    */
  def clears(result: Hash, difficulty: BigInt): Boolean =
    if difficulty <= 0 then false
    else bigEndian(result.toBytes) <= (BigInt(1) << 256) / difficulty

  /** A digest read as the unsigned number its bytes spell, most significant
    * first.
    *
    * Folded rather than handed to a constructor, because the two routes an
    * `IArray` offers are both wrong here: `IArray.toArray` is deprecated at this
    * compiler as *"incorrect and calling it can crash your program"*, and
    * treating the bytes as signed would read every digest with a high bit set as
    * negative -- which is half of them, and every one of those would clear any
    * target.
    */
  private def bigEndian(bytes: IArray[Byte]): BigInt =
    var value = BigInt(0)
    var i = 0
    while i < bytes.length do
      value = (value << 8) + (bytes(i) & 0xff)
      i += 1
    value

  /** ethash's substitute for exclusive-or, which is deliberately not
    * associative.
    */
  private def fnv(a: Int, b: Int): Int = a * FnvPrime ^ b

  private def reversed(bytes: IArray[Byte]): IArray[Byte] =
    val out = new Array[Byte](bytes.length)
    var i = 0
    while i < bytes.length do
      out(i) = bytes(bytes.length - 1 - i)
      i += 1
    IArray.unsafeFromArray(out)

  private def writeAt(target: Array[Byte], offset: Int, source: IArray[Byte]): Unit =
    var i = 0
    while i < source.length do
      target(offset + i) = source(i)
      i += 1

  private def sliceAt(source: Array[Byte], offset: Int): IArray[Byte] =
    val out = new Array[Byte](HashBytes)
    var i = 0
    while i < HashBytes do
      out(i) = source(offset + i)
      i += 1
    IArray.unsafeFromArray(out)

  private def xorAt(source: Array[Byte], first: Int, second: Int): IArray[Byte] =
    val out = new Array[Byte](HashBytes)
    var i = 0
    while i < HashBytes do
      out(i) = (source(first + i) ^ source(second + i)).toByte
      i += 1
    IArray.unsafeFromArray(out)

  /** The little-endian 32-bit word starting at `offset` of the cache buffer.
    *
    * **The buffer and the digest forms below are named apart rather than
    * overloaded, and that is a compile error rather than a preference.**
    * `IArray` erases to `Array`, so two methods differing only in which of the
    * two they take have one signature after erasure and the compiler refuses
    * the pair.
    */
  private def leWordAt(buffer: Array[Byte], offset: Int): Int =
    (buffer(offset) & 0xff) |
      ((buffer(offset + 1) & 0xff) << 8) |
      ((buffer(offset + 2) & 0xff) << 16) |
      ((buffer(offset + 3) & 0xff) << 24)

  /** The first little-endian word of a digest, which is the value the mixing
    * loop and the cache stirring both index by.
    */
  private def leadWord(digest: IArray[Byte]): Int =
    (digest(0) & 0xff) |
      ((digest(1) & 0xff) << 8) |
      ((digest(2) & 0xff) << 16) |
      ((digest(3) & 0xff) << 24)

  /** The finished cache, as the little-endian words every later read takes. */
  private def cacheWords(buffer: Array[Byte]): IArray[Int] =
    val out = new Array[Int](buffer.length / 4)
    var i = 0
    while i < out.length do
      out(i) = leWordAt(buffer, i * 4)
      i += 1
    IArray.unsafeFromArray(out)

  /** A digest as little-endian words, mutable because every caller folds into
    * it in place.
    */
  private def wordsOf(digest: IArray[Byte]): Array[Int] =
    val out = new Array[Int](digest.length / 4)
    var i = 0
    while i < out.length do
      val at = i * 4
      out(i) = (digest(at) & 0xff) |
        ((digest(at + 1) & 0xff) << 8) |
        ((digest(at + 2) & 0xff) << 16) |
        ((digest(at + 3) & 0xff) << 24)
      i += 1
    out

  private def leBytes(source: Array[Int]): IArray[Byte] =
    val out = new Array[Byte](source.length * 4)
    var i = 0
    while i < source.length do
      out(i * 4) = source(i).toByte
      out(i * 4 + 1) = (source(i) >>> 8).toByte
      out(i * 4 + 2) = (source(i) >>> 16).toByte
      out(i * 4 + 3) = (source(i) >>> 24).toByte
      i += 1
    IArray.unsafeFromArray(out)

/** A generated ethash cache, and the epoch it belongs to.
  *
  * The epoch travels with the words because a cache is only meaningful against
  * the one it was grown for. Holding them apart is how a node hands the wrong
  * epoch's cache to a header and gets a well-formed answer that agrees with
  * nothing.
  *
  * ==The epoch NUMBER does not identify a cache, and under ECIP-1099 that is
  * reachable rather than theoretical==
  *
  * [[Ethash.epochAt]] counts in whichever length is in force, so the block
  * before the activation and the block at it can answer the SAME epoch number
  * under different lengths -- and [[Ethash.seedFor]] counts the seed in legacy
  * epochs throughout, so those two caches are grown from different seeds. They
  * agree in epoch number and, because [[Ethash.cacheSize]] reads the epoch
  * alone, in length to the byte. **Nothing about either value tells them
  * apart**, and a cache is a pure function of its seed, so the wrong one
  * answers a well-formed digest that matches no chain -- reported as a bad
  * seal rather than as the caller error it is.
  *
  * `ethereumclassic/core-geth` @ `4185df450` keys its own cache on both, at
  * `consensus/ethash/ethash.go` -- `cacheKey := epochLength + epoch`, whose
  * comment concedes *"This is not perfectly safe, but it's good enough (at
  * least for the first 30000 epochs, or the first 427 years)."* **The sum is
  * what that caveat is about**: it is not injective, so epoch 30001 at the
  * legacy length and epoch 1 at the doubled one collide on one key. Both
  * fields are carried here and compared as a pair, which admits no such
  * collision at any epoch.
  *
  * @param epochLength
  *   the length `epoch` was counted in. Together with `epoch` it fixes the
  *   seed, and therefore every byte below.
  */
final case class EthashCache(epoch: BigInt, epochLength: BigInt, words: IArray[Int]):

  /** How many bytes the cache holds, which is what a published case states. */
  def size: Long = words.length.toLong * 4

/** A generated ethash dataset, and the epoch it belongs to.
  *
  * The words are the same little-endian layout as [[EthashCache]]'s, so an item
  * is sixteen of them. **The epoch travels for the same reason it travels with a
  * cache and one more:** a dataset can legitimately be built at a size that is
  * not its epoch's, which is how both the specification's harness and
  * go-ethereum-pow's exercise the full path cheaply, so the size alone does not
  * say which epoch it belongs to.
  *
  * The length travels for the reason [[EthashCache]] states, and is that of the
  * cache this was folded out of: a dataset item is a pure function of the cache
  * and an index, so a dataset inherits its source's identity exactly.
  */
final case class EthashDataset(epoch: BigInt, epochLength: BigInt, words: IArray[Int]):

  def size: Long = words.length.toLong * 4

/** What an evaluation produces: the digest a header must repeat, and the value
  * its difficulty must admit.
  *
  * Named for besu's `PoWSolution`, prefixed because it is this algorithm's and
  * not proof-of-work's in general -- `ethereumclassic/core-geth` @ `4185df450`
  * ships a second proof-of-work engine beside ethash, so an unprefixed name
  * would claim a namespace that is already shared.
  */
final case class EthashSolution(mixHash: Hash, result: Hash)
