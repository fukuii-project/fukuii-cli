package org.fukuii.consensus.pow.certification

import org.fukuii.bytes.{Address, Bytes, UInt256, UInt64}
import org.fukuii.chainspec.ConsensusRules
import org.fukuii.consensus.pow.ProofOfWorkEngine
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{BlockHeader, BlockNonce, Bloom, Seal}

/** What the two reward tiers settle a block with.
  *
  * ==One transcription of the block shape, however many tiers read it==
  *
  * Both tiers build an ommer that the emission reads two fields of, and a third
  * copy of a fourteen-field header is the defect shape
  * [[org.fukuii.consensus.pow.ProofOfWorkEngine.sealHash]] records from a client
  * that wrote one field list out twice. The parameters below are shared for the
  * same reason: two tiers disagreeing about the era length would each be
  * internally consistent and jointly meaningless.
  *
  * ==The parameters are stated here, not read off a schedule==
  *
  * Reading them from a network configuration would ask the schedule what it
  * says and then check the corpus against its own answer, which is true of any
  * schedule whatsoever. They are stated with their sources instead, so a
  * disagreement is between this file and the chain rather than inside one loop.
  */
object ClassicRewardHarness:

  /** The emission the first era pays, before any reduction.
    *
    * Five ether in wei. `ethereumclassic/core-geth` @ `4185df450` names it
    * `vars.FrontierBlockReward` and its `ecip1017BlockReward` takes that value
    * as the base the era ladder reduces, so the ladder's starting figure is the
    * emission this chain launched with rather than one the proposal introduced.
    */
  val LaunchReward: BigInt = BigInt(5) * BigInt(10).pow(18)

  /** How many blocks one era lasts on this chain.
    *
    * `ECIP1017EraRounds` in the same client's `ClassicChainConfig`, which is
    * also what ECIP-1017 states as *"Every Era will last for 5,000,000
    * blocks"*.
    */
  val EraLength: BigInt = BigInt(5000000)

  /** The engine under certification, carrying this chain's era ladder.
    *
    * No ECIP-1099 activation: the epoch length settles which dataset a seal is
    * checked against and reaches no emission, so an engine carrying one would
    * credit identically at every case here.
    */
  val engine: ProofOfWorkEngine = ProofOfWorkEngine(ecip1017EraLength = Some(EraLength))

  /** An engine that never steps its emission down, which is what a network
    * declining ECIP-1017 runs.
    *
    * The negative control for both reward tiers: era zero pays what this pays,
    * so a suite it satisfies everywhere is a suite blind to the ladder.
    */
  val withoutLadder: ProofOfWorkEngine = ProofOfWorkEngine()

  /** The rule set the emission is resolved from.
    *
    * `zeroRewardCreditsBeneficiary` is left false because no case here resolves
    * an emission of nothing; a network that did would be asking a question this
    * corpus does not state.
    */
  val rules: ConsensusRules =
    ConsensusRules.Unrewarded.copy(
      blockReward = UInt256
        .fromBigInt(LaunchReward)
        .getOrElse(throw new IllegalStateException("the launch emission does not fit a header's width"))
    )

  /** An ommer at `number`, credited to `miner`.
    *
    * Every field the emission does not read is left at its zero rather than
    * invented, for the reason [[DifficultyCorpus.parentOf]] gives: a plausible
    * value in a field nothing reads would suggest the corpus stated one.
    */
  def ommerAt(number: BigInt, miner: Address): BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = EvmFixtures.hash(0),
      beneficiary = miner,
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = UInt256.Zero,
      number = UInt64
        .fromBigInt(number)
        .getOrElse(throw new IllegalStateException("an ommer height a header cannot carry: " + number.toString)),
      gasLimit = UInt64.Zero,
      gasUsed = UInt64.Zero,
      timestamp = UInt64.Zero,
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(mixHash = EvmFixtures.hash(0), nonce = BlockNonce.Zero)
    )

  /** What a block of this shape credits, by address.
    *
    * Balances are read back rather than derived, so the figure compared is the
    * one the engine actually wrote into a world.
    */
  def credits(
      settling: ProofOfWorkEngine,
      beneficiary: Address,
      number: BigInt,
      ommers: Seq[BlockHeader]
  ): Map[Address, BigInt] =
    val world = new EvmFixtures.MapWorldState
    settling.settlement(rules, beneficiary, number, ommers)(world)
    world.balances.view.mapValues(_.toBigInt).toMap
