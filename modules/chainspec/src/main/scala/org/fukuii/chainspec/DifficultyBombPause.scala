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
