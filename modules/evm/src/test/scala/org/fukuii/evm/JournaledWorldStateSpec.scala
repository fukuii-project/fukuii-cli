package org.fukuii.evm

import org.fukuii.bytes.UInt64
import org.scalatest.flatspec.AnyFlatSpec

/** Holding writes back so a failed invocation can drop them.
  *
  * The shape is `ethereum/execution-specs` at `ccaaaba58`:
  * `state_tracker.copy_tx_state` and `restore_tx_state`, called from
  * `vm/interpreter.py`'s `process_message` around the run of a frame and
  * applied when it ends in error.
  *
  * ==One keyspace here is held for the undo and NOT for the commit==
  *
  * The transient cases at the foot are what makes that difference a property
  * rather than a description: every other kind of write is asserted to reach
  * the state beneath, and this one is asserted not to. [[TransientStorageSpec]]
  * asserts the same rule through the two operations that use it; this asserts
  * it against the type that keeps it, which is where a commit can be asked for
  * directly.
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

  "a balance" should "be read from the state beneath where none is held" in {
    val base = new EvmFixtures.MapWorldState
    base.balances(owner) = EvmFixtures.word(1234)
    assert(
      new JournaledWorldState(base).balanceOf(owner) == EvmFixtures.word(1234),
      "the journal is a layer, not a replacement"
    )
  }

  it should "be held back like any other write" in {
    val base = new EvmFixtures.MapWorldState
    new JournaledWorldState(base).setBalance(owner, EvmFixtures.word(1234))
    assert(base.balanceOf(owner) == Word.Zero, "a transfer inside a failed invocation has to be droppable")
  }

  "a transaction count" should "be read from the state beneath where none is held" in {
    val base = new EvmFixtures.MapWorldState
    base.nonces(owner) = UInt64.fromBits(3L)
    assert(new JournaledWorldState(base).nonceOf(owner) == UInt64.fromBits(3L), "the journal is a layer")
  }

  it should "be held back like any other write" in {
    val base = new EvmFixtures.MapWorldState
    new JournaledWorldState(base).setNonce(owner, UInt64.fromBits(3L))
    assert(base.nonceOf(owner) == UInt64.Zero, "a creation inside a failed invocation has to be droppable")
  }

  "code" should "be read from the state beneath where none is held" in {
    val base = new EvmFixtures.MapWorldState
    base.codes(owner) = EvmFixtures.bytesOf("6001")
    assert(new JournaledWorldState(base).codeOf(owner) == EvmFixtures.bytesOf("6001"), "the journal is a layer")
  }

  it should "be held back like any other write" in {
    val base = new EvmFixtures.MapWorldState
    new JournaledWorldState(base).setCode(owner, EvmFixtures.bytesOf("6001"))
    assert(base.codeOf(owner) == org.fukuii.bytes.Bytes.Empty, "a deployment inside a failed invocation is droppable")
  }

  "an account brought into being" should "exist through the journal that made it" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.touch(owner)
    assert(journal.accountExists(owner), "an invocation sees the account it was given")
  }

  it should "not have reached the state beneath" in {
    val base = new EvmFixtures.MapWorldState
    new JournaledWorldState(base).touch(owner)
    assert(!base.accountExists(owner), "an account left behind by a failed invocation would change a state root")
  }

  "storage this journal holds" should "answer that the account has some" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    assert(journal.hasStorage(owner), "a creation must not deploy over storage an invocation has just written")
  }

  it should "answer so even where the value written was zero" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.setStorage(owner, slot, Word.Zero)
    assert(
      journal.hasStorage(owner),
      "the specification tests the address's pending writes for emptiness and never the values in them"
    )
  }

  "a restore" should "drop a balance written after the snapshot" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    val taken = journal.snapshot()
    journal.setBalance(owner, EvmFixtures.word(1234))
    journal.restore(taken)
    assert(journal.balanceOf(owner) == Word.Zero, "a snapshot covering only storage would restore a state never held")
  }

  it should "drop an account brought into being after the snapshot" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    val taken = journal.snapshot()
    journal.touch(owner)
    journal.restore(taken)
    assert(!journal.accountExists(owner), "the account the failed invocation was given is gone with the rest")
  }

  it should "drop a write made after the snapshot" in {
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

  it should "pass a balance down" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    journal.setBalance(owner, EvmFixtures.word(1234))
    journal.commit()
    assert(base.balanceOf(owner) == EvmFixtures.word(1234), "a transfer reaches a state root only once committed")
  }

  it should "pass a transaction count down" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    journal.setNonce(owner, UInt64.fromBits(3L))
    journal.commit()
    assert(base.nonceOf(owner) == UInt64.fromBits(3L), "a creation's count reaches a state root only once committed")
  }

  it should "pass code down" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    journal.setCode(owner, EvmFixtures.bytesOf("6001"))
    journal.commit()
    assert(base.codeOf(owner) == EvmFixtures.bytesOf("6001"), "a deployment reaches a state root only once committed")
  }

  it should "bring an account into being beneath" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    journal.touch(owner)
    journal.commit()
    assert(base.accountExists(owner), "an account nothing wrote to still changes a state root by existing")
  }

  it should "stop holding what it passed down" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    journal.commit()
    base.setStorage(owner, slot, EvmFixtures.word(7))
    assert(journal.storageAt(owner, slot) == EvmFixtures.word(7), "a write still held would shadow the state beneath")
  }

  "a transient write" should "be visible through the journal that made it" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.setTransientStorage(owner, slot, EvmFixtures.word(42))
    assert(journal.transientStorageAt(owner, slot) == EvmFixtures.word(42), "an invocation reads what it wrote")
  }

  it should "answer zero where nothing has been written" in {
    val base = new EvmFixtures.MapWorldState
    base.setStorage(owner, slot, EvmFixtures.word(7))
    assert(
      new JournaledWorldState(base).transientStorageAt(owner, slot) == Word.Zero,
      "there is no committed transient storage to fall through to, so the state beneath cannot answer this"
    )
  }

  it should "not answer that the account has storage" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.setTransientStorage(owner, slot, EvmFixtures.word(42))
    assert(
      !journal.hasStorage(owner),
      "a creation may deploy over an account whose only writes were transient, since none of them is stored"
    )
  }

  "a restore" should "drop a transient write made after the snapshot" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    val taken = journal.snapshot()
    journal.setTransientStorage(owner, slot, EvmFixtures.word(42))
    journal.restore(taken)
    assert(
      journal.transientStorageAt(owner, slot) == Word.Zero,
      "a snapshot that skipped this keyspace would leave a failed invocation's transient writes readable"
    )
  }

  it should "keep a transient write made before the snapshot" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.setTransientStorage(owner, slot, EvmFixtures.word(7))
    val taken = journal.snapshot()
    journal.setTransientStorage(owner, slot, EvmFixtures.word(42))
    journal.restore(taken)
    assert(
      journal.transientStorageAt(owner, slot) == EvmFixtures.word(7),
      "only what the failed invocation did is undone, here as everywhere else"
    )
  }

  "a commit" should "pass no transient write down" in {
    val base = new EvmFixtures.MapWorldState
    val journal = new JournaledWorldState(base)
    journal.setTransientStorage(owner, slot, EvmFixtures.word(42))
    journal.commit()
    assert(
      base.storageAt(owner, slot) == Word.Zero && base.slots.get((owner, slot)).isEmpty,
      "a transient write reaching the state beneath would survive the transaction and move a state root"
    )
  }

  it should "stop holding a transient write" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.setTransientStorage(owner, slot, EvmFixtures.word(42))
    journal.commit()
    assert(
      journal.transientStorageAt(owner, slot) == Word.Zero,
      "\"all values in transient storage are discarded at the end of the transaction\", and a commit ends one"
    )
  }

  // ── The one record here a failed invocation does NOT take back ────────────

  "a created-account record" should "be visible through the journal that made it" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.markAccountCreated(owner)
    assert(journal.wasCreatedInTransaction(owner), "the record a narrowed destruction turns on")
  }

  it should "answer false for an account the state beneath already held" in {
    val base = new EvmFixtures.MapWorldState
    base.setCode(owner, EvmFixtures.bytesOf("00"))
    assert(
      !new JournaledWorldState(base).wasCreatedInTransaction(owner),
      "the question is what THIS transaction created, so a populated state beneath answers nothing to it"
    )
  }

  it should "survive a restore that drops every write made beside it" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    val taken = journal.snapshot()
    journal.markAccountCreated(owner)
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    journal.restore(taken)
    assert(
      journal.wasCreatedInTransaction(owner) && journal.storageAt(owner, slot) == Word.Zero,
      "\"the marker is not removed even if the account creation reverts\", and the write beside it is the " +
        "control -- without it a restore that undid nothing at all would pass this"
    )
  }

  "a commit" should "stop holding a created-account record" in {
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.markAccountCreated(owner)
    journal.commit()
    assert(
      !journal.wasCreatedInTransaction(owner),
      "a journal reused across two transactions would otherwise report the first transaction's creations as " +
        "the second's, which is a destruction the second may not perform"
    )
  }

  // ── Why the committed read needs no case for a created account ────────────

  "an address a creation may deploy at" should "have nothing committed at any slot" in {
    // THE ANSWER TO A QUESTION THE COMMITTED READ RAISES AND CANNOT SETTLE.
    // The specification's own committed read returns zero for an account created
    // in the transaction -- `get_storage_original` at
    // `ethereum/execution-specs` @ `0cc100eb1`
    // `forks/cancun/state_tracker.py:214` -- and
    // `org.fukuii.evm.JournaledWorldState.committedStorageAt` has no such case.
    // It needs none, and this is the reason rather than an assurance: the two
    // creation entry points both gate on `Interpreter.deployableAt`, whose
    // storage term refuses every address that has any, so an address that can be
    // marked created had nothing committed to be wrong about.
    //
    // THE COUPLING IS LOAD-BEARING AND RUNS ONE WAY. Relaxing that storage term
    // -- which `deployableAt` records a trigger for -- makes the case live, and
    // this is the assertion that would then fail.
    val base = new EvmFixtures.MapWorldState
    base.setStorage(owner, slot, EvmFixtures.word(7))
    val journal = new JournaledWorldState(base)
    val elsewhere = EvmFixtures.address(0x44)
    assert(
      !Interpreter.deployableAt(journal, owner) &&
        Interpreter.deployableAt(journal, elsewhere) &&
        journal.committedStorageAt(elsewhere, slot) == Word.Zero,
      "the address holding storage is refused and the one holding none is admitted -- the second is the " +
        "control, without which a rule that refused every address would pass this"
    )
  }

  it should "still have nothing committed once a write of this transaction lands on it" in {
    // The other half, and the one a reader would suspect: a pending write makes
    // the address undeployable rather than making its committed read non-zero.
    val journal = new JournaledWorldState(new EvmFixtures.MapWorldState)
    journal.setStorage(owner, slot, EvmFixtures.word(42))
    assert(
      !Interpreter.deployableAt(journal, owner) && journal.committedStorageAt(owner, slot) == Word.Zero,
      "a pending write answers `hasStorage` whatever it wrote, so the address is refused before the committed " +
        "read is ever asked -- and the committed read is zero regardless, because nothing has been committed"
    )
  }
