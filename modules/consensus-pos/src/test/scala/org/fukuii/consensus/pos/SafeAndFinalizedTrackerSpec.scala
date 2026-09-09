package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

/** What the tracker holds, and the one distinction a simpler one would lose.
  *
  * ==A tracker that collapsed absent into zero would pass most of this==
  *
  * Storing two hashes and answering them is nearly untestable: any
  * implementation that keeps what it is given passes. The assertions that
  * discriminate are the ones about the state before anything was recorded, and
  * about the zero hash being a value the consensus layer said rather than the
  * absence of one.
  */
class SafeAndFinalizedTrackerSpec extends AnyFlatSpec:

  private val finalizedHash = PosFixtures.hash(0xf1)
  private val safeHash = PosFixtures.hash(0x5a)
  private val noBlock = SafeAndFinalizedTracker.NoBlock

  private def recorded(finalized: org.fukuii.bytes.Hash, safe: org.fukuii.bytes.Hash): SafeAndFinalizedTracker =
    val tracker = InMemorySafeAndFinalizedTracker()
    tracker.record(finalized, safe)
    tracker

  "A tracker that has been told nothing" should "answer nothing for finalized" in
    assert(
      InMemorySafeAndFinalizedTracker().finalized.isEmpty,
      "a node before its first forkchoice call has been told nothing, which is not the same as being told none"
    )

  it should "answer nothing for safe" in
    assert(InMemorySafeAndFinalizedTracker().safe.isEmpty, "the same reading applies to both")

  it should "report no finalized block" in
    assert(
      !InMemorySafeAndFinalizedTracker().hasFinalizedBlock,
      "never told and told none fold together here, which is what the convenience is for"
    )

  "A tracker told a real pair" should "answer the finalized hash it was given" in
    assert(recorded(finalizedHash, safeHash).finalized.contains(finalizedHash), "it holds what it was told")

  it should "answer the safe hash it was given" in
    assert(recorded(finalizedHash, safeHash).safe.contains(safeHash), "it holds what it was told")

  it should "keep the two apart" in
    assert(
      recorded(finalizedHash, safeHash).finalized != recorded(finalizedHash, safeHash).safe,
      "transposing the pair is the defect a tracker of two same-typed hashes most easily hides"
    )

  it should "report a finalized block" in
    assert(recorded(finalizedHash, safeHash).hasFinalizedBlock, "a hash that is not the zero one names a block")

  it should "report a safe block" in
    assert(recorded(finalizedHash, safeHash).hasSafeBlock, "the same reading applies to both")

  "A tracker told the zero hash" should "distinguish that from having been told nothing" in
    assert(
      recorded(noBlock, noBlock).finalized.contains(noBlock),
      "the protocol's way of saying nothing is finalized yet is a value, and the tracker was told it"
    )

  it should "report no finalized block even though it was told something" in
    assert(
      !recorded(noBlock, noBlock).hasFinalizedBlock,
      "the zero hash names no block, which is the whole reason the convenience differs from the reader"
    )

  it should "report no safe block even though it was told something" in
    assert(!recorded(noBlock, noBlock).hasSafeBlock, "the same reading applies to both")

  "A tracker told twice" should "answer the most recent finalized hash" in {
    val tracker = InMemorySafeAndFinalizedTracker()
    tracker.record(noBlock, noBlock)
    tracker.record(finalizedHash, safeHash)
    assert(tracker.finalized.contains(finalizedHash), "a later forkchoice call supersedes an earlier one")
  }

  it should "answer the most recent safe hash" in {
    val tracker = InMemorySafeAndFinalizedTracker()
    tracker.record(finalizedHash, safeHash)
    tracker.record(noBlock, noBlock)
    assert(tracker.safe.contains(noBlock), "a consensus layer may retract, and the newest answer is the answer")
  }

  "SafeAndFinalizedTracker.NoBlock" should "be thirty-two zero bytes" in
    assert(
      noBlock.toBytes.length == 32 && noBlock.toBytes.forall(_ == 0.toByte),
      "the protocol names no block with a hash of zeros, and this is derived rather than written out"
    )

  /** ==The gap, pinned as behavior rather than left in prose==
    *
    * The specification requires the safe block to be the head or an ancestor of
    * it, and nothing here can tell. Two unrelated hashes are accepted because
    * there is no relationship between two hashes to reject — which is the
    * absence the port's own documentation argues for, stated where a later
    * change would have to notice it.
    *
    * If something here ever does check, this expectation fails and whoever
    * added the check decides deliberately rather than discovering that a port
    * quietly grew a requirement.
    */
  "A tracker" should "accept two hashes with no relationship between them" in {
    val tracker = InMemorySafeAndFinalizedTracker()
    tracker.record(PosFixtures.hash(0x01), PosFixtures.hash(0xfe))
    assert(
      tracker.finalized.contains(PosFixtures.hash(0x01)) && tracker.safe.contains(PosFixtures.hash(0xfe)),
      "ancestry is a question about a chain, and this holds hashes, so it checks nothing about the invariant"
    )
  }
