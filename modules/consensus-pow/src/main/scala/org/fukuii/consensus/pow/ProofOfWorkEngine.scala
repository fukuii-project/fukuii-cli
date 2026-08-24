package org.fukuii.consensus.pow

import org.fukuii.bytes.{Address, Hash, UInt256, UInt64}
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment}
import org.fukuii.consensus.ConsensusEngine
import org.fukuii.crypto.Keccak256
import org.fukuii.evm.WorldState
import org.fukuii.rlp.{Rlp, RlpCodec, RlpItem}
import org.fukuii.types.{BlockHeader, Seal}

/** The emission a proof-of-work network pays for a block and for the ommers
  * that block included.
  *
  * ==One engine and one parameter, which is how two of the three lineages ship
  * it==
  *
  * ECIP-1017 replaces a constant emission with a ladder that steps down by a
  * fifth every era, and a network either runs that ladder or does not.
  * `openethereum/openethereum` @ `v3.0.1` expresses the choice as a single
  * engine parameter -- `EthashParams.ecip1017_era_rounds`, defaulted to
  * `u64::max_value()` so that a network which never set it computes era zero at
  * every height -- and `ethereumclassic/core-geth` @ `4185df450` as a predicate
  * over one chain configuration, `IsEnabled(GetEthashECIP1017Transition, ...)`,
  * selecting between two functions in one package. `besu-eth/besu-etc` @
  * `eb4248c99` is the third and takes the other shape, installing a
  * `ClassicBlockProcessor` subclass at the fork that adopts the proposal.
  *
  * **The parameter shape is taken because the ladder is inert below its own
  * activation, and that is provable rather than incidental.** Era zero pays the
  * amount the fork resolved and applies the ommer rules that amount has always
  * carried, so a network running the ladder from its genesis block pays exactly
  * what a network without it pays until the first era boundary. That is why
  * `openethereum` needs no activation height beside the era length, and it is
  * why one is not carried here.
  *
  * ==The ladder is the engine's because no finite per-fork map can hold it==
  *
  * ECIP-1017 states *"All rewards will be reduced at a constant rate of 20%
  * upon entering a new Era"* and *"Every Era will last for 5,000,000 blocks"*,
  * with no last era. A rule set resolved per upgrade can state the amount an
  * era starts from and cannot state an unbounded sequence of them, which is the
  * split [[org.fukuii.chainspec.ConsensusRules]] documents from the other side:
  * the value is the fork's and the formula over it is this.
  *
  * @param ecip1017EraLength
  *   how many blocks one era lasts, where this network runs ECIP-1017, and
  *   [[scala.None]] where it does not.
  *
  *   **An era length is a per-network parameter and no client hardcodes it.**
  *   besu-etc reads `genesisConfigOptions.getEcip1017EraRounds()` and defaults
  *   it, core-geth carries `ECIP1017EraRounds` in chain configuration and ships
  *   a second configuration setting it to 5,000 rather than 5,000,000, and
  *   OpenEthereum reads `ecip1017EraRounds` from its engine parameters. The
  *   proposal is named in the parameter for the reason all three name it there:
  *   the number means nothing except as that document's era.
  *
  * @param ecip1099Activation
  *   the height at which the epoch length changes, and [[scala.None]] on a
  *   network that never adopts the proposal. It sits beside the era length
  *   rather than on a second engine because ECIP-1099 changes one constant --
  *   *"Ethash transitions to a modified Dagger Hashimoto algorithm, referred to
  *   hereby as Etchash"* is the proposal's own framing, and neither implementing
  *   client builds a second engine for it. See [[Ethash.epochLengthAt]].
  *
  *   **The network's own height is not defaulted here.** The proposal states one
  *   per network -- `11_700_000` for Ethereum Classic, `2_520_000` for Mordor,
  *   and *"no upgrade is required"* for Kotti -- so a default would be one
  *   network's answer wearing no network's name, which is what a chain
  *   specification exists to state.
  */
