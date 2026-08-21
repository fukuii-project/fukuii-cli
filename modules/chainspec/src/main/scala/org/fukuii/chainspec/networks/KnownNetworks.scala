package org.fukuii.chainspec.networks

import org.fukuii.chainspec.{Network, Registry, UpgradeSchedule}

/** The networks authored under this package, assembled into one registry.
  *
  * ==One place a chain id is resolved, so a second one cannot disagree==
  *
  * [[Registry]] takes each schedule's chain id from the schedule itself, so the
  * only way to build it wrongly is to offer two schedules for one id. Assembling
  * it once here is what makes that the only way: a caller that built its own
  * registry from a subset would answer some lookups and silently miss others,
  * and nothing about the answer it did give would look wrong.
  *
  * A network is added by adding a line to [[registry]]. Nothing else enumerates
  * them, so there is no second list to keep in step.
  */
object KnownNetworks:

  /** Why the networks authored here do not form a registry.
    *
    * Named per network rather than left as a bare union of the two underlying
    * error types: with more than one network authored, *which* one failed is
    * the first thing a reader needs and a union cannot carry it.
    */
  enum Error:

    /** One network's entries are not a schedule. */
    case Unschedulable(network: Network, reason: UpgradeSchedule.Error)

    /** The schedules are individually valid and collide with each other. */
    case Unregisterable(reason: Registry.Error)

  /** Every network this build knows how to run, or the first reason they do not
    * form a registry.
    *
    * The failure is returned rather than thrown for the reason
    * [[ethereum.Mainnet.schedule]] gives: a throwing `val` fails at class
    * initialization and surfaces somewhere unrelated.
    */
  val registry: Either[Error, Registry] =
    for
      ethereumSchedule <- ethereum.Mainnet.schedule.left
        .map[Error](reason => Error.Unschedulable(ethereum.Mainnet.network, reason))
      classicSchedule <- ethereumclassic.Mainnet.schedule.left
        .map[Error](reason => Error.Unschedulable(ethereumclassic.Mainnet.network, reason))
      assembled <- Registry
        .of(Vector(ethereumSchedule, classicSchedule))
        .left
        .map[Error](Error.Unregisterable.apply)
    yield assembled
