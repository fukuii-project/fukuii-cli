package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}

/** EIP-7002 -- withdrawals a validator's execution-layer key can trigger.
  *
  * ==A CHECKED system call, which is what distinguishes it from the calls
  * before it==
  *
  * `org.fukuii.execution.SystemCall.Target.WithdrawalRequests` is the account
  * and `runChecked` is the kind: an empty target or a call that does not end
  * normally refuses the block, and what it returns becomes a record in the
  * block's request list. Every system call this build had before Prague
  * discarded its outcome entirely.
  *
  * ==Its queue is written by ordinary transactions, which is why it has no state
  * tests==
  *
  * A sender calls the contract; the contract queues the request; the block's own
  * call at the end drains the queue. So the only published evidence for this
  * document is a block that executes those transactions and then makes that
  * call -- nothing about it is observable from a transaction run alone.
  */
object Eip7002:

  /** Adopting the document, which over a rule set changes nothing.
    *
    * The call is driven by [[Eip7685]]'s container and its address is fixed by
    * this document, so no rule is left for a fork to switch. [[Eip6110]]'s
    * component states in full why an empty delta is still recorded and what
    * would give it a real one.
    */
  val component: Component = Component(ProposalId.Eip(7002), identity)
