package org.fukuii.storage

/** One of the independent axes a [[RetentionVector]] carries a bound for.
  *
  * The set below is the decomposition the field converges on: state history
  * and commitment history are kept as separate axes because a chain config
  * may need to retain the ability to prove state to a different depth than
  * it retains the state values themselves — a split at least one production
  * client already ships, at two different default depths for the two
  * different questions.
  *
  * Which of these axes is exposed to an operator as a configuration flag is
  * an open question left to whichever layer will make it. This type exists
  * to make the axes independently expressible, not to answer that question.
  */
enum RetentionCategory:
  case Commitment, StateHistory, Bodies, Receipts, TransactionIndex, LogIndex

/** How long a [[RetentionCategory]] is retained for.
  *
  * A depth is not always the right SHAPE of bound. A block-depth chosen to
  * approximate a finality signal is wrong precisely during a finality
  * stall — the gap between the head and the last finalized block is
  * ordinarily small, and unbounded exactly when the data a reorg recovery
  * needs must not be pruned. [[RetentionBound.FinalityWatermark]] exists so
  * that case is expressible as its own bound rather than as a depth guess.
  */
enum RetentionBound:
  case Depth(blocks: Long)
  case FinalityWatermark
  case Unbounded

/** Independent retention depths, one per [[RetentionCategory]].
  *
  * Deliberately not a mode (`Archival | Hot`) and not a sum over two
  * backends — production clients that model this at all converge on
  * independent axes rather than a small named set of combinations, and a
  * named combination cannot express one the field has not shipped yet.
  *
  * A type with no behavior: nothing in Phase 1 enforces a [[RetentionVector]]
  * against a [[KeyValueStore]], because nothing in Phase 1 prunes.
  *
  * @param bounds
  *   the bound declared for each category this vector states an opinion
  *   about. A category absent from `bounds` reads as
  *   [[RetentionBound.Unbounded]] through [[apply]] — "no bound stated" is
  *   the safe reading, not "retain nothing".
  */
final case class RetentionVector(bounds: Map[RetentionCategory, RetentionBound]):
  def apply(category: RetentionCategory): RetentionBound =
    bounds.getOrElse(category, RetentionBound.Unbounded)

object RetentionVector:

  /** "archive" is the unbounded value on every axis, not a distinct mode. */
  val Archive: RetentionVector =
    RetentionVector(RetentionCategory.values.toList.map(category => category -> RetentionBound.Unbounded).toMap)
