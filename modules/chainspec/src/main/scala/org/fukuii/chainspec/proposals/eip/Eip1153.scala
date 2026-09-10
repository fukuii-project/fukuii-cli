package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-1153 -- a keyspace one transaction writes and no block keeps.
  *
  * ==Two entries, at a figure the document names by reference rather than
  * fixes==
  *
  * *"Two new opcodes are added to EVM, `TLOAD` (`0x5c`) and `TSTORE`
  * (`0x5d`)"*, and *"gas cost for `TSTORE` is the same as a warm `SSTORE` of a
  * dirty slot (i.e. original value is not new value and is not current value,
  * currently 100 gas), and gas cost of `TLOAD` is the same as a hot `SLOAD`
  * (value has been read before, currently 100 gas)"* (`ethereum/EIPs` @
  * `d2a64c2d4` (2026-09-09), `EIPS/eip-1153.md`, Final).
  *
  * **The parenthesised figure is a reading and the reference is the rule**, so
  * both entries below are built from `org.fukuii.evm.GasSchedule.warmAccess`
  * rather than from a literal 100 -- the same reason [[Eip3198]] and [[Eip3855]]
  * name a tier. A network that moved the warm figure would move these two with
  * it, which is what the document asks for and what a literal would silently
  * opt them out of.
  *
  * **Both corroborating sources declare the price by that same indirection.**
  * `ethereum/execution-specs` @ `0cc100eb1` writes `OPCODE_TLOAD: Final[Uint] =
  * WARM_ACCESS` and `OPCODE_TSTORE: Final[Uint] = WARM_ACCESS`
  * (`forks/cancun/vm/gas.py:156-157`); `ethereum/go-ethereum` @ `02872e9ef`
  * gives both `constantGas: params.WarmStorageReadCostEIP2929`
  * (`core/vm/eips.go:186-200`); `besu-eth/besu` @ `b330564a9` sets
  * `TLOAD_GAS = TSTORE_GAS = WARM_STORAGE_READ_COST`
  * (`CancunGasCalculator.java:43-44`).
  *
  * ==The figure is the RULE SET's, so a rule set holding none prices these at
  * none==
  *
  * The document defines the charge by reference to a warm-and-cold scheme's warm
  * figure, and a rule set that has adopted no such scheme has nothing for that
  * reference to resolve to -- these two entries then carry whatever that rule
  * set holds, which for a network below EIP-2929 is zero. **That is the
  * reference working rather than failing**, and it is stated because the seam
  * cannot refuse: a `Proposal` is a total function over the rules, so a
  * component has no way to decline a composition it has no price for. Adopting
  * this document over a rule set with no warm figure is therefore a
  * configuration decision, and the spec beside this file asserts the figure in
  * both directions so the dependency is visible rather than a zero nobody
  * notices.
  *
  * ==Settled prices, over a keyspace with no cold state to be in==
  *
  * The two operations they are priced against are metered warm-or-cold, and
  * these are not: nothing is committed anywhere for a first reach to be
  * expensive about, so the reference resolves to the warm figure at every
  * reach. Both entries are therefore
  * [[org.fukuii.evm.Cost.Fixed]] rather than computed, and this document leaves
  * `org.fukuii.evm.EvmRules.stateAccessMetering` alone.
  *
  * ==What it does NOT reach==
  *
  * The schedule, admission, execution or the header. No figure moves -- the
  * field these entries are built from is already what the rules hold -- so a
  * rule set adopting this document adds two operations and changes nothing
  * else.
  *
  * ==This is the ONE Cancun document whose delta needs a place to put a write,
  * and it already had one==
  *
  * `org.fukuii.evm.JournaledWorldState` is per transaction and is snapshotted
  * around every invocation, which is exactly the pair the document requires:
  * writes rolled back with a reverting frame, and everything discarded when the
  * transaction ends. So the component here is two table entries and the
  * behavior is the machine's, in the type that already had the lifetime.
  *
  * **The obvious nearby home is the wrong one and is worth naming.**
  * `org.fukuii.evm.Frame.accessedAddresses` is copied on entry and merged up
  * only where a nested invocation stopped normally. That discards a failed
  * frame's writes, which looks like the rule -- and it also hides a SUCCEEDING
  * frame's writes from a sibling that runs after it, because each frame
  * accumulates into its own copy. The document requires the opposite: *"all the
  * frames access the same transient store, in the same way as persistent
  * storage, but unlike memory"*.
  */
object Eip1153:

  /** Both operations join the table at the figure the document refers to. */
  val transientStorage: Proposal =
    rules =>
      rules.copy(table =
        rules.table
          .adding(Operation(Opcode.TLoad, Cost.Fixed(rules.schedule.warmAccess)))
          .adding(Operation(Opcode.TStore, Cost.Fixed(rules.schedule.warmAccess)))
      )

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(1153), transientStorage)
