package org.fukuii.chainspec.proposals.ecip

import org.fukuii.chainspec.{Component, ProposalId}

/** ECIP-1039 -- where the floor divisions in [[Ecip1017]]'s ladder fall, which
  * decides the last digit of every reward from the second era onward.
  *
  * ==A rounding document exists because two clients had already parted over it==
  *
  * *"Monetary policy rounding specification"*, status Final, created
  * 2017-11-14, and its abstract scopes itself to *"eventual rounding issues
  * around ECIP-1017 Monetary Policy calculation"* over three figures -- the
  * winner's reward, the winner's reward for including uncles, and the uncle
  * miner's reward -- *"exclusively with reward calculation beginning with Era
  * 2"*. Its motivation is a split rather than a tidiness concern: *"ambiguity
  * in the specification may lead to different interpretations and
  * implementations that may result in a network split in further eras"*
  * (`ethereumclassic/ECIPs` @ `e36ef7f10166769aa3ac469aaf27ba5b0cacb198`,
  * `_specs/ecip-1039.md`).
  *
  * ==It mandates two roundings, and forbids the arithmetic that reaches each of
  * them the other way==
  *
  * The winner's reward rounds **once**: *"Block winner reward calculation for a
  * given era should be rounded down only once. This can be accomplished using
  * exponentiation"*, written `eraBlockReward * 4^era / 5^era`. Stepping the
  * reward down era by era is a different number, and the document says where it
  * first shows -- *"the discrepency would begin in era 22, where a single
  * rounding will yield 46116860184273879, and iterative rounding will yield
  * 46116860184273878"*.
  *
  * The thirty-second rounds **per ommer**: *"an uncles inclusion reward is
  * calculated using 1/32's (rounded down) of the block winner reward"*, over
  * the winner's reward already rounded, and the same figure is the uncle
  * miner's own reward from the second era. Multiplying by the count before
  * dividing once reaches a different number, and the document states that
  * boundary too, at the same era and for two ommers: the winner is paid
  * 48999163945790995 rather than 48999163945790996, and the ommers' miners
  * 2882303761517116 between them rather than 2882303761517117. Both figures are
  * the two-ommer totals the document tabulates, not a per-ommer share -- one
  * ommer's own thirty-second there is 1441151880758558, and it is the rounding
  * of that share, taken before any multiplication, which the two totals differ
  * by.
  *
  * ==Where the arithmetic is, and why nothing is written here==
  *
  * `org.fukuii.consensus.pow.EthashEngine` computes both: its winner's reward
  * raises the quotient and the divisor to the era before dividing once, and its
  * settlement divides that rounded amount by thirty-two before multiplying by
  * the ommer count, crediting each ommer's miner the same rounded thirty-second
  * separately. Neither is a value a rule set can hold -- they are where a
  * division falls inside an expression -- so adopting this document settles no
  * member of any facet and the rule set records the adoption alone.
  *
  * ==The two readings of the second rounding are both in the field==
  *
  * `ethereumclassic/core-geth` @ `4185df450` matches the document:
  * `params/mutations/rewards.go` divides the era's winner reward by thirty-two
  * in `getEraUncleBlockReward` and accumulates that per uncle in
  * `GetBlockWinnerRewardForUnclesByEra`. `besu-eth/besu-etc` @ `eb4248c997`
  * computes `winnerReward.plus(winnerReward.multiply(ommersSize).divide(32))`
  * in `ClassicBlockProcessor.getCoinbaseReward`, which is the form the document
  * names as the one that should not be used. The two agree at every era below
  * the twenty-second and part at it, so a corpus reaching no further cannot
  * tell them apart -- which is the reason to take the arithmetic from the
  * document rather than from whichever client is nearest.
  */
object Ecip1039:

  /** Adopting the document, which settles no member of any facet.
    *
    * Built from the general constructor rather than [[Component.evm]], which
    * would claim this document reaches the machine. It reaches no facet: where
    * a division falls inside the emission is not a value a rule set holds.
    */
  val component: Component = Component(ProposalId.Ecip(1039), identity)
