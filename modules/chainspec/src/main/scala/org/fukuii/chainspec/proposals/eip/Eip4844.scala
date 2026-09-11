package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{BlobSchedule, Component, HeaderRules, ProposalId}
import org.fukuii.evm.{Cost, EvmRules, Opcode, Operation, Precompile, PrecompileSet}
import org.fukuii.execution.AdmissionRules
import org.fukuii.types.TransactionType

/** EIP-4844 -- blob-carrying transactions.
  *
  * ==A COMPLETE adoption, and what completed it is the part a reader was
  * previously warned about==
  *
  * The document introduces a transaction format, an operation reporting a
  * blob's hash, a precompile evaluating a polynomial, and the blob-gas
  * accounting a header carries. **A rule set adopting this component now has
  * all four.** It accounts for blob gas in its headers, prices blob gas for
  * anything that asks, admits the format at `0x03`, holds the operation at
  * `0x49`, and installs the native at `0x0a`.
  *
  * **What that changes for a reader of
  * `org.fukuii.chainspec.UpgradeRules.components` is the size of the gap, not
  * the rule about it.** That record states which documents were applied and the
  * rules state what applying them did; the two are still different claims, and
  * this document is simply no longer the worked case where they came apart.
  *
  * **A contract can now verify a commitment a transaction carried.** Admission
  * checks only that each commitment names the scheme
  * (`org.fukuii.evm.BlobGas.versionKnown`), because the commitment itself is
  * not part of the transaction -- the blobs and their commitments travel beside
  * it on the network layer. The precompile is handed a commitment by its
  * caller, so it is the one place the digest under that byte can be checked
  * against the commitment it claims to name, which is
  * `org.fukuii.evm.BlobGas.versionedHashOf`.
  *
  * ==Five deltas across three facets, and two of them do not reach the machine==
  *
  * The header gains the blob schedule; the machine gains the update fraction
  * the charge is derived through, the operation that reports a commitment, and
  * the native that verifies one; admission gains the format.
  * `ethereum/execution-specs` @ `0cc100eb1` (2026-09-11) splits the same
  * document the same way, between `src/ethereum/forks/cancun/fork.py`'s header
  * and transaction checks and `forks/cancun/vm/`'s gas constants, instruction
  * table and `precompiled_contracts/`.
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

  /** The native verifying that a blob's committed polynomial takes a claimed
    * value at a claimed point, at the address the document names.
    *
    * *"a precompile at `POINT_EVALUATION_PRECOMPILE_ADDRESS`"*, that address
    * given as `Bytes20(0x0A)` and `POINT_EVALUATION_PRECOMPILE_GAS` as 50,000
    * (`ethereum/EIPs` @ `d2a64c2d4` (2026-09-11), `EIPS/eip-4844.md:56-57,131`,
    * Final). Corroborated at `ethereum/execution-specs` @ `0cc100eb1`
    * (2026-09-11), `src/ethereum/forks/cancun/vm/gas.py:76`,
    * `PRECOMPILE_POINT_EVALUATION: Final[Uint] = Uint(50000)`, and at
    * `ethereum/go-ethereum` @ `02872e9ef` (2026-09-11),
    * `params/protocol_params.go:204`.
    *
    * **No price moves.** 50,000 is already
    * `org.fukuii.evm.GasSchedule.precompilePointEvaluation`, stated at that
    * figure by both networks this repository configures, so this is a placement
    * built from a figure the rules already hold -- [[Eip152]]'s shape, and for
    * the same reason.
    *
    * **The figure is unmoved by every later fork the specification carries**,
    * checked across its per-fork gas modules rather than assumed from the
    * document: each states 50,000, and the newest differs only in wrapping it
    * in a gas type rather than in its value. That is why it is one number in a
    * schedule rather than something a later component reprices.
    *
    * ==What it does NOT reach==
    *
    * `org.fukuii.evm.OpcodeTable`, for [[Eip198]]'s reason -- a native has no
    * byte in the instruction set.
    */
  val pointEvaluation: EvmRules => EvmRules =
    rules =>
      rules.copy(precompiles =
        rules.precompiles.adding(
          PrecompileSet.PointEvaluation,
          Precompile.PointEvaluation(rules.schedule.precompilePointEvaluation)
        )
      )

  /** Adopting the document, which is adopting all five of its deltas.
    *
    * Built from the general constructor rather than the machine-scoped one
    * because two of the five do not reach the machine, which is [[Eip1559]]'s
    * reason for the same choice.
    *
    * **The order is stated and is immaterial**: no two of the five name a
    * common field, and those that share a facet add distinct members to it --
    * the two machine-scoped placements land in different records, one in the
    * instruction table and one in the precompile set.
    */
  val component: Component =
    Component(
      ProposalId.Eip(4844),
      rules =>
        rules.copy(
          evm = pointEvaluation(blobHash(blobGasPricing(rules.evm))),
          header = blobAccounting(rules.header),
          admission = admitsBlobFormat(rules.admission)
        )
    )
