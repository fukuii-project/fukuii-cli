package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{BlobSchedule, Component, HeaderRules, ProposalId}
import org.fukuii.evm.{Cost, EvmRules, Opcode, Operation}
import org.fukuii.execution.AdmissionRules
import org.fukuii.types.TransactionType

/** EIP-4844 -- blob-carrying transactions.
  *
  * ==A PARTIAL adoption still, and the remaining part is named rather than
  * implied==
  *
  * The document introduces a transaction format, an operation reporting a
  * blob's hash, a precompile evaluating a polynomial, and the blob-gas
  * accounting a header carries. **This component is all of those but the
  * precompile.** A rule set adopting it accounts for blob gas in its headers,
  * prices blob gas for anything that asks, admits the format at `0x03`, and
  * holds the operation at `0x49`; it installs no precompile at `0x0a`.
  *
  * That still matters because `org.fukuii.chainspec.UpgradeRules.components`
  * records this document's number once a rule set adopts it, and a reader
  * taking that record as *"every rule this document states is in force here"*
  * would be wrong -- less wrong than before, and wrong in the same way. The
  * record states which documents were applied and the rules state what applying
  * them did, which is the property that type already documents.
  *
  * **A rule set here therefore accepts a blob transaction whose commitment a
  * contract cannot verify.** Verification is the precompile's, and nothing
  * about admitting the format supplies it: what admission checks is that each
  * commitment names the scheme (`org.fukuii.evm.BlobGas.VersionedHashVersion`),
  * never that the digest under that byte is the one the blob produces. The
  * blobs themselves travel beside the transaction on the network layer and no
  * layer here holds them.
  *
  * ==Four deltas across three facets, and the fourth is why the record is not
  * machine-scoped==
  *
  * The header gains the blob schedule; the machine gains the update fraction
  * the charge is derived through and the operation that reports a commitment;
  * admission gains the format. `ethereum/execution-specs` @ `0cc100eb1`
  * (2026-09-11) splits the same document the same way, between
  * `src/ethereum/forks/cancun/fork.py`'s header and transaction checks and
  * `forks/cancun/vm/`'s gas constants and instruction table.
  *
  * ==The SCHEDULE alone spans two of those facets, and its split is by reader
  * rather than by subject==
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

  /** The operation reporting a commitment the transaction carries.
    *
    * ==A LITERAL price, where every entry beside it names a tier==
    *
    * *"The opcode has a gas cost of `HASH_OPCODE_GAS`"* with
    * `HASH_OPCODE_GAS` at 3 (`ethereum/EIPs` @ `d2a64c2d4` (2026-09-11),
    * `EIPS/eip-4844.md:55,190-193`, Final) -- **a constant of the document's
    * own rather than a reference to an existing tier**, which is the opposite
    * of what [[Eip7516]] found for the operation one byte along.
    *
    * Two of three sources follow the document rather than the tier, and they
    * do it in files where the tier is right there to be named:
    * `ethereum/execution-specs` @ `0cc100eb1` writes
    * `OPCODE_BLOBHASH: Final[Uint] = Uint(3)` on
    * `src/ethereum/forks/cancun/vm/gas.py:151`, one line below
    * `OPCODE_BLOBBASEFEE: Final[Uint] = BASE`, and with `VERY_LOW` -- which is
    * also 3 -- declared in the same class and used by a dozen other entries.
    * `besu-eth/besu` @ `b330564a9` returns `new OperationResult(3, null)` from
    * `evm/.../operation/BlobHashOperation.java:70` rather than asking its gas
    * calculator for a tier. Only `ethereum/go-ethereum` @ `02872e9ef` names one
    * (`core/vm/eips.go:302`, `constantGas: GasFastestStep`).
    *
    * **So naming the tier here would opt the operation INTO a tier the
    * specification deliberately kept it out of** -- [[Eip7516]]'s hazard
    * reversed, and the reason that record's reasoning is not simply copied. The
    * two agree numerically at every fork read for this; what differs is the
    * claim each arrangement makes about what a repricing of the tier would
    * carry with it.
    */
  val blobHash: EvmRules => EvmRules =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.BlobHash, Cost.Fixed(BigInt(3)))))

  /** The format a block at these rules may carry, added to what it already
    * carried.
    *
    * `ethereum/EIPs` @ `d2a64c2d4` `EIPS/eip-4844.md:42` states `BLOB_TX_TYPE`
    * as `Bytes1(0x03)`; `org.fukuii.types.TransactionType` holds the tag and
    * this names the format rather than the number, for [[Eip2930]]'s reason.
    */
  val admitsBlobFormat: AdmissionRules => AdmissionRules =
    rules => rules.copy(admittedTypes = rules.admittedTypes + TransactionType.Blob)

  /** Adopting the document, which is adopting all four of its deltas.
    *
    * Built from the general constructor rather than the machine-scoped one
    * because two of the four do not reach the machine, which is [[Eip1559]]'s
    * reason for the same choice.
    *
    * **The order is stated and is immaterial**: no two of the four name a
    * common field, and the two that share a facet add distinct members to it.
    */
  val component: Component =
    Component(
      ProposalId.Eip(4844),
      rules =>
        rules.copy(
          evm = blobHash(blobGasPricing(rules.evm)),
          header = blobAccounting(rules.header),
          admission = admitsBlobFormat(rules.admission)
        )
    )
