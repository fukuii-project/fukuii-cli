package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}

/** EIP-7251 -- consolidating two validators into one.
  *
  * ==Shaped exactly as [[Eip7002]], and that is the whole of what to know==
  *
  * `org.fukuii.execution.SystemCall.Target.ConsolidationRequests` is the
  * account, the call is checked the same way, its queue is written by ordinary
  * transactions the same way, and its records are filed under a different type
  * byte. **The two differ in their address and in that byte, and in nothing
  * else** -- so a build treating them as one mechanism with two parameters is
  * reading them correctly, and a divergence found in one is a finding about the
  * other until checked.
  */
object Eip7251:

  /** Adopting the document, which over a rule set changes nothing.
    *
    * [[Eip6110]]'s component states why an empty delta is still recorded and
    * what would give it a real one.
    */
  val component: Component = Component(ProposalId.Eip(7251), identity)