final case class ProofOfWorkEngine(
    ecip1017EraLength: Option[BigInt] = None,
    ecip1099Activation: Option[BigInt] = None
) extends ConsensusEngine:

  /** Credits the block's beneficiary, then each ommer's.
    *
    * ==Four credits rather than one, which is what makes the zero case this
    * mechanism's own==
    *
    * [[org.fukuii.consensus.ConsensusEngine.credit]] asks
    * [[org.fukuii.chainspec.ConsensusRules.zeroRewardCreditsBeneficiary]] of
    * the amount it is handed, so a mechanism paying several figures asks it of
    * each. That is the answer this emission needs: ECIP-1017 steps the winner's
    * reward down until it reaches nothing, and every other figure here is a
    * fraction of that stepped-down amount, so a resolved reward that is not
    * zero still pays zero once the ladder has exhausted it.
    *
    * ==Declining to credit covers the ommers too, and needs no check of its own==
    *
    * `besu-eth/besu` @ `c2addd9424` returns from `rewardCoinbase` on
    * `skipZeroBlockRewards && blockReward.isZero()` **before** its ommer loop,
    * so a mechanism that credits nobody for the block credits nobody for an
    * ommer either. The alternative would bring an ommer's beneficiary into
    * being on a network whose emission is nothing, which is a leaf in the state
    * trie that network does not have.
    *
    * A per-credit question reaches that outcome without a check of the resolved
    * amount beside it, because a resolved reward of nothing leaves nothing for
    * any figure here to be a fraction of: [[winnerReward]] answers zero at that
    * amount for every era, the inclusion bonus is a thirty-second of it, and an
    * ommer's share is taken over it under whichever of the two rules applies.
    *
    * ==Nothing here refuses a block==
    *
    * An ommer's admissibility -- how many there may be, how old, whose sibling
    * -- is block-body validation and needs the chain rather than the state.
    * `besu-eth/besu` @ `c2addd9424` settles it in
    * `MainnetBlockBodyValidator.isOmmerSiblingOfAncestor`, walking ancestors
    * from the blockchain, and `ethereum/execution-specs` @ `ccaaaba58` in
    * `validate_ommers`, which reads `chain.blocks`. This is handed ommers that
    * have already passed such a check and has no channel to refuse one.
    */
  override def settlement(
      rules: ConsensusRules,
      beneficiary: Address,
      number: BigInt,
      ommers: Seq[BlockHeader]
  ): WorldState => Unit =
    world =>
      val era = eraAt(number)
      val winner = winnerReward(rules.blockReward.toBigInt, era)
      credit(world, rules, beneficiary, winner + winner / ProofOfWorkEngine.InclusionDivisor * ommers.size)
      ommers.foreach(ommer =>
        credit(world, rules, ommer.beneficiary, ommerReward(winner, era, number, ommer.number.toBigInt))
      )

  /** What the block after `parent` must be mined against.
    *
    * ==One expression for all three algorithms, which is EIP-2's own framing==
    *
    * `ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38` writes the
    * rule it replaces and the rule it introduces in the same shape -- a parent
    * difficulty, plus one adjustment step multiplied by a figure read off the
    * gap between the two blocks. So the algorithm chooses the multiplier and
    * [[org.fukuii.chainspec.DifficultyAdjustment]] enumerates exactly that.
    *
    * ==The floor is applied to the adjustment and the bomb is added over it==
    *
    * **This is a real divergence in the field and the published corpus cannot
    * settle it**, so the order is stated rather than inherited.
    * `ethereum/execution-specs` @ `ccaaaba58` adds the exponential term first
    * and takes `max(difficulty, MINIMUM_DIFFICULTY)` over the sum, and its own
    * comment records that *"some clients raise the difficulty to
    * `MINIMUM_DIFFICULTY` prior to adding the bomb"* and that the difference
    * *"does not matter because the difficulty is always much greater than
    * `MINIMUM_DIFFICULTY` on Mainnet"*.
    *
    * The order here is the other one, on three counts. Every surveyed
    * implementation uses it, across three independent language lineages:
    * `ethereum/go-ethereum-pow` @ `v1.10.26` floors with the comment
    * *"minimum difficulty can ever be (before exponential factor)"*,
    * `besu-eth/besu-etc` @ `eb4248c99` wraps the adjustment in
    * `ensureMinimumDifficulty` before `adjustForPeriod`, and
    * `openethereum/openethereum` @ `v3.0.1` floors the target and then floors
    * the sum again, which is the same value. EIP-2 states it in prose --
    * *"The `minDifficulty` still defines the minimum difficulty allowed and no
    * ADJUSTMENT may take it below this"*. And the first of those is the client
    * that produced the chain this rule is read against.
    *
    * **The two orders agree on every case of the published corpus**, because
    * they differ only where an adjusted difficulty falls below the floor while
    * the exponential term is not yet zero -- which no mainnet block of either
    * family reaches. A network launching near the floor and running past the
    * term's first period is where the choice becomes observable.
    *
    * @param parentHasOmmers
    *   whether the parent block itself included any.
    *
    *   **A parameter rather than a reading of `parent.ommersHash`, which is what
    *   every client does instead.** They compare against a hash of the empty
    *   list -- `types.EmptyUncleHash` in the go-ethereum line,
    *   `Hash.EMPTY_LIST_HASH` in besu -- and this build carries no such constant
    *   because nothing has needed one. The executable specification takes the
    *   same parameter for the same reason, declaring `parent_has_ommers: bool`
    *   on `calculate_block_difficulty` from `forks/byzantium/fork.py` onward.
    *   Ommer validation is what brings the constant, and this reads it off the
    *   header once that lands.
    *
    *   It is read only under [[org.fukuii.chainspec.DifficultyAdjustment.Eip100]]
    *   and is supplied at every call, which is the shape the seam already takes
    *   for the block facts a mechanism may or may not read.
    */
  def difficulty(
      rules: ConsensusRules,
      parent: BlockHeader,
      parentHasOmmers: Boolean,
      timestamp: UInt64
  ): UInt256 =
    val parentDifficulty = parent.difficulty.toBigInt
    val gap = gapAfter(parent, timestamp)
    val step = parentDifficulty / boundDivisor(rules)
    val adjusted = rules.difficultyAdjustment match
      case DifficultyAdjustment.Original =>
        if gap < ProofOfWorkEngine.DurationLimit then parentDifficulty + step else parentDifficulty - step
      case DifficultyAdjustment.Eip2 =>
        parentDifficulty + step * multiplier(BigInt(1), gap, ProofOfWorkEngine.Eip2GapDivisor)
      case DifficultyAdjustment.Eip100 =>
        val raised = if parentHasOmmers then BigInt(2) else BigInt(1)
        parentDifficulty + step * multiplier(raised, gap, ProofOfWorkEngine.Eip100GapDivisor)
    val floored = adjusted.max(ProofOfWorkEngine.MinimumDifficulty)
    UInt256
      .fromBigInt(floored + bomb(parent.number.toBigInt + 1, rules.difficultyBombDelay))
      .getOrElse(
        throw new IllegalStateException(
          "a difficulty above what a header can carry was computed for the block after " + parent.number.toString
        )
      )

  /** Which ethash epoch a height falls in under this network's parameters. */
  def epochOf(number: BigInt): BigInt = Ethash.epochAt(number, ecip1099Activation)

  /** The cache a header at `number` is checked against.
    *
    * Generating one is tens of megabytes and roughly a million 512-bit digests,
    * so a caller validating more than one block in an epoch generates it once
    * and hands the same value to every [[verifySeal]] in that epoch. Nothing
    * here retains it: see [[Ethash]] on why the cache is a parameter.
    */
  def cacheFor(number: BigInt): EthashCache =
    Ethash.cacheFor(epochOf(number), Ethash.epochLengthAt(number, ecip1099Activation))

  /** The digest a nonce is sought against: the header without its seal.
    *
    * ==Derived from the header's own encoder rather than by listing the fields
    * again==
    *
    * Both surveyed clients that carry this write the field list out by hand,
    * and `besu-eth/besu-etc` @ `eb4248c99` writes it out TWICE -- once in
    * `EthHash.hashHeader` and once in `ProofOfWorkValidationRule.hashHeader` --
    * where the two copies already disagree about the condition guarding the
    * fee-market field. Two transcriptions of one field order is the defect
    * shape, so the order is taken from the codec that defines it and the seal's
    * own two elements are removed by the arity the seal declares.
    *
    * ==What is dropped is a position, and the position is the seal's own==
    *
    * [[org.fukuii.types.Seal.FieldCount]] elements ending where the mandatory
    * fields end, which is where
    * [[org.fukuii.types.BlockHeader.blockHeaderCodec]] splices them. A header
    * carrying a tail keeps it: `ethereum/go-ethereum-pow` @ `v1.10.26` appends
    * the fee-market field to its preimage when one is present and besu-etc does
    * the same, and no field beyond that one is reachable on a header a
    * proof-of-work network sealed.
    */
  def sealHash(header: BlockHeader): Hash =
    RlpCodec[BlockHeader].encode(header) match
      case RlpItem.Sequence(items) =>
        val at = BlockHeader.MandatoryFields - Seal.FieldCount
        Keccak256.hash(Rlp.encode(RlpItem.Sequence(items.patch(at, Vector.empty, Seal.FieldCount))))
      case _: RlpItem.Bytes =>
        throw new IllegalStateException("a block header encoded to a single element rather than to a sequence")

  /** Whether this header's seal is the one its own difficulty demands.
    *
    * ==Two independent checks, reported apart, which is what the field does==
    *
    * `ethereum/go-ethereum-pow` @ `v1.10.26` answers `errInvalidMixDigest` and
    * `errInvalidPoW` from the same method, and `besu-eth/besu-etc` @
    * `eb4248c99` logs the two failures separately. They are different faults --
    * one says the miner's own claimed mix is wrong, the other that the work is
    * insufficient -- and collapsing them to one boolean discards which. **The
    * two clients disagree about the ORDER they are checked in** and agree on
    * the verdict, which is what shows the order carries nothing.
    *
    * ==Rejecting the other seal case is an obligation this discharges==
    *
    * [[org.fukuii.types.Seal]] records that widening the seal to a sum moved a
    * refusal out of the decoder, so a network whose engine writes the two-slot
    * seal must positively reject the authority-round case rather than decline
    * to read it. This is a proof-of-work engine and the exhaustive match below
    * is where that obligation is met.
    *
    * @param cache
    *   the cache for this header's epoch. Its own epoch is checked rather than
    *   trusted: a cache from the wrong epoch produces a well-formed digest that
    *   matches nothing, which is indistinguishable from an invalid block and is
    *   not the same finding.
    */
  def verifySeal(header: BlockHeader, cache: EthashCache): Either[SealFault, EthashSolution] =
    header.seal match
      case Seal.AuthorityRound(_, _)            => Left(SealFault.WrongEngine)
      case Seal.MixHashAndNonce(mixHash, nonce) =>
        val number = header.number.toBigInt
        val epoch = epochOf(number)
        if cache.epoch != epoch then Left(SealFault.WrongEpoch(epoch, cache.epoch))
        else if header.difficulty.toBigInt <= 0 then Left(SealFault.NoDifficulty)
        else
          val answered = Ethash.evaluateLight(cache, Ethash.datasetSize(epoch), sealHash(header), nonce.toBytes)
          if answered.mixHash != mixHash then Left(SealFault.WrongMixHash(mixHash, answered.mixHash))
          else if !Ethash.clears(answered.result, header.difficulty.toBigInt) then
            Left(SealFault.AboveTarget(answered.result, header.difficulty.toBigInt))
          else Right(answered)

  /** How long the block took, refused where it did not follow its parent in
    * time.
    *
    * ==A broken precondition, and the caller is what upholds it==
    *
    * `ethereum/execution-specs` @ `ccaaaba58` refuses
    * `header.timestamp <= parent_header.timestamp` in `validate_header` and
    * calls `calculate_block_difficulty` several lines further down, so the
    * formula is stated only for a block that already passed. This is the same
    * division of labor [[ommerReward]] records for an ommer's age: validation
    * refuses, and the arithmetic assumes.
    *
    * **Refused rather than assumed, because the two arithmetics part here and
    * neither reports it.** Integer division truncates toward zero in this
    * language and floors away from it in the specification's, so a negative gap
    * would give a multiplier one larger here than the value every reference
    * implementation computes -- a plausible difficulty, differing from the
    * chain's, with nothing to distinguish it from the right one.
    */
  private def gapAfter(parent: BlockHeader, timestamp: UInt64): BigInt =
    val gap = timestamp.toBigInt - parent.timestamp.toBigInt
    if gap <= 0 then
      throw new IllegalStateException(
        "a block at " + timestamp.toBigInt.toString + " does not follow its parent at " +
          parent.timestamp.toBigInt.toString
      )
    else gap

  /** The signed figure one adjustment step is multiplied by, floored so that a
    * very long gap cannot collapse the difficulty in one block.
    *
    * EIP-2 states the floor's purpose outright -- it *"serves to ensure that the
    * difficulty does not fall extremely far if two blocks happen to be very far
    * apart in time due to a client security bug or other black-swan issue"*.
    */
  private def multiplier(raised: BigInt, gap: BigInt, gapDivisor: BigInt): BigInt =
    (raised - gap / gapDivisor).max(ProofOfWorkEngine.MultiplierFloor)

  /** The divisor sizing one adjustment step, refused where a rule set states
    * nothing to divide by.
    *
    * A zero would raise from the division itself, and it would raise naming
    * arithmetic rather than the rule set that supplied it. The rules are read
    * once per block, so asking here costs one comparison and answers with the
    * fact a reader needs.
    */
  private def boundDivisor(rules: ConsensusRules): BigInt =
    if rules.difficultyBoundDivisor <= 0 then
      throw new IllegalStateException(
        "a rule set states no difficulty adjustment step: " + rules.difficultyBoundDivisor.toString
      )
    else rules.difficultyBoundDivisor

  /** The exponential term, which doubles every period once it starts.
    *
    * ==The delay is subtracted from the block, not from the period==
    *
    * `ethereum/execution-specs` @ `ccaaaba58` computes
    * `((block_number - BOMB_DELAY_BLOCKS) // 100000) - 2` and raises two to that
    * power where it is not negative. Subtracting whole periods instead would
    * agree only where the delay is an exact multiple of the period -- which
    * every delay read for this build happens to be, so the two are
    * indistinguishable on the published corpus and part on the first delay that
    * is not.
    *
    * ==The delayed block is floored at zero rather than divided while negative==
    *
    * `ethereum/go-ethereum-pow` @ `v1.10.26` floors it, keeping
    * `fakeBlockNumber` at zero where the parent is below the delay. Dividing a
    * negative instead reaches the same answer here, but only because integer
    * division in this language truncates toward zero while the specification's
    * floors away from it, and the two quotients differ by one -- a coincidence
    * of both being discarded rather than a property to rely on.
    *
    * ==A period past what a header could hold is a broken precondition==
    *
    * Two raised to a period is unbounded, and a height far enough out would ask
    * for a power no machine can hold long before the result is rejected for
    * being too wide. `UInt256.MaxValue` is below two to the 256th, so a period
    * at or above that is already unreachable and is refused as a nonsense
    * height rather than computed -- the same bound, for the same reason,
    * [[winnerReward]] puts on its own exponent.
    *
    * **Nothing here is a sentinel for a network that removed the term.** A delay
    * of zero is no delay, and a network that removed it needs a rule this build
    * does not carry.
    */
  private def bomb(number: BigInt, delay: BigInt): BigInt =
    val delayed = (number - delay).max(BigInt(0))
    val periods = delayed / ProofOfWorkEngine.ExponentialPeriod - 2
    if periods < 0 then BigInt(0)
    else if periods >= ProofOfWorkEngine.WidestExponent then
      throw new IllegalStateException(
        "block " + number.toString + " asks for a difficulty term of two to the " + periods.toString
      )
    else BigInt(2).pow(periods.toInt)

  /** Which era `number` falls in, counting the first as zero.
    *
    * ECIP-1017 numbers its own eras from one and puts the first at *"blocks 1 -
    * 5,000,000"* and the second at *"blocks 5,000,001 - 10,000,000"*, so the
    * step lands on the block AFTER a multiple of the era length rather than on
    * it. Both surveyed implementations of the proposal carry that offset and
    * carry it differently: `besu-eth/besu-etc` @ `eb4248c99` computes
    * `(blockNumber - 1) % eraLength` and divides what is left, and
    * `openethereum/openethereum` @ `v3.0.1` subtracts one from the quotient on
    * an exact multiple. Both are `(number - 1) / eraLength`, and getting it
    * wrong pays the wrong amount for exactly one block per era.
    */
  private def eraAt(number: BigInt): BigInt =
    ecip1017EraLength match
      case None         => BigInt(0)
      case Some(length) => if number < 1 then BigInt(0) else (number - 1) / length

  /** What the block's own producer is paid before any ommer bonus.
    *
    * ==One division at the end, not one per era==
    *
    * `base * 4^era / 5^era` is what all three implementations compute, and
    * besu-etc's source states the identity it rests on --
    * *"MaxBlockReward _r_ * (4/5)**era == MaxBlockReward * (4**era) / (5**era)
    * since (q/d)**n == q**n / d**n"*. **Stepping the reward down era by era is
    * not the same arithmetic**: a base of three over three eras is one under a
    * single division and zero under three, because each step floors.
    *
    * ==The exponent is bounded, so a nonsense height cannot ask for a nonsense
    * power==
    *
    * Multiplying by four fifths drives any amount below one eventually, and
    * `base < 2^bitLength` puts that point below `bitLength * ln 2 / ln 1.25`,
    * which is under four bit lengths. Past that the answer is zero without
    * computing anything, so the largest power this raises is a few hundred for
    * an emission any network states. besu-etc reaches the same protection by
    * narrowing the era to an `int` and throwing where it does not fit.
    */
  private def winnerReward(base: BigInt, era: BigInt): BigInt =
    if era <= 0 then base
    else if era > base.bitLength * 4 then BigInt(0)
    else
      val steps = era.toInt
      base * ProofOfWorkEngine.DisinflationQuotient.pow(steps) / ProofOfWorkEngine.DisinflationDivisor.pow(steps)

  /** What the producer of an included ommer is paid.
    *
    * ==The two eras pay by different rules, and that is the proposal's own
    * wording rather than an optimization==
    *
    * ECIP-1017 gives its first era *"a reward of up to 7/8 of the winning block
    * reward (4.375ETC)"* for an ommer's miner, and its second *"a reward of
    * 1/32 (0.125ETC) ... the same value as the reward to the winning miner for
    * including the uncle(s)"*. So the age-scaled rule stops at the first era
    * boundary and a flat thirty-second replaces it. Both implementations of the
    * proposal branch at exactly that point --
    * `besu-eth/besu-etc` @ `eb4248c99` on `era < 1` and
    * `openethereum/openethereum` @ `v3.0.1` on `eras == 0`.
    *
    * The age-scaled rule is the one every proof-of-work client applies at every
    * height where no ladder is in force: `ethereum/execution-specs` @
    * `ccaaaba58` writes it `((8 - ommer_age) * BLOCK_REWARD) // 8` and
    * `ethereum/go-ethereum-pow` @ `v1.10.26` writes the same value as
    * `(uncle.Number + 8 - header.Number) * blockReward / 8`.
    *
    * ==An age outside the rule's range is a broken precondition==
    *
    * The rule is stated for an ommer strictly older than the block and no more
    * than eight behind it: at nine the numerator turns negative, and a negative
    * credit would wrap into a balance no chain agreed on rather than fail.
    * Validation is tighter still and settles it elsewhere -- `MAX_OMMER_DEPTH`
    * is six in `ethereum/execution-specs` @ `ccaaaba58`, which refuses a block
    * outside `1 <= ommer_age <= 6`, and besu's `MAX_GENERATION` is the same six
    * -- so an age this refuses is one no validated block carries.
    *
    * **The flat rule reads no age, so it has no range to check.** The numerator
    * that turns negative belongs to the age-scaled expression alone and the
    * guard sits with it. Both implementations of the proposal branch on the era
    * the same way and validate an age in neither branch:
    * `besu-eth/besu-etc` @ `eb4248c99` checks `MAX_GENERATION` in
    * `ClassicBlockProcessor.rewardCoinbase`, outside `calculateOmmerReward` and
    * for every era alike, and `ethereumclassic/core-geth` @ `4185df450` records
    * the same division of labor in its own source, noting of the reward it
    * takes per ommer that it *"[a]ssumes uncles have been validated and
    * limited"*.
    */
  private def ommerReward(winner: BigInt, era: BigInt, number: BigInt, ommerNumber: BigInt): BigInt =
    if era > 0 then winner / ProofOfWorkEngine.InclusionDivisor
    else
      val age = number - ommerNumber
      if age < 1 || age > ProofOfWorkEngine.OmmerRewardHorizon then
        throw new IllegalStateException(
          "an ommer " + age.toString + " blocks behind block " + number.toString +
            " is outside the range this emission is stated for"
        )
      (ProofOfWorkEngine.OmmerRewardHorizon - age) * winner / ProofOfWorkEngine.OmmerRewardHorizon

