package org.fukuii.evm

import org.scalatest.flatspec.AnyFlatSpec

/** Holding writes back so a failed invocation can drop them.
  *
  * The shape is `ethereum/execution-specs` at `ccaaaba58`:
  * `state_tracker.copy_tx_state` and `restore_tx_state`, called from
  * `vm/interpreter.py`'s `process_message` around the run of a frame and
  * applied when it ends in error.
  */
class JournaledWorldStateSpec extends AnyFlatSpec:

  private val owner = EvmFixtures.address(0x22)

  private val slot = EvmFixtures.word(1)

  "a held write" should "be visible through the journal that made it" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    assert(journal.storageAt(owner, slot) == EvmFixtures.word(42), "an invocation reads what it has written")
  }

  it should "not have reached the state beneath" in {
    val base = new EvmFixtures.MapWorldState
    new JournaledWorldState(base).setStorage(owner, slot, EvmFixtures.word(42))
    assert(base.storageAt(owner, slot) == Word.Zero, "a write that reached the state beneath could not be dropped")
  }

  "a read of something never written" should "fall through to the state beneath" in {
    val base = new EvmFixtures.MapWorldState
    base.setStorage(owner, slot, EvmFixtures.word(7))
    assert(
      new JournaledWorldState(base).storageAt(owner, slot) == EvmFixtures.word(7),
      "the journal is a layer, not a replacement"
    )
  }

  "a balance" should "be read from the state beneath" in {
    val base = new EvmFixtures.MapWorldState
    base.balances(owner) = EvmFixtures.word(1234)
    assert(
      new JournaledWorldState(base).balanceOf(owner) == EvmFixtures.word(1234),
      "nothing here writes a balance yet"
    )
  }

  "code" should "be read from the state beneath" in {
    val base = new EvmFixtures.MapWorldState
    base.codes(owner) = EvmFixtures.bytesOf("6001")
    assert(new JournaledWorldState(base).codeOf(owner) == EvmFixtures.bytesOf("6001"), "nothing here writes code yet")
  }

  "a restore" should "drop a write made after the snapshot" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    val taken = journal.snapshot()
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    journal.restore(taken)
    assert(journal.storageAt(owner, slot) == Word.Zero, "the failed invocation's write is gone, not reversed")
  }

  it should "keep a write made before the snapshot" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.setStorage(owner, slot, EvmFixtures.word(7))
    val taken = journal.snapshot()
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    journal.restore(taken)
    assert(journal.storageAt(owner, slot) == EvmFixtures.word(7), "only what the failed invocation did is undone")
  }

  it should "leave the state beneath untouched" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    val taken = journal.snapshot()
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    journal.restore(taken)
    assert(base.storageAt(owner, slot) == Word.Zero, "nothing reached it to be undone")
  }

  "a commit" should "pass every held write down" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    journal.commit()
    assert(base.storageAt(owner, slot) == EvmFixtures.word(42), "committing is what makes a write reach a state root")
  }

  it should "pass a zero down as a zero" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    journal.setStorage(owner, slot, Word.Zero)
    journal.commit()
    assert(
      base.slots.get((owner, slot)).contains(Word.Zero),
      "turning a zero into a removal belongs to the layer that reaches the trie, not to this one"
    )
  }

  it should "stop holding what it passed down" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    journal.commit()
    base.setStorage(owner, slot, EvmFixtures.word(7))
    assert(journal.storageAt(owner, slot) == EvmFixtures.word(7), "a write still held would shadow the state beneath")
  }
