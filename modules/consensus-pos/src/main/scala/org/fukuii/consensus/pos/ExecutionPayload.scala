package org.fukuii.consensus.pos

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.types.{Bloom, Withdrawal}

/** A block, as the consensus layer hands it to the execution layer: fourteen
  * fields that have been there since the merge, then a chain of fields each
  * later proposal appended.
  *
  * ==This is not a header, and the difference is what it leaves out==
  *
  * Seven of the header fields this build models are absent, in three
  * groups. Three are constants the merge fixed and nobody transmits — the
  * ommers hash, the difficulty and the nonce. Three are commitments derivable
  * from what is here — the transactions root, the withdrawals root and the
  * requests hash. The seventh, the parent beacon block root, is transmitted but
  * as a separate argument to the verb rather than inside the payload.
  *
  * So a payload is the INPUT a header is built from, plus the resulting
  * `blockHash` for the receiver to check its own construction against.
  *
  * That is why [[org.fukuii.types.BlockHeader]] is not reused for this and why
  * its tail chain is not reused either: `WithdrawalsTail` carries a root where
  * this carries the list, and `BeaconRootTail` and `RequestsTail` carry values
  * that travel as separate method arguments rather than inside the payload
  * (`ethereum/execution-apis` @ `6570b55` `src/engine/prague.md:35-38`, where
  * `parentBeaconBlockRoot` and `executionRequests` are parameters 3 and 4 of
  * `engine_newPayloadV4` and the payload itself is still an
  * `ExecutionPayloadV3`).
  *
  * ==Why the appended fields are a chain and not independent options==
  *
  * The specification defines the versions as strict prefixes — each *"has the
  * syntax of"* the previous one *"and appends"* its own fields — and requires
  * the version to match the fork: `-32602: Invalid params` where the wrong
  * structure is used (`src/engine/shanghai.md:96-99`). So a payload carrying
  * blob-gas fields and no withdrawals belongs to no version, can be named by no
  * `engine_newPayloadVN`, and is not a value the protocol has.
  *
  * Three independent options would admit eight states where the specification
  * defines three. A chain admits exactly the three, and makes
  * [[structureVersion]] a walk rather than a lookup.
  *
  * **The field disagrees about the representation and agrees about the
  * semantics**, which is what leaves the choice open here.
  * `besu-eth/besu` @ `b330564a94` makes it an inheritance chain —
  * `ExecutionPayloadV1.java:52` is `sealed class ... permits ExecutionPayloadV2`
  * and each version extends the last, so its undefined combinations are
  * unrepresentable too. `NethermindEth/nethermind` @ `df655aef1f`
  * `ExecutionPayload.cs:97,104` declares the added fields `virtual` and
  * nullable on one base class, and `ExecutionPayloadV3.cs:56,63` overrides them
  * `sealed` and `[JsonRequired]`, which reaches the same exclusion through the
  * decoder. `ethereum/go-ethereum` @ `02872e9ef` `beacon/engine/types.go:90-110`
  * uses one flat struct of independent pointers and admits every combination in
  * the type, checking elsewhere.
  *
  * ==`baseFeePerGas` is mandatory here, unlike on a header==
  *
  * A header's base fee is optional because headers predate EIP-1559. A payload's
  * is not, because payloads postdate it: the merge is after London, so the
  * earliest structure that exists already carries one. The specification lists
  * it with no null (`src/engine/paris.md:56`) and go-ethereum marks it
  * `gencodec:"required"` (`beacon/engine/types.go:102`). Measured over the
  * published engine fixtures at `tests-v20.0.1`, it is present in every entry
  * of a roughly 870-entry sample taken under each of `for_paris` — the earliest
  * fork label there is — `for_shanghai`, `for_cancun`, `for_prague` and
  * `for_osaka`. The transition and blob-parameter labels were not sampled, so
  * this is a claim about those five and not about the release.
  *
  * ==Transactions are carried as bytes, because the protocol has a response for
  * bytes that do not decode==
  *
  * `engine_newPayload` must answer `{status: INVALID, latestValidHash: null}`
  * where *"`transactions` contains zero length or invalid entries"*
  * (`src/engine/paris.md:175`). That is a domain outcome, so the undecodable
  * input has to be a value this type can hold; a `Seq[Transaction]` could not
  * represent the payload whose required answer is `INVALID`.
  *
  * The specification types the field `Array of DATA` and two of the three
  * clients read agree — `[][]byte` in go-ethereum (`beacon/engine/types.go:104`)
  * and `byte[][]` in nethermind (`ExecutionPayload.cs:57,65`, with decoding a
  * separate fallible `TryGetTransactions`). besu is the exception and holds
  * `List<Transaction>` (`ExecutionPayloadV1.java:66`).
  *
  * @param prevRandao
  *   the consensus layer's randomness for this block. It occupies the header
  *   slot a proof-of-work network fills with a mixed hash, which is why
  *   [[org.fukuii.types.Seal]] names that slot after the slot rather than after
  *   either family's reading of it.
  * @param blockHash
  *   what the sender computed. It is a claim to be checked rather than a fact
  *   to be trusted — the receiver rebuilds the header and compares — so it is
  *   carried as an ordinary field and nothing here derives it.
  * @param extraData
  *   capped at 32 bytes by consensus rules on the networks this client targets.
  *   That is a validity rule rather than an encoding one and is not enforced
  *   here, exactly as it is not on a header.
  */