object ProofOfWorkEngine:

  /** ECIP-1017's era length on Ethereum Classic, in blocks.
    *
    * The proposal states it as *"Every Era will last for 5,000,000 blocks"*,
    * and it is a default rather than a constant everywhere it appears:
    * besu-etc's `DEFAULT_ERA_LENGTH` is what it falls back to when genesis
    * configuration names none, and core-geth ships a second chain configuration
    * setting it to 5,000. A network states its own.
    */
  val ClassicEraLength: BigInt = BigInt(5000000)

  /** What a winner is paid per ommer included, as a fraction of its own reward.
    *
    * The same thirty-second is the whole of the ommer's own reward from the
    * second ECIP-1017 era onward, which is what that proposal means by
    * equalizing the two.
    */
  private val InclusionDivisor: BigInt = BigInt(32)

  /** How far behind a block an ommer's reward is still stated for.
    *
    * Eight, and it is the divisor of the age-scaled rule as well as its
    * horizon: an ommer eight behind is paid nothing by the same expression that
    * pays one a block behind seven eighths.
    */
  private val OmmerRewardHorizon: BigInt = BigInt(8)

  /** The fraction of an era's reward the next era pays.
    *
    * ECIP-1017 states the step as *"reduced at a constant rate of 20% upon
    * entering a new Era"*, and core-geth names the pair
    * `DisinflationRateQuotient` and `DisinflationRateDivisor`.
    */
  private val DisinflationQuotient: BigInt = BigInt(4)

  private val DisinflationDivisor: BigInt = BigInt(5)

  /** The gap, in seconds, at or above which the original algorithm lowers the
    * difficulty instead of raising it.
    *
    * Thirteen, from two sources that do not derive from one another. EIP-2
    * quotes the rule it replaces as
    * *"`(1 if block_timestamp - parent_timestamp < 13 else -1)`"*, and
    * `ethereum/execution-specs` @ `ccaaaba58` compares
    * `block_timestamp < parent_timestamp + U256(13)` in
    * `forks/frontier/fork.py`. **The comparison is strict**, so a gap of exactly
    * thirteen lowers the difficulty; both sources agree on that and it is the
    * boundary an off-by-one moves.
    *
    * `openethereum/openethereum` @ `v3.0.1` reads it from
    * `EthashParams.duration_limit` rather than a constant. **One client of four
    * resolving it is thin evidence for a rule-set member**, which is why it sits
    * here and not on [[org.fukuii.chainspec.ConsensusRules]]; no network in this
    * project's scope varies it.
    */
  private val DurationLimit: BigInt = BigInt(13)

  /** What the gap is divided by under EIP-2's algorithm.
    *
    * Ten, from EIP-2's own item 4 and from
    * `ethereum/execution-specs` @ `ccaaaba58`'s `forks/homestead/fork.py`. It is
    * `EIP2DifficultyIncrementDivisor` in `ethereumclassic/core-geth` @
    * `4185df450` and `difficulty_increment_divisor` in OpenEthereum.
    */
  private val Eip2GapDivisor: BigInt = BigInt(10)

  /** What the gap is divided by under EIP-100's algorithm.
    *
    * Nine, from `ethereum/execution-specs` @ `ccaaaba58`'s
    * `forks/byzantium/fork.py`. It is `EIP100FDifficultyIncrementDivisor` in
    * core-geth and `metropolis_difficulty_increment_divisor` in OpenEthereum,
    * and the shortening of the interval is the half of EIP-100 that is easy to
    * miss beside the ommer term.
    */
  private val Eip100GapDivisor: BigInt = BigInt(9)

  /** How far one block may lower the difficulty, as a count of adjustment
    * steps.
    *
    * Ninety-nine below, which every surveyed implementation writes as a floor of
    * `-99` on the multiplier.
    */
  private val MultiplierFloor: BigInt = BigInt(-99)

  /** The difficulty no adjustment may take a block below.
    *
    * `ethereum/execution-specs` @ `ccaaaba58` declares
    * `MINIMUM_DIFFICULTY = Uint(131072)` in every fork module that adjusts a
    * difficulty, and `besu-eth/besu-etc` @ `eb4248c99` declares
    * `MINIMUM_DIFFICULTY = BigInteger.valueOf(131_072L)` in both of its
    * calculator classes.
    */
  private val MinimumDifficulty: BigInt = BigInt(131072)

  /** How many blocks one doubling of the exponential term lasts.
    *
    * `ethereum/execution-specs` @ `ccaaaba58` divides the block number by a
    * literal `100000`; `ethereumclassic/core-geth` @ `4185df450` names the same
    * figure `ExpDiffPeriod` and notes that ECIP-1010 reads it too.
    */
  private val ExponentialPeriod: BigInt = BigInt(100000)

  /** The period at which the exponential term already exceeds any difficulty a
    * header can carry.
    *
    * `org.fukuii.bytes.UInt256.MaxValue` is one below two to the 256th, so a
    * term at that power cannot be carried whatever it is added to.
    */
  private val WidestExponent: BigInt = BigInt(256)

