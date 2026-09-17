package org.fukuii.blockchaintests

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, UInt64}
import org.fukuii.consensus.{EngineRule, RuleNotRun}
import org.fukuii.evm.EvmFixtures
import org.fukuii.evm.fixtures.{ExpectedRejection, FixtureAccount}

/** The runner over three published cases, and each way a case must fail to
  * agree.
  *
  * ==The agreements are the release's; the failures are one change each==
  *
  * The three cases are [[PublishedParisCases]]'s, run exactly as published: the
  * runner verifies every hash and root they state as it goes, so an agreement
  * here is this build reproducing values the filling tool produced. Every
  * failure below changes one member of one of those cases, or the engine it runs
  * under, and nothing else, and asserts the reason as well as the outcome -- a
  * runner that diverged on everything would satisfy a bare count, and could not
  * tell a seeded defect from an unrelated breakage.
  *
  * ==What the controls are for==
  *
  * A published label that agrees everywhere reports zero divergences, and a
  * runner that had stopped comparing would report the same zero. **Each control
  * is a comparison the runner makes, shown to refuse**: a head that is not the
  * case's, a world holding some other state, a refusal under a name no
  * vocabulary holds, a refusal under a known name for another rule, a block
  * accepted where the case refuses it, a block stating a chain other than the
  * one the runner follows, a refusal with blocks after it that only running its
  * block reaches, a header named for a rule it does not break beside two it
  * does, and a refusal the rules the runner asks cannot reproduce. Two more are
  * rows of `BlockchainRunnerPropSpec`'s tables: a block that does not decode
  * under a name whose rule is not that, and a blob schedule some fork's rules
  * do not resolve.
  *
  * ==And the outcomes no published label reaches yet==
  *
  * A block left undecided and a block this build raises on appear in no label
  * this runner certifies, and the counts pinned there were written from its own
  * output. So each is reached here first, through an engine standing in for a
  * mechanism, and its outcome is asserted before any label depends on it.
  *
  * `BlockchainRunnerPropSpec` holds the comparisons whose variants differ only
  * by the value they state; [[BlockchainRunnerCases]] holds the cases and
  * variants both specs run.
  */
