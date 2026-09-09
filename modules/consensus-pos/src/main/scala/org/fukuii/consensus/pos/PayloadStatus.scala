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
