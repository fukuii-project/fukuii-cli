package org.fukuii.chainspec

import org.fukuii.bytes.UInt64

/** Every network this node knows how to run, found by chain id.
  *
  * ==The key is derived from the schedule, never supplied beside it==
  *
  * A schedule already knows which network it is for -- every entry names it,
  * and [[Schedule.of]] refuses a schedule whose entries disagree. Taking the
  * key from the schedule rather than pairing the two by hand removes the
  * failure where a schedule is filed under the wrong chain id, which is a
  * mistake nothing downstream could detect: every lookup would succeed and
  * answer with another network's rules.
  *
  * So the only way to build this wrongly is to offer two schedules for one
  * chain id, and that is refused.
  */
final class Registry private (val schedules: Map[UInt64, Schedule]):

  /** The schedule for a chain id, where this node has one. */
  def at(chainId: UInt64): Option[Schedule] = schedules.get(chainId)

object Registry:

  /** Why a set of schedules is not a registry. */
  enum Error:

    /** Two schedules claim one chain id, so a lookup would have to pick. */
    case DuplicateNetwork(chainId: UInt64)

  /** The registry these schedules form, or the first reason they do not.
    *
    * ==No schedules is a registry; no entries is not a schedule==
    *
    * The sibling constructor refuses an empty vector and this one accepts it,
    * and the difference follows from what each type promises rather than from
    * either being more careful.
    *
    * [[Schedule.at]] is TOTAL -- it answers for every height and timestamp, with
    * no option around it -- so a schedule with nothing in it could not keep that
    * promise, and [[Schedule.of]] refuses one for exactly that reason.
    * [[Registry.at]] is PARTIAL: it returns an option because a node not running
    * a network is an ordinary state rather than an error. An empty registry
    * answers `None` to every chain id, which is truthful -- this node runs no
    * networks -- and is the correct answer for a build that has configured none.
    *
    * So the pair is consistent at the level that matters: each constructor
    * refuses exactly the inputs its own lookup could not answer for.
    */
  def of(schedules: Vector[Schedule]): Either[Error, Registry] =
    val ids = schedules.map(_.network.chainId)
    ids.zipWithIndex
      .collectFirst { case (chainId, index) if ids.indexOf(chainId) < index => Error.DuplicateNetwork(chainId) }
      .toLeft(new Registry(schedules.map(schedule => schedule.network.chainId -> schedule).toMap))