class BlockchainRunnerSpec extends AnyFlatSpec with BlockchainRunnerCases:

  "run" should "agree with a published chain whose last block reads the chain's hashes through BLOCKHASH" in {
    val result = BlockchainRunner.run(blockHash)
    assert(agreed(result) && result.blocksAccepted == 2 && result.refusalsAgreed == 0, result.toString)
  }

  it should "agree with a published block refused for its gas limit, under the name the case states" in {
    val result = BlockchainRunner.run(gasLimit)
    assert(agreed(result) && result.blocksAccepted == 0 && result.refusalsAgreed == 1, result.toString)
  }

  it should "agree with a published block refused for a transaction's signature, under the name the case states" in {
    val result = BlockchainRunner.run(signature)
    assert(agreed(result) && result.blocksAccepted == 0 && result.refusalsAgreed == 1, result.toString)
  }

  it should "count no refusal for a refused block it did not refuse under a stated name" in {
    val result = BlockchainRunner.run(
      withRefusal(refusedEncoding, ExpectedRejection(Set("A.NAME_NOT_HELD")), Some(hashOf(refusedEncoding)))
    )
    assert(result.refusalsAgreed == 0, result.toString)
  }

  it should "count the blocks accepted before a later comparison diverges" in {
    val result = BlockchainRunner.run(blockHash.copy(lastBlockHash = Wrong))
    assert(result.blocksAccepted == 2, "both blocks were accepted before the head was compared: " + result.toString)
  }

  "the genesis" should "diverge where its encoding hashes to some other hash than the case states" in {
    val result = BlockchainRunner.run(blockHash.copy(genesisHash = Wrong))
    assert(divergedFor(result, "the genesis hashes to"), result.toString)
  }

  it should "diverge where the pre-state seeds some other root than the genesis commits to" in {
    val extra = EvmFixtures.address(0x7e) -> FixtureAccount(BigInt(0), BigInt(1), Bytes.Empty, Map.empty)
    val result = BlockchainRunner.run(blockHash.copy(pre = blockHash.pre + extra))
    assert(divergedFor(result, "the pre-state seeds root"), result.toString)
  }

  it should "diverge where its encoding does not decode" in {
    val result = BlockchainRunner.run(blockHash.copy(genesisRlp = Undecodable))
    assert(divergedFor(result, "the genesis does not decode"), result.toString)
  }

  "a block the case accepts" should "diverge where its encoding hashes to some other hash than the case states" in {
    val (rlp, _) = firstAccepted
    val result =
      BlockchainRunner.run(blockHash.copy(blocks = blockHash.blocks.updated(0, StatedBlock.Valid(rlp, Wrong))))
    assert(divergedFor(result, "block 0 hashes to"), result.toString)
  }

  it should "diverge where its encoding does not decode" in {
    val restated = blockHash.blocks.updated(0, StatedBlock.Valid(Undecodable, Wrong))
    val result = BlockchainRunner.run(blockHash.copy(blocks = restated))
    assert(divergedFor(result, "block 0 does not decode"), result.toString)
  }

  it should "diverge where this build refuses it, naming the fault" in {
    // The gas-limit case's refused block, stated as one the case accepts. A
    // runner reporting this build's refusal as anything but a divergence would
    // be folding a refusal into agreement.
    val restated = Vector(StatedBlock.Valid(refusedEncoding, hashOf(refusedEncoding)))
    val result = BlockchainRunner.run(gasLimit.copy(blocks = restated))
    assert(divergedFor(result, "GasLimitOutOfBounds"), result.toString)
  }

  it should "be undecided, naming the block and the rule, where it reaches a rule its engine does not run" in {
    val result = BlockchainRunner.run(blockHash, runningUnder(leavesTheSealUnrun))
    val expected = BlockchainVerdict.Undecided(0, RuleNotRun.EngineRules(Set(EngineRule.Seal)))
    assert(result.verdict == expected && result.blocksAccepted == 0, result.toString)
  }

  "a block the case refuses" should "diverge where its encoding hashes to some other hash than the decoded form the case publishes" in {
    val result = BlockchainRunner.run(withRefusal(refusedEncoding, GasLimitName, Some(Wrong)))
    assert(divergedFor(result, "block 0 hashes to") && result.refusalsAgreed == 0, result.toString)
  }

  it should "agree by name alone where the case publishes no decoded form and this build decodes the block" in {
    val result = BlockchainRunner.run(withRefusal(refusedEncoding, GasLimitName, None))
    assert(agreed(result) && result.refusalsAgreed == 1, result.toString)
  }

  it should "diverge where the case publishes no decoded form and this build refuses the block under another name" in {
    val baseFee = ExpectedRejection(Set("BlockException.INVALID_BASEFEE_PER_GAS"))
    val result = BlockchainRunner.run(withRefusal(refusedEncoding, baseFee, None))
    assert(divergedFor(result, "was refused as Header(GasLimitOutOfBounds"), result.toString)
  }

  it should "be undecided, naming the block and the rule, where it reaches a rule its engine does not run" in {
    val result = BlockchainRunner.run(firstBlockRefused, runningUnder(leavesTheSealUnrun))
    val expected = BlockchainVerdict.Undecided(0, RuleNotRun.EngineRules(Set(EngineRule.Seal)))
    assert(result.verdict == expected && result.refusalsAgreed == 0, result.toString)
  }

  it should "diverge, naming both reasons, where the header rules it runs do not reproduce the validator's" in {
    val result = BlockchainRunner.run(firstBlockRefused, runningUnder(refusingDifferentlyEachTime))
    assert(
      divergedFor(
        result,
        "was refused as ExtraDataAboveLimit(1,0), which the header rules it runs reproduce as " +
          "ExtraDataAboveLimit(2,0)"
      ) && result.refusalsAgreed == 0,
      result.toString
    )
  }

  "a block the case refuses with blocks after it" should "be refused in place where its refusal comes before it runs" in {
    val sameTime = secondBlockWith(_.copy(timestamp = firstBlockHeader.timestamp))
    val result = BlockchainRunner.run(
      refusingBetween(sameTime, ExpectedRejection(Set("InvalidTimestampOlderParent"))),
      filledBy = FillingTool.Retesteth
    )
    assert(agreed(result) && result.blocksAccepted == 2 && result.refusalsAgreed == 1, result.toString)
  }

  it should "diverge, naming the order, where only running its block refuses it" in {
    val wrongRoot = secondBlockWith(_.copy(stateRoot = Wrong))
    val result = BlockchainRunner.run(
      refusingBetween(wrongRoot, ExpectedRejection(Set("InvalidStateRoot"))),
      filledBy = FillingTool.Retesteth
    )
    assert(divergedFor(result, "after it ran"), result.toString)
  }

  it should "diverge, naming both roots, where refusing it moved the world" in {
    val wrongRoot = secondBlockWith(_.copy(stateRoot = Wrong))
    val result = BlockchainRunner.run(
      refusingBetween(wrongRoot, ExpectedRejection(Set("InvalidStateRoot"))),
      filledBy = FillingTool.Retesteth
    )
    assert(divergedFor(result, "moved the world from root"), result.toString)
  }

  it should "diverge where its refusal is under a name the case does not state" in {
    val sameTime = secondBlockWith(_.copy(timestamp = firstBlockHeader.timestamp))
    val result = BlockchainRunner.run(
      refusingBetween(sameTime, ExpectedRejection(Set("InvalidNumber"))),
      filledBy = FillingTool.Retesteth
    )
    assert(
      divergedFor(result, "was refused as Header(TimestampNotAfterParent") && !divergedFor(result, "names none of"),
      result.toString
    )
  }

  it should "diverge where its refusal is under a name another tool writes, which the case's corpus does not hold" in {
    // The refusal the first test in this group agrees with, stated in the names
    // of a corpus that does not write that name: a runner comparing names
    // without asking whose they are would agree here.
    val sameTime = secondBlockWith(_.copy(timestamp = firstBlockHeader.timestamp))
    val result = BlockchainRunner.run(
      refusingBetween(sameTime, ExpectedRejection(Set("InvalidTimestampOlderParent"))),
      filledBy = FillingTool.Testeth
    )
    assert(divergedFor(result, "names none of InvalidTimestampOlderParent"), result.toString)
  }

  it should "agree where it also breaks the rule the case names, after another rule refused it first" in {
    val result = BlockchainRunner.run(refusingBetween(breakingTwoRules, GasLimitName))
    assert(agreed(result) && result.refusalsAgreed == 1 && result.blocksAccepted == 2, result.toString)
  }

  it should "diverge where the case names a rule it does not break, whichever rules it does break" in {
    val baseFee = ExpectedRejection(Set("BlockException.INVALID_BASEFEE_PER_GAS"))
    val result = BlockchainRunner.run(refusingBetween(breakingTwoRules, baseFee))
    assert(divergedFor(result, "was refused as Header(TimestampNotAfterParent"), result.toString)
  }

  "a case" should "be skipped, by name, where it names a network no rules are resolved for" in {
    val result = BlockchainRunner.run(blockHash.copy(network = "NotAPublishedNetwork"))
    assert(skippedNaming(result, "NotAPublishedNetwork"), result.toString)
  }

  it should "diverge, naming the block and the chain, where a block states a chain other than the default" in {
    val result = BlockchainRunner.run(blockHash.copy(otherChains = Vector(1 -> "B")))
    assert(
      divergedFor(result, "block 1 states the chain B") && result.blocksAccepted == 0,
      "a case following two chains is not run as one: " + result.toString
    )
  }

  it should "run as its network's chain id where it states no config" in {
    val result = BlockchainRunner.run(blockHash.copy(config = None))
    assert(agreed(result) && result.blocksAccepted == 2, result.toString)
  }

  it should "diverge, naming the seal engine, before any block runs, where it states one this runner does not run" in {
    val result = BlockchainRunner.run(blockHash.copy(sealEngine = Some("Ethash")))
    assert(
      divergedFor(result, "states the seal engine Ethash") && result.blocksAccepted == 0,
      "a case sealed by a proof this build does not verify is not run as one sealed without proof: " + result.toString
    )
  }

  it should "diverge where it states no seal engine" in {
    val result = BlockchainRunner.run(blockHash.copy(sealEngine = None))
    assert(divergedFor(result, "states no seal engine") && result.blocksAccepted == 0, result.toString)
  }

  it should "diverge where it states another chain id than its network runs as" in {
    val result = BlockchainRunner.run(blockHash.copy(config = Some(config.copy(chainId = Some(UInt64.fromBits(5L))))))
    assert(divergedFor(result, "chain id 5"), result.toString)
  }

  it should "diverge, naming the key, where its config states a key this runner does not compare" in {
    val result =
      BlockchainRunner.run(blockHash.copy(config = Some(config.copy(otherKeys = Vector("NOT_A_PUBLISHED_KEY")))))
    assert(divergedFor(result, "config states NOT_A_PUBLISHED_KEY"), result.toString)
  }

  it should "diverge where its config names another network than the case does" in {
    val result = BlockchainRunner.run(blockHash.copy(config = Some(config.copy(network = Some("Shanghai")))))
    assert(divergedFor(result, "names the network Shanghai"), result.toString)
  }

  it should "diverge, naming what was raised, where a block part-way through raises" in {
    val result = BlockchainRunner.run(blockHash, runningUnder(raisesAtTheSecondBlock))
    assert(
      divergedFor(result, "a settlement that raises at block 2") && result.blocksAccepted == 1,
      "the first block was accepted and the second raised: " + result.toString
    )
  }

  "the controls" should "diverge where the case's last block hash is not the head" in {
    val result = BlockchainRunner.run(blockHash.copy(lastBlockHash = Wrong))
    assert(divergedFor(result, "last block hash"), "the head comparison is what a wrong lastblockhash must reach")
  }

  it should "diverge where a case accepting no block states a last block hash other than its genesis" in {
    val result = BlockchainRunner.run(gasLimit.copy(lastBlockHash = Wrong))
    assert(divergedFor(result, "last block hash"), result.toString)
  }

  it should "diverge where the post-state states an account holding other than the chain's" in {
    val result = BlockchainRunner.run(withRicherAccount(blockHash))
    assert(divergedFor(result, "balance") && divergedFor(result, "root of the post-state"), result.toString)
  }

  it should "diverge where the post-state omits an account the chain created" in {
    // Only the root comparison reaches this: the account is in neither the
    // pre-state nor the post-state as stated, so a comparison over listed
    // accounts sees nothing wrong with an account it was never told about.
    val created = (blockHash.postState.keySet -- blockHash.pre.keySet).toVector.sortBy(_.toHex)
    val result = BlockchainRunner.run(blockHash.copy(postState = blockHash.postState -- created.take(1)))
    assert(created.nonEmpty && divergedFor(result, "root of the post-state"), created.toString + " " + result.toString)
  }

  it should "diverge where a refusal is stated under a name no vocabulary holds" in {
    val unpublished = ExpectedRejection(Set("BlockException.NOT_A_PUBLISHED_NAME"))
    val result = BlockchainRunner.run(withRefusal(refusedEncoding, unpublished, Some(hashOf(refusedEncoding))))
    assert(divergedFor(result, "names none of BlockException.NOT_A_PUBLISHED_NAME"), result.toString)
  }

  it should "diverge where a refusal is stated under a known name for another rule" in {
    // The gas-limit case's block is refused for its gas limit, and is stated
    // here as refused for its base fee -- a name the vocabulary holds, so only a
    // comparison against the RULE the name maps to can refuse the agreement.
    val baseFee = ExpectedRejection(Set("BlockException.INVALID_BASEFEE_PER_GAS"))
    val result = BlockchainRunner.run(withRefusal(refusedEncoding, baseFee, Some(hashOf(refusedEncoding))))
    assert(
      divergedFor(result, "was refused as Header(GasLimitOutOfBounds") && !divergedFor(result, "names none of"),
      result.toString
    )
  }

  it should "diverge where a block the case refuses is accepted" in {
    val result = BlockchainRunner.run(firstBlockRefused)
    assert(divergedFor(result, "was accepted"), result.toString)
  }
