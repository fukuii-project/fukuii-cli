package org.fukuii.consensus.pow

import org.fukuii.bytes.Address
import org.fukuii.chainspec.ConsensusRules
import org.fukuii.consensus.ConsensusEngine
import org.fukuii.evm.WorldState
import org.fukuii.types.BlockHeader

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
  */
final case class ProofOfWorkEngine(ecip1017EraLength: Option[BigInt] = None) extends ConsensusEngine:

  /** Credits the block's beneficiary, then each ommer's.
    *
    * ==The zero case is asked of each amount credited, not of the amount the
    * fork resolved==
    *
    * [[org.fukuii.consensus.ConsensusEngine.credit]] brings an account into
    * being, so whoever calls it decides the zero case -- and what this
    * mechanism credits is not what the fork resolved. ECIP-1017 steps the
    * winner's reward down until it reaches nothing, and every other figure here
    * is a fraction of that stepped-down amount, so a resolved reward that is
    * not zero still pays zero once the ladder has exhausted it. The question
    * [[org.fukuii.chainspec.ConsensusRules.zeroRewardCreditsBeneficiary]] asks
    * is therefore asked once per credit, of the figure that credit writes.
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
      creditIfDue(world, rules, beneficiary, winner + winner / ProofOfWorkEngine.InclusionDivisor * ommers.size)
      ommers.foreach(ommer =>
        creditIfDue(world, rules, ommer.beneficiary, ommerReward(winner, era, number, ommer.number.toBigInt))
      )

  /** Credits `to` unless the amount is nothing and this network declines to
    * bring an account into being by crediting it nothing.
    */
  private def creditIfDue(world: WorldState, rules: ConsensusRules, to: Address, amount: BigInt): Unit =
    if amount != 0 || rules.zeroRewardCreditsBeneficiary then credit(world, to, amount)

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