final case class ExecutionPayload(
    parentHash: Hash,
    feeRecipient: Address,
    stateRoot: Hash,
    receiptsRoot: Hash,
    logsBloom: Bloom,
    prevRandao: Hash,
    blockNumber: UInt64,
    gasLimit: UInt64,
    gasUsed: UInt64,
    timestamp: UInt64,
    extraData: Bytes,
    baseFeePerGas: UInt256,
    blockHash: Hash,
    transactions: Seq[Bytes],
    appended: Option[PayloadWithdrawals] = None,
    familyFields: Option[ExecutionPayload.FamilyFields] = None
):

  def withdrawals: Option[Seq[Withdrawal]] = appended.map(_.withdrawals)

  private def blobGas: Option[PayloadBlobGas] = appended.flatMap(_.next)

  def blobGasUsed: Option[UInt64] = blobGas.map(_.blobGasUsed)

  def excessBlobGas: Option[UInt64] = blobGas.map(_.excessBlobGas)

  /** Which `ExecutionPayloadVN` of the specification this value is.
    *
    * **Not the `engine_newPayloadVN` method version, and the two moved apart.**
    * `engine_newPayloadV4` takes an `ExecutionPayloadV3`
    * (`ethereum/execution-apis` @ `6570b55` `src/engine/prague.md:35`), because
    * what Prague added arrived as a fourth method parameter rather than as a
    * payload field. Reading this as a method version is therefore wrong from
    * Prague onward, which is why the name says which axis it is on.
    *
    * Read off the chain rather than from a fork rule: which structure a given
    * network accepts at a given timestamp is the fork gate's to decide, and
    * that gate is not this type's.
    */
  def structureVersion: Int = 1 + PayloadWithdrawals.lengthOf(appended)

/** The field EIP-4895 appended, as the payload carries it.
  *
  * The payload carries the withdrawal LIST where a header carries only its
  * root, so this is not [[org.fukuii.types.WithdrawalsTail]] under another name
  * and the two are not interchangeable.
  */
final case class PayloadWithdrawals(withdrawals: Seq[Withdrawal], next: Option[PayloadBlobGas] = None)

/** The two fields EIP-4844 appended.
  *
  * One link rather than two, for the reason
  * [[org.fukuii.types.BlobGasTail]] is one: the proposal defines them as a
  * pair, no version carries one without the other, and separating them would
  * reintroduce the unrepresentable state this chain exists to exclude.
  *
  * It has no `next`, and that is a statement about what has activated rather
  * than about what can. The next link is the pair EIP-7928 and the slot number
  * append, and the fork defining it is drafted rather than scheduled — so
  * adding it is a change to this chain, made by whoever schedules that fork.
  */
final case class PayloadBlobGas(blobGasUsed: UInt64, excessBlobGas: UInt64)

object PayloadWithdrawals:

  /** How many links the chain contributes, counted by walking it. */
  def lengthOf(appended: Option[PayloadWithdrawals]): Int = appended match
    case None    => 0
    case Some(w) => 1 + (if w.next.isEmpty then 0 else 1)

object ExecutionPayload:

  /** Fields a network family appends to the payload for itself.
    *
    * ==Why the slot exists when nothing in this build fills it==
    *
    * Because a consumer that fills it exists in the field today, and a type
    * closed against it would have to be reopened rather than extended.
    * `ethereum-optimism/op-geth` @ `7da4560d1` reuses this seam and appends
    * `WithdrawalsRoot` to its `ExecutableData` (`beacon/engine/types.go:132`),
    * having no way to extend upstream's struct otherwise — and besu's version
    * chain is `sealed ... permits`, so a family leaf outside its own package
    * cannot extend that either. **Both available representations foreclose the
    * family; one open slot is what does not.**
    *
    * ==What is deliberately NOT here==
    *
    * The field itself. This build implements no rollup, so declaring
    * `withdrawalsRoot` would be building a consumer that does not exist here
    * rather than declining to foreclose one that exists elsewhere.
    *
    * ==The evidence is thinner than [[PayloadAttributes.FamilyFields]]'s==
    *
    * That seam is earned by five appended fields; this one by a single field in
    * a single client. It is declared anyway, because a family driver with a
    * home for its attributes and none for its payload would have to carry the
    * second half outside the type the verb takes, which is the foreclosure this
    * slot is here to avoid.
    *
    * Empty by design: the core reads nothing from it, which is the whole of
    * what "byte-identical core" means. A family leaf declares its own case and
    * recovers it by matching on that case.
    */
  trait FamilyFields
