package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{BlockRandomness, Proposal}

/** EIP-4399 -- the operation at `0x44` reports the beacon chain's randomness
  * instead of the block's difficulty.
  *
  * ==One delta, and it moves neither the byte nor the price==
  *
  * *"This EIP supplants the semantics of the return value of existing
  * `DIFFICULTY (0x44)` opcode and renames the opcode to `PREVRANDAO (0x44)`"*
  * (`ethereum/EIPs` @ `dbfa6bee8` (2026-08-26), `EIPS/eip-4399.md:16`, Final).
  * Its Specification is three sentences: the header's `mixHash` carries the
  * randomness (`:40`), the operation returns that field (`:44`), and *"The gas
  * cost of the `DIFFICULTY (0x44)` opcode remains unchanged"* (`:46`).
  *
  * **So this document does not touch the table.** It is the mirror of [[Eip3198]],
  * which adds an entry at a tier without moving a price: that one changes what
  * the table holds and this one changes what an entry already in it reports.
  * [[org.fukuii.evm.BlockRandomness]] carries the evidence for why a rules member
  * is what expresses that, and why a second [[org.fukuii.evm.Opcode]] case at the
  * same byte is not available.
  *
  * ==The apparent conflict with EIP-3675, and the document that settles it==
  *
  * EIP-3675's block-structure table fixes `mixHash` to
  * `0x0000000000000000000000000000000000000000000000000000000000000000`
  * (`EIPS/eip-3675.md:85`) while this document requires the same field to carry
  * *"the latest RANDAO mix of the post beacon state of the previous block"*
  * (`:40`). Read as two independent requirements they cannot both hold.
  *
  * **They are not independent, and EIP-3675 says so itself.** Two lines below its
  * own table: *"Subsequent EIPs may override the constant values specified above
  * to provide additional functionality. For an example, see EIP-4399"*
  * (`EIPS/eip-3675.md:93`). The earlier document names the later one, by number,
  * as the example of the override. **This one governs the field.**
  *
  * Three further readings agree, and none of them is the adjacency of the two
  * documents:
  *
  *   - This document's own frontmatter is `requires: 3675` (`:11`), so it is
  *     stated as an extension of that one rather than beside it.
  *   - Its rationale explains the reuse in exactly those terms: *"The `mixHash`
  *     field is deprecated at the PoS upgrade and set to zero bytes array
  *     thereafter. Reusing an existing field as a place for the randomness output
  *     saves 32 bytes per block"* (`:75`). It is describing what it is overriding.
  *   - **The executable specification constrains the field at neither reading.**
  *     `ethereum/execution-specs` @ `20f7f6271a` (2026-08-26) checks the
  *     difficulty, the nonce and the ommers commitment against constants in
  *     `src/ethereum/forks/paris/fork.py:324`, `:326` and `:328` and checks this
  *     field nowhere -- it renames it to `prev_randao` in `blocks.py:155` and
  *     passes it straight into the block environment at `fork.py:186`.
  *
  * **The two documents are also one upgrade, so no height separates them.**
  * `src/ethereum/forks/paris/__init__.py` lists what its fork changes and lists
  * these two, so there is no fork at which EIP-3675's zeroing is in force without
  * this override. The conflict has no height at which it could be observed.
  *
  * `org.fukuii.consensus.HeaderValidator` therefore compares three fields under
  * these rules and leaves this one alone, and says so at the comparison rather
  * than only here.
  *
  * ==The renames are SHOULD, and this build takes one of the two==
  *
  * *"The `mixHash` field **SHOULD** further be renamed to `prevRandao`"* (`:50`)
  * and *"The `DIFFICULTY (0x44)` opcode **SHOULD** further be renamed to
  * `PREVRANDAO (0x44)`"* (`:52`).
  *
  * **The reading is renamed and the byte is not**, which is a decision rather
  * than a partial application. [[org.fukuii.evm.BlockRandomness.Eip4399]] carries
  * the document's own vocabulary for what the operation reports. The vocabulary
  * entry for the byte stays `org.fukuii.evm.Opcode.Difficulty`, because that enum
  * is read by both network families this project serves and one of them runs that
  * operation reporting a difficulty for its whole life --
  * [[org.fukuii.evm.BlockRandomness]] carries that reasoning in full, and
  * `ethereum/go-ethereum` @ `e9e35a42f` (2026-08-26) makes the same choice by
  * declaring all three names as one constant.
  *
  * ==Where the value comes from is a field, not a chain configuration==
  *
  * `org.fukuii.evm.Environment.BlockContext.prevRandao` is what this reads, and
  * that member's own note records why it is optional for a different reason than
  * the base fee is: the 32-byte slot exists on every header of that shape, and
  * what the fork settles is whether its contents are randomness. **This rule is
  * the decider and the field is the carrier**, so a reader must not invert them
  * and take a filled slot as evidence of the fork.
  */
object Eip4399:

  /** The operation at `0x44` reports the previous block's randomness.
    *
    * The table is unread and unwritten. Every proposal in this package that
    * changes an operation reaches [[org.fukuii.evm.EvmRules.table]]; this one is
    * the first that changes what an operation reports without changing the entry,
    * which is why the delta touches a different member.
    */
  val reportsRandomness: Proposal = _.copy(blockRandomness = BlockRandomness.Eip4399)

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the machine-scoped constructor, which is the guarantee that a
    * document confined to the machine cannot reach past it. This one is: its
    * header half is a requirement on what a producer WRITES into a field, and
    * nothing in this build validates that field or produces a block.
    */
  val component: Component = Component.evm(ProposalId.Eip(4399), reportsRandomness)
