package org.fukuii.chainspec

import org.fukuii.bytes.UInt64

/** One network, by the identifier the ecosystem keys networks on and the name
  * it goes by.
  *
  * ==Why the chain id and not the name is the identity==
  *
  * A name is a display concern and two projects may pick the same one; the
  * chain id is registered and unique, which is what makes it usable as a key. A
  * [[Registry]] therefore keys on [[chainId]] alone, and [[name]] exists so that
  * a label reads as something a human recognizes.
  *
  * Both are held on one value rather than split, because every consumer that
  * has one wants the other: an [[UpgradeId]] needs the name to render and the
  * id to scope, and an [[UpgradeSchedule]] needs the id to be found and the
  * name to be reported.
  *
  * ==This is deliberately NOT the devp2p network identifier==
  *
  * That is a separate number which a network may set independently of its chain
  * id -- Ethereum Classic runs chain id 61 while its peer-to-peer network
  * identifier is 1 -- and a type named for it here would take a name the
  * ecosystem has already given to something else. Nothing in this module needs
  * it.
  *
  * @param chainId
  *   the registered identifier. Read it from the registry at the moment a
  *   network is authored; it is not derivable from anything here.
  * @param name
  *   what the network is called. It is compared as well as displayed, so two
  *   entries spelling it differently are two networks as far as
  *   [[UpgradeSchedule.of]] is concerned -- which is the intended reading,
  *   because a schedule that mixes networks is the failure this module exists
  *   to prevent.
  */
final case class Network(chainId: UInt64, name: String)