/** Why a header's seal was refused.
  *
  * A sum rather than a boolean, because the surveyed clients distinguish these
  * and a caller diagnosing a rejected block needs which one it was: an
  * insufficient proof is a peer mining badly, a wrong mixed hash is a peer
  * lying about its own work, and the two below it are not the peer's fault at
  * all.
  */
enum SealFault:

  /** The header carries the other seal case, which this engine does not write.
    *
    * A refusal rather than an omission: see
    * [[org.fukuii.consensus.pow.ProofOfWorkEngine.verifySeal]] on why declining
    * to interpret it would leave an obligation undischarged.
    */
  case WrongEngine

  /** The header states no difficulty, so there is no bar for a result to clear.
    *
    * Both surveyed clients refuse this before dividing rather than after --
    * `Difficulty.Sign() <= 0` in the go-ethereum line and `isZero()` in besu-etc
    * -- because the division the target comes from is what would fail otherwise,
    * and it would fail naming arithmetic rather than the header.
    */
  case NoDifficulty

  /** The cache supplied belongs to a different epoch than the header does.
    *
    * Not a fact about the block. It is the one fault here that says the caller
    * is wrong, and it exists because the alternative is a well-formed answer
    * about nothing.
    */
  case WrongEpoch(headerEpoch: BigInt, cacheEpoch: BigInt)

  /** The header's own mixed hash is not the one its nonce produces. */
  case WrongMixHash(claimed: Hash, answered: Hash)

  /** The work is well-formed and insufficient for the difficulty claimed. */
  case AboveTarget(result: Hash, difficulty: BigInt)
