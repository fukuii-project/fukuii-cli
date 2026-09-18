package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{BlobSchedule, Component, HeaderRules, ProposalId}
import org.fukuii.evm.EvmRules

/** EIP-7691 -- more blobs per block, by moving three numbers.
  *
  * ==This document adds no mechanism, and that is the whole of it==
  *
  * EIP-4844 built the blob accounting: a header states `blobGasUsed` and
  * `excessBlobGas`, a transaction buys blob gas outside the block's own limit,
  * and a fee follows an exponential in the excess. **Every one of those rules is
  * unchanged here.** What moves is the target, the maximum, and the fraction the
  * fee update divides by -- so this document is three values written onto records
  * that already exist, and `org.fukuii.chainspec.BlobSchedule` is the record
  * EIP-4844 already created for exactly this.
  *
  * **So there is no new seam and no new reader.** `org.fukuii.consensus.HeaderValidator`
  * checks `blobGasUsed` against the maximum as it already did, and
  * `org.fukuii.consensus.BlockValidator` derives the excess as it already did.
  * A fork adopting this document changes what those two compute with, never how.
  *
  * ==The three values, and why the pair is not derivable from one number==
  *
  * Target 6, maximum 9, update fraction 5,007,716.
  *
  *   - `ethereum/execution-specs` @ `0cc100eb1`,
  *     `src/ethereum/forks/prague/vm/gas.py:96`,
  *     `BLOB_BASE_FEE_UPDATE_FRACTION: Final[Uint] = Uint(5007716)` -- against
  *     Cancun's 3,338,477 at `src/ethereum/forks/cancun/vm/gas.py:86`, which is
  *     the control for having read the right fork.
  *   - The same tree's `src/ethereum/forks/prague/fork.py:102`,
  *     `MAX_BLOB_GAS_PER_BLOCK: Final[U64] = U64(1179648)` -- nine blobs at
  *     131,072 gas each.
  *   - `ethereum/go-ethereum` @ `02872e9ef` `params/config.go:344-348` states all
  *     three together as `DefaultPragueBlobConfig`: `Target: 6`, `Max: 9`,
  *     `UpdateFraction: 5007716`, with `DefaultCancunBlobConfig` at `:338-342`
  *     giving 3 / 6 / 3338477.
  *
  * **The target is not half the maximum here, and neither schedule read for this
  * makes it so.** [[Eip4844]]'s pair is 3/6 and this one is 6/9; `BlobSchedule`
  * already carries the evidence, across six entries in one client's table and
  * three in another's, that deriving either member from the other would be wrong
  * at every entry rather than at an edge case.
  *
  * ==Why the fraction sits on the machine and the pair on the header==
  *
  * The split is EIP-4844's and is kept rather than revisited. The target and the
  * maximum bound what a header may state, so a header alone settles them. The
  * update fraction is read only when a fee is computed from an excess, which is
  * machine work -- `org.fukuii.chainspec.EvmRules` holds it for that reason, and
  * this document writes to both records because the quantities it moves live on
  * both.
  */
object Eip7691:

  /** The per-fork pair a header is checked against. */
  val widenedSchedule: HeaderRules => HeaderRules =
    _.copy(blobSchedule = Some(BlobSchedule(targetBlobs = BigInt(6), maxBlobs = BigInt(9))))

  /** The divisor the fee update uses, which only the machine reads. */
  val widenedUpdateFraction: EvmRules => EvmRules =
    _.copy(blobBaseFeeUpdateFraction = Some(BigInt(5007716)))

  /** Adopting the document, which is adopting its three values. */
  val component: Component =
    Component(
      ProposalId.Eip(7691),
      rules =>
        rules.copy(
          header = widenedSchedule(rules.header),
          evm = widenedUpdateFraction(rules.evm)
        )
    )
