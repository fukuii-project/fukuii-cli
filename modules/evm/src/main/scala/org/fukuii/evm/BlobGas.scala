package org.fukuii.evm

import org.fukuii.bytes.Hash
import org.fukuii.types.Transaction

/** How much blob gas a transaction spends, and which commitments it spends it
  * on.
  *
  * ==A count of blobs, priced at a figure no fork varies==
  *
  * `ethereum/EIPs` @ `d2a64c2d4` (2026-09-11), `EIPS/eip-4844.md:53` states
  * `GAS_PER_BLOB` as `2**17`, and `ethereum/execution-specs` @ `0cc100eb1`
  * (2026-09-11) repeats `PER_BLOB: Final[U64] = U64(2**17)` unchanged in every
  * fork module that has a blob schedule at all --
  * `src/ethereum/forks/cancun/vm/gas.py:83` through `forks/bpo5/vm/gas.py:94`.
  * `ethereum/go-ethereum` @ `02872e9ef` holds it as the package constant
  * `params.BlobTxBlobGasPerBlob` and multiplies its per-fork blob COUNTS by it.
  *
  * ==Here rather than on a fork's rules, and here rather than in either layer
  * that reads it==
  *
  * A fork-invariant figure on a fork-resolved record is a member nothing can
  * vary, which `org.fukuii.chainspec.UpgradeRules`'s own admission test refuses
  * -- the reason `org.fukuii.consensus.HeaderValidator.GasLimitBoundDivisor`
  * sits beside its reader rather than on a schedule.
  *
  * **It has two readers in different layers, which is what puts it in this
  * module and not in either of them.** A header states a block's spend and a
  * validator bounds it; admission charges a transaction for its own and block
  * processing accumulates them. Those sit in `org.fukuii.consensus` and
  * `org.fukuii.execution`, neither of which can see the other, and both of
  * which can see this module. A copy in each would be one number with two
  * definitions, which is the arrangement a fork that moved it could not
  * survive.
  *
  * ==The derivation is the specification's own, and it reads nothing that runs==
  *
  * `ethereum/execution-specs` @ `0cc100eb1`
  * `src/ethereum/forks/cancun/vm/gas.py:431-434` is
  * `GasCosts.PER_BLOB * U64(len(tx.blob_versioned_hashes))` for a blob
  * transaction and `U64(0)` for every other, and `ethereum/go-ethereum` @
  * `02872e9ef` `core/state_transition.go:1209-1210` is
  * `len(st.msg.BlobHashes) * params.BlobTxBlobGasPerBlob`. **Both read a
  * LENGTH**, so what a block spent on blobs is settled by its body and needs no
  * execution -- which is what lets a caller holding a block's transactions
  * compare the figure against a header without running any of them.
  */
object BlobGas:

  /** What one blob costs in blob gas. */
  val PerBlob: BigInt = BigInt(1) << 17

  /** The version byte a commitment's hash must lead with.
    *
    * `ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-4844.md:46` states
    * `VERSIONED_HASH_VERSION_KZG` as `Bytes1(0x01)`, and
    * `ethereum/execution-specs` @ `0cc100eb1` states the identical
    * `VERSIONED_HASH_VERSION_KZG = b"\x01"` twice --
    * `src/ethereum/forks/cancun/fork.py:94`, read by the admission rule, and
    * `forks/cancun/vm/precompiled_contracts/point_evaluation.py:29`, read by a
    * precompile this build has not installed.
    *
    * **It names the scheme that produced the commitment, which is why a hash
    * leading with anything else is refused rather than ignored.** The document
    * derives the hash as
    * `VERSIONED_HASH_VERSION_KZG + sha256(commitment)[1:]` (`:80`), so the byte
    * is a discriminator a later scheme is expected to move.
    */
  val VersionedHashVersion: Byte = 0x01

  /** Whether `hash` names the commitment scheme these rules know.
    *
    * The check is on the FIRST byte and on nothing else. The remaining
    * thirty-one are a digest this layer cannot recompute -- the commitment they
    * are taken over travels beside the transaction on the network layer and is
    * not part of it here.
    */
  def versionKnown(hash: Hash): Boolean =
    hash.toBytes.headOption.contains(VersionedHashVersion)

  /** What a transaction carrying these commitments spends on them. */
  def spentOn(blobVersionedHashes: Seq[Hash]): BigInt = PerBlob * blobVersionedHashes.length

  /** The commitments a transaction carries, which only one format can.
    *
    * Written out per payload rather than as a wildcard, so a format carrying
    * blobs that is added later cannot silently answer that it carries none --
    * the reason `org.fukuii.execution.BlockProcessor` writes its own
    * per-payload matches out rather than defaulting.
    */
  def carriedBy(transaction: Transaction): Seq[Hash] = transaction match
    case _: Transaction.Legacy     => Seq.empty
    case _: Transaction.AccessList => Seq.empty
    case _: Transaction.DynamicFee => Seq.empty
    case t: Transaction.Blob       => t.blobVersionedHashes
    case _: Transaction.SetCode    => Seq.empty

  /** What a transaction spends on blobs, which is zero for every format but
    * one.
    *
    * The whole of what a block spent is this summed over the transactions it
    * carries, and nothing in the sum reads a result -- see the class note.
    */
  def spentBy(transaction: Transaction): BigInt = spentOn(carriedBy(transaction))
