package org.fukuii.consensus.pos

import org.fukuii.bytes.Hash

/** The three blocks the consensus layer names when it tells the execution layer
  * which chain is canonical.
  *
  * ==The invariant this type carries and cannot enforce==
  *
  * **`safeBlockHash` must be `headBlockHash` or one of its ancestors** —
  * `ethereum/execution-apis` @ `6570b55` `src/engine/paris.md:65`, *"This value
  * MUST be either equal to or an ancestor of `headBlockHash`"* — and the same
  * requirement reaches `finalizedBlockHash` from the verb's side: an execution
  * layer must answer `-38002: Invalid forkchoice state` where the head is
  * `VALID` and either of the other two *"does not belong to the chain defined
  * by `forkchoiceState.headBlockHash`"* (`:219`).
  *
  * Ancestry is a question about a chain, and this value is three hashes. So the
  * invariant is stated here and checked by whoever holds the chain — it is a
  * refusal the verb makes, not a construction this type can decline. Stating it
  * on the type is what stops the check being invented at each call site or
  * skipped at one of them.
  *
  * ==A zero hash is a legitimate value, not an unset one==
  *
  * Both the safe and the finalized fields *"are allowed to have
  * `0x0000...0000` value unless transition block is finalized"* (`:68`). That is
  * why neither is an `Option`: the protocol has one way to say "none yet" and it
  * is a hash of zeros, so admitting a second way would give one fact two
  * spellings and leave a decoder to choose between them.
  *
  * ==It has never gained a version, while the verb carrying it gained three==
  *
  * Swept over every document under `src/engine` at the ref above, no
  * `ForkchoiceStateV2` or later exists: the only token the pattern finds is
  * `ForkchoiceStateV1`, and the drafted forks' documents cite that one. The
  * sweep discriminates — the same pattern shape finds `PayloadAttributesV2`
  * through `V5` in the same tree — so the absence is a reading of the corpus
  * rather than of the instrument. Over that same range
  * `engine_forkchoiceUpdated` reached V4.
  *
  * **That is the version-axis separation seen from the structure's side**, and
  * it is why this type carries no version ladder where [[ExecutionPayload]] and
  * [[PayloadAttributes]] both do.
  *
  * @param headBlockHash
  *   the head of the canonical chain.
  * @param safeBlockHash
  *   the head under the synchrony and honesty assumptions the consensus layer
  *   makes. Subject to the ancestry requirement above.
  * @param finalizedBlockHash
  *   the most recent finalized block.
  */
final case class ForkchoiceState(headBlockHash: Hash, safeBlockHash: Hash, finalizedBlockHash: Hash)
