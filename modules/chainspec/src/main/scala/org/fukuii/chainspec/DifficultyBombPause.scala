package org.fukuii.chainspec

/** The half-open range of heights over which the exponential term's reference
  * point stands still.
  *
  * ==Why this is not a delay, which is the whole reason it is a second member==
  *
  * [[ConsensusRules.difficultyBombDelay]] moves the reference point back by a
  * fixed number of blocks, so the term it produces still doubles once per
  * period. This one holds the reference point at one height, so the term is
  * **constant** for the whole window and then resumes from where it was
  * suspended. No delay of any size makes an expression of the form
  * `2^((n - d) / p - 2)` constant across a range, so the two are different
  * rules rather than two spellings of one, and a build carrying only the delay
  * computes a growing term where the chain has a flat one.
  *
  * ==Two heights and not a height and a duration, which is the proposal's own
  * framing==
  *
  * ECIP-1010's `Constants` block names `pause_block` and `cont_block` and
  * *derives* the span from them --
  * `delay = (cont_block - pause_block) / 100000` -- at
  * `ethereumclassic/ECIPs` @ `f398567f4`, `_specs/ecip-1010.md`, status Final.
  * Two of the three implementations surveyed state the same pair:
  * `openethereum/parity-ethereum` @ `55c90d401` carries
  * `ecip1010_pause_transition` and `ecip1010_continue_transition` on
  * `EthashParams`, and `besu-eth/besu-etc` @ `eb4248c997` declares
  * `PAUSE_BLOCK` and `CONTINUE_BLOCK` in `ClassicDifficultyCalculators` and
  * derives `DELAY` from their difference. `ethereumclassic/core-geth` @
  * `4185df450` is the third and stores a height and a duration instead --
  * `ECIP1010PauseBlock` with `ECIP1010Length` -- deriving the continue height
  * where it needs one.
  *
  * ==The derived form is not merely less direct: it makes one axis untestable==
  *
  * This is the reason to keep the pair, and it is stronger than a preference
  * because it was measured rather than argued.
  *
  * Under a height-and-duration statement the continue point is the sum, so
  * moving the pause moves the end of the window with it while the span stays
  * put. The post-window reference never shifts, and the rule computes the same
  * difficulty at **every** height -- an equivalent mutant, which no vector at
  * any height can catch, in any corpus, ever. A client storing that form cannot
  * be tested on where its window begins.
  *
  * Under the pair, the same one-block shift shortens the window and is
  * observable. Measured here by seeding `pausedFrom` one block higher and
  * running the proof-of-work module: it fails, and the catchers are the cases
  * that compute the term *through* the window, where a shifted pause moves the
  * exponent.
  *
  * **Do not generalize that into "a sub-period error is invisible."** It is
  * not. What makes the shift unobservable *inside* the window under either
  * statement is that this network's pause height is itself a multiple of the
  * hundred-thousand period, so the height and the height plus one floor to the
  * same period. A pause at 3,099,999 would be observable in-window. **The
  * discriminator is the representation; the period alignment is a local
  * coincidence of this network's numbers.**
  *
  * ==A record rather than two members, because neither height means anything
  * alone==
  *
  * A rule set carrying one of the two states a window with no other end, which
  * is not a rule any network runs. core-geth's own configurator ships repair
  * code for exactly that state -- `chain_config_configurator.go` reconstructs a
  * missing pause height from a length that was set without one -- which is the
  * evidence that two independently-absent scalars is the shape to avoid rather
  * than a preference against it.
  *
  * @param pausedFrom
  *   the first height whose term is taken at the frozen reference point. At
  *   this height the frozen point and the height itself coincide, so the term
  *   equals what a network without the rule computes and the boundary is not
  *   observable from one side.
  * @param continuesFrom
  *   the first height past the window, whose term resumes from the reference
  *   point the window suspended. Exclusive: the last paused height is the one
  *   below it.
  */
final case class DifficultyBombPause(pausedFrom: BigInt, continuesFrom: BigInt)
