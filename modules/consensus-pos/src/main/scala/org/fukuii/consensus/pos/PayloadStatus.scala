package org.fukuii.consensus.pos

import org.fukuii.bytes.Hash

/** What the execution layer answers about a payload.
  *
  * ==A sum, because the status decides which other fields exist==
  *
  * The specification writes this as a record of three — a status string, a
  * nullable hash, a nullable message — and then constrains the combinations so
  * tightly that most of them never occur. Every literal response in the five
  * activated-fork engine documents at `ethereum/execution-apis` @ `6570b55` was
  * read, and the pairing is total:
  *
  *   - `VALID` always carries a hash, either `payload.blockHash`
  *     (`src/engine/paris.md:104`) or `forkchoiceState.headBlockHash`
  *     (`:213`, `:239`);
  *   - `INVALID` carries a hash, a zero hash, or nothing
  *     (`src/engine/paris.md:105-111`, `:175`, `:177`);
  *   - `SYNCING`, `ACCEPTED` and `INVALID_BLOCK_HASH` carry no hash at all
  *     (`:178`, `:180`, `:176`).
  *
  * Seventeen literal `{status: ..., latestValidHash: ...}` pairings occur across
  * those five documents and every one falls into the three rows above. The sweep
  * is calibrated: a status name that does not exist returns nothing from the
  * same pattern.
  *
  * A record would therefore admit a `SYNCING` carrying a latest valid hash and
  * a `VALID` carrying none, and neither is a response the protocol defines. The
  * cases below admit exactly the responses it does.
  *
  * ==A zero hash and no hash are different answers, and both are kept==
  *
  * On `INVALID` the specification distinguishes them: a zero hash means the
  * nearest valid ancestor is a proof-of-work block, and null means the client
  * *"cannot determine the ancestor"* (`src/engine/paris.md:109-111`). So
  * [[Invalid]] takes an `Option[Hash]` and the zero hash is an ordinary
  * `Some`, exactly as it is on [[ForkchoiceState]].
  *
  * ==This is the PRODUCING side, and the accepting side must not reuse it==
  *
  * Strictness is right here because an execution layer is deciding what to say,
  * and a combination the specification does not define is one it should be
  * unable to utter. The consensus layer reading that answer is under the
  * opposite obligation and behaves accordingly: `sigp/lighthouse` @
  * `e423a66763` accepts a non-null `latest_valid_hash` on a status the
  * specification pairs with `null`, warns, and drops the field — *"In the
  * interests of being liberal with what we accept, only raise a warning here"*
  * (`beacon_node/execution_layer/src/payload_status.rs:59-60`, and again at
  * `:74-75`).
  *
  * **So a decoder built on this type would refuse input a shipping consensus
  * layer deliberately tolerates.** That is not an argument for loosening it. It
  * is the reason the transport's inbound type is a different type: one side
  * makes an undefined combination unrepresentable, the other has to represent
  * it long enough to discard it, and collapsing them into one type forces
  * whichever obligation is written to lose.
  *
  * ==Which cases a verb may answer is the verb's restriction, not this type's==
  *
  * `engine_forkchoiceUpdated` narrows its own answers to `VALID`, `INVALID` and
  * `SYNCING` (`src/engine/paris.md:202-205`), and from `engine_newPayloadV2`
  * onward *"`INVALID_BLOCK_HASH` status value is supplanted by `INVALID`"*
  * (`src/engine/shanghai.md:105`). Both are restrictions a verb applies to this
  * type rather than facts about the type, and the verbs are not this phase's.
  * [[InvalidBlockHash]] is modeled because the first activated fork's
  * `engine_newPayloadV1` still requires it.
  *
  * ==The five cases are NOT equally stable under repetition==
  *
  * A caller that memoized a status by payload hash would be relying on
  * something the protocol declines to promise. Idempotence is a **MUST** only
  * across the validity boundary — *"a payload which validity status is
  * `INVALID (INVALID_BLOCK_HASH)` MUST NOT become `VALID` and vice versa"* —
  * and the same sentence then permits the opposite for the rest: *"Client
  * software MAY change payload status from `INVALID` to `SYNCING | ACCEPTED`"*
  * (`src/engine/paris.md:114`).
  *
  * So `VALID` and `INVALID` are stable answers about the same payload and the
  * other three are not, and a cache keyed on the payload alone cannot tell
  * which kind it is holding.
  *
  * ==Answering `INVALID_BLOCK_HASH` can reach a caller that cannot read it==
  *
  * `Consensys/teku` @ `488a89357e` models four cases and not five —
  * `ethereum/spec/.../ExecutionPayloadStatus.java:17-20` declares `VALID`,
  * `INVALID`, `SYNCING` and `ACCEPTED`, having dropped the one this type keeps.
  * So the status is one that consensus layer has no case to deserialize into.
  *
  * **That is the cost of keeping it and not a reason to drop it.** The first
  * activated fork's `engine_newPayloadV1` requires it, and a version this
  * client serves must be able to say it. What the cost buys is a decision for
  * whoever maps a status to the wire: from `engine_newPayloadV2` the
  * specification already supplants it with `INVALID`, so there is no version
  * on which both the requirement and the incompatibility are live at once.
  *
  * ==What is not modeled, and why==
  *
  * `PayloadStatusV2` appends an inclusion-list flag and drops
  * `INVALID_BLOCK_HASH` (`src/engine/bogota.md:57-64`), and the fork defining it
  * is drafted rather than scheduled. Adding it is a change to this sum, made by
  * whoever schedules that fork.
  */
enum PayloadStatus:

  /** Fully validated. The hash is the block this status is about.
    *
    * Non-optional because no response in the corpus pairs `VALID` with a null
    * hash, and a caller that had to handle the absent case would be writing a
    * branch the protocol cannot reach.
    */
  case Valid(latestValidHash: Hash)

  /** Rejected.
    *
    * @param latestValidHash
    *   the most recent valid block on this branch: a hash where one is known, a
    *   zero hash where it is a proof-of-work block, `None` where the client
    *   cannot determine it.
    * @param validationError
    *   optional detail. The specification makes supplying it a `MAY`
    *   (`src/engine/paris.md:116`), so its absence is not a defect.
    */
  case Invalid(latestValidHash: Option[Hash], validationError: Option[String])

  /** The data needed to validate is missing and is being fetched. */
  case Syncing

  /** Well-formed, on a branch that is not the canonical chain, and not fully
    * validated.
    */
  case Accepted

  /** The payload's own `blockHash` did not match the header rebuilt from it.
    *
    * Reachable on `engine_newPayloadV1` alone — see the verb-restriction note on
    * the type.
    */
  case InvalidBlockHash(validationError: Option[String])
