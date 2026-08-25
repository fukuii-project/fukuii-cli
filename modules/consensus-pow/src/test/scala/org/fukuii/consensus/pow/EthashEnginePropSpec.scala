package org.fukuii.consensus.pow

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

import org.fukuii.bytes.{Address, UInt256}
import org.fukuii.chainspec.ConsensusRules
import org.fukuii.evm.{EvmFixtures, Word}

/** ECIP-1017's emission schedule, at the amounts and heights the proposal
  * states.
  *
  * ==This is the vector table [[EthashEngineSpec]] deliberately does not
  * hold==
  *
  * That suite settles the shape of the credit -- who is paid, from which
  * account it comes into being, which precondition refuses a block -- at
  * amounts small enough to read. This one settles the numbers, and the numbers
  * only mean anything at the scale a network actually pays, where an era's
  * reward is an eighteen-digit figure and the difference between two adjacent
  * eras is larger than most balances.
  *
  * ==Where the rows come from==
  *
  * The proposal itself. ECIP-1017 § *Specification* states its first era as
  * *"blocks 1 - 5,000,000"* paying *"a 'static' block reward for the winning
  * block of 5 ETC"*, its second as *"blocks 5,000,001 - 10,000,000"* paying
  * 4 ETC, and every era after as *"reduced at a constant rate of 20% upon
  * entering a new Era"* with *"Every Era will last for 5,000,000 blocks"*. The
  * later rows are that rate applied, and two implementations compute the same
  * figures from `4^era / 5^era`: `besu-eth/besu-etc` @ `eb4248c99` in
  * `ClassicBlockProcessor.getBlockWinnerRewardByEra` and
  * `openethereum/openethereum` @ `v3.0.1` in `ecip1017_eras_block_reward`.
  *
  * **The boundary rows are the ones that would be wrong under the obvious
  * reading.** An era ending on a multiple and the next beginning after it means
  * the last block of an era is a multiple of the era length, which is where an
  * off-by-one pays a whole era's difference for exactly one block.
  */
class EthashEnginePropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val beneficiary: Address = EvmFixtures.address(0x33)

  /** Five ether in wei, which is what ECIP-1017's first era pays. */
  private val launchReward: BigInt = BigInt(5) * BigInt(10).pow(18)

  /** The era length the proposal states, in blocks. */
  private val eraLength: BigInt = BigInt(5000000)

  private val classic: EthashEngine = EthashEngine(Some(eraLength))

  /** The state left by settling `number` on a network that does not bring an
    * account into being by crediting it nothing.
    *
    * The world itself is returned rather than a figure, because the two
    * questions asked of it below are not both answerable from a balance: an
    * account written a zero and an account never written both hold nothing.
    */
  private def settling(engine: EthashEngine, reward: BigInt, number: BigInt): EvmFixtures.MapWorldState =
    val world = new EvmFixtures.MapWorldState
    val consensus = ConsensusRules.Unrewarded.copy(
      blockReward = UInt256.fromBigInt(reward).toOption.get,
      zeroRewardCreditsBeneficiary = false
    )
    engine.settlement(consensus, beneficiary, number, Seq.empty)(world)
    world

  private def paid(engine: EthashEngine, reward: BigInt, number: BigInt): BigInt =
    settling(engine, reward, number).balanceOf(beneficiary).toBigInt

  /** Thousandths of an ether, as wei. Every reward on this ladder is exact at
    * this scale for the eras a row below names, so a figure reads as the
    * proposal writes it rather than as a power of ten.
    */
  private def milliether(thousandths: Long): BigInt = BigInt(thousandths) * BigInt(10).pow(15)

  private val schedule = Table(
    ("what the height is", "block", "winner's reward"),
    ("the first block of the first era", BigInt(1), milliether(5000)),
    ("a block inside the first era", BigInt(2500000), milliether(5000)),
    ("the last block of the first era", eraLength, milliether(5000)),
    ("the first block of the second era", eraLength + 1, milliether(4000)),
    ("the last block of the second era", eraLength * 2, milliether(4000)),
    ("the first block of the third era", eraLength * 2 + 1, milliether(3200)),
    ("the first block of the fourth era", eraLength * 3 + 1, milliether(2560)),
    ("the first block of the fifth era", eraLength * 4 + 1, milliether(2048))
  )

  property("ECIP-1017 pays each era four fifths of the era before it") {
    forAll(schedule) { (what: String, block: BigInt, expected: BigInt) =>
      val credited = paid(classic, launchReward, block)
      assert(
        credited == expected,
        what + ", block " + block.toString + ", paid " + credited.toString + " rather than " + expected.toString
      )
    }
  }

  private val unladdered = Table(
    ("what the height is", "block"),
    ("genesis", BigInt(0)),
    ("the first block", BigInt(1)),
    ("a height past several of Ethereum Classic's era boundaries", eraLength * 4 + 1)
  )

  property("an engine with no era length pays the resolved amount at every height") {
    forAll(unladdered) { (what: String, block: BigInt) =>
      assert(
        paid(EthashEngine(), launchReward, block) == launchReward,
        what + " changed the amount on a network that adopted no era ladder"
      )
    }
  }

  private val exhaustion = Table(
    ("what the height is", "block"),
    ("the first era where four fifths drives five ether to nothing by arithmetic", eraLength * 193 + 1),
    ("far enough that four fifths has driven five ether below one wei", eraLength * 400 + 1),
    ("further still, where the exponent would be ruinous to compute", eraLength * 4000000 + 1)
  )

  property("ECIP-1017 pays nothing once the ladder has exhausted the amount") {
    forAll(exhaustion) { (what: String, block: BigInt) =>
      assert(
        paid(classic, launchReward, block) == BigInt(0),
        what + " did not answer zero, which is what a fixed-supply schedule means at its tail"
      )
    }
  }

  property("ECIP-1017 brings nobody into being once the ladder has exhausted the amount") {
    forAll(exhaustion) { (what: String, block: BigInt) =>
      assert(
        !settling(classic, launchReward, block).accountExists(beneficiary),
        what + " brought the beneficiary into being on a network that does not credit a reward of zero"
      )
    }
  }
