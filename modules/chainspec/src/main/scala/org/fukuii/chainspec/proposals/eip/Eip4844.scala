package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{BlobSchedule, Component, HeaderRules, ProposalId}
import org.fukuii.evm.EvmRules

/** EIP-4844 -- blob-carrying transactions, as the blob-gas accounting alone.
  *
  * ==A PARTIAL adoption, and the part is named rather than implied==
  *
  * The document introduces a transaction format, an operation reporting a
  * blob's hash, a precompile evaluating a polynomial, and the blob-gas
  * accounting a header carries. **This component is the last of those and
  * nothing else.** A rule set adopting it accounts for blob gas in its headers
  * and prices blob gas for anything that asks; it admits no blob transaction,
  * holds no operation at `0x49`, and installs no precompile at `0x0a`.
  *
  * That matters because `org.fukuii.chainspec.UpgradeRules.components` records
  * this document's number once a rule set adopts it, and a reader taking that
  * record as *"blob transactions are in force here"* would be wrong. The record
  * states which documents were applied and the rules state what applying them
  * did -- which is the property that type already documents, met here for the
  * first time by a document whose delta arrives in more than one phase.
  *
  * ==Two facets, and the split is by reader rather than by subject==
  *
  * The published shape of a fork's blob schedule is one object of three, and
  * `org.fukuii.chainspec.BlobSchedule` records why one of the three sits
  * elsewhere. The target and the maximum are read by header rules and land on
  * the header facet; the update fraction is read by the machine and lands on
  * the machine's rules. One component writes all three, from one reading of one
  * fork's figures, so they cannot drift apart by being edited separately.
  *
  * ==The figures are this fork's and the document's own, which are the same
  * figures and will not stay that way==
  *
  * `ethereum/EIPs` @ `d2a64c2d4` (2026-09-11), `EIPS/eip-4844.md:49-53`, Final,
  * states `MAX_BLOB_GAS_PER_BLOCK` as 786,432, `TARGET_BLOB_GAS_PER_BLOCK` as
  * 393,216, `GAS_PER_BLOB` as `2**17` and `BLOB_BASE_FEE_UPDATE_FRACTION` as
  * 3,338,477 -- so the target is three blobs and the maximum six, which
  * `EIPS/eip-4844.md:400` then says in those words.
  * `ethereum/execution-specs` @ `0cc100eb1` (2026-09-11)
  * `src/ethereum/forks/cancun/vm/gas.py:83-86` states the target, the per-blob
  * figure and the update fraction identically, and holds the maximum one file
  * away as `MAX_BLOB_GAS_PER_BLOCK` (`forks/cancun/fork.py:93`).
  * `ethereum/go-ethereum` @ `02872e9ef` (2026-09-11) `params/config.go:338-342`
  * gives `Target: 3`, `Max: 6` and `UpdateFraction: 3338477` for the same fork,
  * and `besu-eth/besu` @ `b330564a9` (2026-09-11)
  * `config/.../BlobSchedule.java:24` states the same three as `create(3, 6,
  * 3338477)`.
  *
  * **Three later forks move them**, which is why they are data here rather than
  * constants in the rules that read them: the same go-ethereum block gives
  * 6/5007716, 10/8346193 and 14/11684671 at the three further forks it carries
  * an entry for, and the executable specification's own per-fork modules carry
  * the same four schedules. **Four schedules is not four forks** -- the
  * specification's Osaka module repeats the one before it
  * (`forks/osaka/vm/gas.py:95-100` against `forks/prague/vm/gas.py:93-96`), and
  * go-ethereum carries no Osaka entry at all, so a reader counting forks rather
  * than distinct schedules gets a different number from both.
  */
object Eip4844:

  /** What a header at these rules accounts for in blob gas. */
  val blobAccounting: HeaderRules => HeaderRules =
    _.copy(blobSchedule = Some(BlobSchedule(targetBlobs = BigInt(3), maxBlobs = BigInt(6))))

  /** What the charge per unit of blob gas is derived through.
    *
    * A machine rule rather than a header one because the layers that need a
    * price are the machine and the settlement of a blob-carrying transaction,
    * and a header rule needs none -- see `org.fukuii.evm.EvmRules`.
    */
  val blobGasPricing: EvmRules => EvmRules =
    _.copy(blobBaseFeeUpdateFraction = Some(BigInt(3338477)))

  /** Adopting the blob-gas accounting, which is adopting both of its deltas.
    *
    * Built from the general constructor rather than the machine-scoped one
    * because half of it does not reach the machine, which is [[Eip1559]]'s
    * reason for the same choice.
    */
  val component: Component =
    Component(
      ProposalId.Eip(4844),
      rules =>
        rules.copy(
          evm = blobGasPricing(rules.evm),
          header = blobAccounting(rules.header)
        )
    )
