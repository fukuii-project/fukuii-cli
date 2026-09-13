package org.fukuii.blockchaintests

import org.fukuii.chainspec.certification.TransactionRefusalVocabulary
import org.fukuii.consensus.{BlockFault, HeaderFault}
import org.fukuii.evm.fixtures.ExpectedRejection

/** The corpus's names for a refused block, against the faults this build
  * refuses one for.
  *
  * ==A refusal is compared by name, and a name this does not hold matches
  * nothing==
  *
  * A block refused for some other rule than the one the case states is a
  * disagreement, even though both answers agree the block is invalid: the
  * reason is the only thing that separates a validator applying the rule the
  * case exercises from one refusing the block by accident. So a stated name
  * this vocabulary lacks is a divergence and never a skip.
  * `besu-eth/besu` @ `b330564a94` compares the same way and fails the same way,
  * where a key its mapping file lacks matches no message
  * (`ethereum/referencetests/.../BlockExceptionMatcher.java:127-137`).
  *
  * ==Names, per rule, rather than a message per stage==
  *
  * That file maps most header names onto one message, `"Header validation
  * failed"`, so any header rule satisfies any header name there. The faults
  * here carry one case per rule, so each name is held against the one rule the
  * ids' own definitions give it (`ethereum/execution-specs` @ `0cc100eb1`,
  * `packages/testing/src/execution_testing/exceptions/exceptions/block.py`),
  * and a header refused for its base fee does not satisfy a case stating its gas
  * limit.
  *
  * ==Two halves, and only one of them lives here==
  *
  * A block refused because a transaction it carries is refused states the
  * transaction's name, and that name is
  * [[org.fukuii.chainspec.certification.TransactionRefusalVocabulary]]'s, which
  * the state tier reads too. The names for a rule over the block itself have
  * one reader, this tier, and sit beside it.
  *
  * ==An entry arrives with the first published case that states it==
  *
  * So every name below is exercised by a case this build certifies, and a name
  * mapped to the wrong rule is caught by that case rather than waiting for one.
  */
object BlockRefusalVocabulary:

  /** Whether `fault` is a refusal under any of the names `expected` states. */
  def satisfies(expected: ExpectedRejection, fault: BlockFault): Boolean =
    expected.stated.exists(name => refusesUnder(name, fault))

  /** The names `expected` states that no half of the vocabulary holds.
    *
    * Reported beside a divergence, because a case refused for the right rule
    * under a name this build has never mapped reads, without it, exactly like a
    * validator refusing for the wrong rule.
    */
  def unmapped(expected: ExpectedRejection): Set[String] =
    expected.stated.filterNot(name => blockRules.contains(name) || TransactionRefusalVocabulary.byName.contains(name))

  private def refusesUnder(name: String, fault: BlockFault): Boolean =
    blockRules.get(name).exists(rule => rule(fault)) ||
      TransactionRefusalVocabulary.byName.get(name).exists { reason =>
        fault match
          case BlockFault.TransactionRefused(rejection) => rejection.reason == reason
          case _                                        => false
      }

  /** A rule over the block itself, by the name the corpus gives it.
    *
    * `INVALID_GASLIMIT` is defined as a gas limit not matching the limit formula
    * calculated from the parent, and this build's one rule for that is the
    * bound, floor included. `INVALID_BASEFEE_PER_GAS` is defined as a base fee
    * calculated incorrectly, which is the charge a parent requires -- not a
    * charge missing where a fee market requires one, which is a different rule.
    */
  private val blockRules: Map[String, BlockFault => Boolean] =
    Map(
      "BlockException.INVALID_GASLIMIT" -> {
        case BlockFault.Header(HeaderFault.GasLimitOutOfBounds(_, _)) => true
        case _                                                        => false
      },
      "BlockException.INVALID_BASEFEE_PER_GAS" -> {
        case BlockFault.Header(HeaderFault.BaseFeeMismatch(_, _)) => true
        case _                                                    => false
      }
    )
