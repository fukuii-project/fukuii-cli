package org.fukuii.chainspec.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Network, UpgradeRules, UpgradeSchedule}
import org.fukuii.chainspec.networks.{KnownNetworks, ethereum, ethereumclassic}
import org.fukuii.chainspec.proposals.eip.{Eip1153, Eip4844, Eip5656, Eip6780, Eip7516}
import org.fukuii.evm.EvmRules

/** The published corpora this layer is certified against, run once and reported
  * as counts.
  *
  * ==A tier is a directory and a fork key, and neither implies the other==
  *
  * The generated tier partitions by fork, so a directory names one; the legacy
  * tier does not, so one directory answers for every fork it carries an
  * expectation under and is read once per fork. A report is therefore keyed by
  * both, and the count of reports is not the count of directories.
  *
  * ==Computed once, because running them is the expensive part==
  *
  * Every spec that asserts something about coverage asserts it about the same
  * run, so the run happens on first demand and the reports are shared. Two
  * suites asking two questions of two different runs could disagree about how
  * many cases there were.
  */
object CertificationCorpora:

  /** Every report, or nothing at all when the harness cannot be assembled.
    *
    * The distinction is the point: a harness that answered with empty reports
    * would be indistinguishable from one that found nothing wrong. `None` is
    * caught loudly by the first case in `CertificationCorporaSpec`, which is
    * why every step below may collapse into it rather than reporting its own
    * failure -- a broken registry is separately and loudly asserted by
    * `KnownNetworksSpec`, so nothing here has to restate that diagnosis.
    *
    * ==The rules come from the registry, not from a named composition==
    *
    * Each corpus below is bound to a NETWORK and a HEIGHT, and what runs is
    * whatever that network's schedule says is in force there. Naming a
    * composition instead would certify the compositions and say nothing about
    * the schedule, because a composition is right by construction while an
    * activation is an external fact nothing here can derive.
    */
  lazy val reports: Option[Vector[CorpusReport]] =
    for
      root <- FixtureCorpus.root
      registry <- KnownNetworks.registry.toOption
      ethereumSchedule <- registry.at(ethereum.Mainnet.network.chainId)
      classicSchedule <- registry.at(ethereumclassic.Mainnet.network.chainId)
    yield assemble(root, ethereumSchedule, classicSchedule)

  /** The legacy hand-written interpreter tier: an invocation stated directly,
    * with no transaction around it.
    */
  val LegacyVmCorpus: String = "legacytests Constantinople/VMTests"

  /** The legacy hand-written state tier, of which only the cases carrying a
    * Frontier expectation are executable here.
    *
    * The fork is named in the report because this directory is read more than
    * once. A general state test states expectations under several forks at
    * once, so the same 2394 files answer a different question per fork asked --
    * and a report naming only the directory could not say which was asked.
    */
  val LegacyFrontierStateCorpus: String = "legacytests Constantinople/GeneralStateTests at Frontier"

  /** The same directory, read for EIP-150's expectations instead.
    *
    * **These cases are not a subset of the suites named for that proposal, and
    * the difference is thirteenfold.** Four suites carry it in their names and
    * hold 81 cases between them; the fork key is carried by 650 cases across the
    * tier, expanding to 1096 runnable combinations. The suite name under-reports
    * because a general state test is not organized by the fork it exercises,
    * and the post key is what the reader dispatches on -- which is the invariant
    * to search for rather than the name.
    */
  val LegacyEip150StateCorpus: String = "legacytests Constantinople/GeneralStateTests at EIP150"

  /** The same directory a third time, read for the expectations it files under
    * the name EIP-158.
    *
    * ==The key is the superseded document's number, and that is the corpus's
    * spelling rather than a mistake to correct==
    *
    * EIP-161 supersedes EIP-158 as that document's own *invariant-preserving
    * alternative*, and EIP-607 lists 161 and not 158. The clients and the
    * corpora kept the earlier number for the activation all the same:
    * `ethereum/go-ethereum` @ `6bb0588ad` names the field `EIP158Block` and
    * `besu-eth/besu` @ `c2addd94` names the genesis key `eip158Block`, both
    * gating EIP-161. The reader dispatches on the post key, so the key is the
    * invariant to match -- asking for the number the upgrade actually includes
    * would find nothing and report all 2394 files as stating no expectation.
    *
    * ==What this tier adds over the generated one, which is not what it looks
    * like==
    *
    * Not the mere fact of EIP-161: the generated tier already discriminates on
    * the clearing clause, and does so in 48 cases. What this one adds is scale
    * and a second route. It decides 74 cases, and 28 of its cases hold an
    * account that is **already empty when the transaction begins** -- the
    * clause's own case, which the generated corpus does not state anywhere and
    * can only approach through an emptiness it created itself.
    *
    * It is also **the only tier the matrix measures for EIP-170 where that
    * bound on deployed code decides anything**, and it does so once. Against that it
    * reaches EIP-155 not at all, because it publishes no signed bytes for any
    * case and so never asks a signature which chain it names -- which is what
    * makes the two tiers near-complements rather than one a subset of the other.
    * The coverage matrix in `CertificationCorporaSpec` is where that is measured.
    *
    * Most of its cases publish only a post-state root and no per-account
    * expectation, so a clause that fired when it should not surfaces as a root
    * mismatch rather than as an account a reader can see in the file.
    */
  val LegacyEip158StateCorpus: String = "legacytests Constantinople/GeneralStateTests at EIP158"

  /** The generated state tier, from the tests@v20.0.1 release. */
  val GeneratedStateCorpus: String = "execution-specs-fixtures state_tests/for_frontier"

  /** The same tier filled for the next fork, run under that fork's rules.
    *
    * The corpus is partitioned per fork, so certifying against another one is a
    * directory, a name to read expectations under, and a set of rules -- and
    * nothing else. That it costs no more than this is the fork seam's claim
    * about itself, tested here rather than asserted.
    */
  val GeneratedHomesteadCorpus: String = "execution-specs-fixtures state_tests/for_homestead"

  /** The same tier filled for the fork after that.
    *
    * **The directory is partitioned by the fork a test was FILLED FOR, not by
    * the fork it was authored at**, so it holds families named for much later
    * proposals evaluated under these rules. What it certifies is therefore every
    * published state test filled for this fork, which is a different set from
    * every test of this fork and is the only claim the directory supports.
    */
  val GeneratedTangerineWhistleCorpus: String = "execution-specs-fixtures state_tests/for_tangerinewhistle"

  /** The same tier filled for the fork after that.
    *
    * ==Which of the fork's four proposals this corpus can actually decide==
    *
    * The coverage matrix in `CertificationCorporaSpec` holds the figures and the
    * method. Two of them bear on why this tier exists: it is **the only censused
    * tier that reaches EIP-155 at all**, deciding 500 of its 537 cases on it,
    * and it is blind to EIP-170's bound on deployed code, which the older tier
    * reaches instead. So this corpus does not certify the fork by itself, and
    * neither does the other one.
    *
    * ==Reading the files gives the wrong answer for the first of those, and
    * gives it convincingly==
    *
    * No case here holds an empty account in its pre-state and none publishes one
    * in a post state, which reads as a corpus with nothing for the clause to
    * delete. It is the reverse: the empty accounts are made during execution,
    * where a zero-value call reaches a precompile or an address with no account,
    * and **the absence of them afterwards is what the clause working looks
    * like.** Switching it off moves 48 of the 537 verdicts.
    *
    * So a count of empty accounts in the JSON measures the corpus's expected
    * OUTPUT and is not evidence about its coverage, which is why the assertion
    * beside this is a differential run and not a property of the files.
    */
  val GeneratedSpuriousDragonCorpus: String = "execution-specs-fixtures state_tests/for_spuriousdragon"

  /** The legacy tier read a fourth time, for the expectations it files under
    * the name of the fork after that.
    *
    * ==Nearly the whole directory answers here==
    *
    * 2297 of the 2394 files carry a section under this key against 579 under
    * EIP-158, and they expand to 4899 runnable combinations. The reason is the
    * one the EIP-158 tier already states -- a general state test states
    * expectations for every fork it was authored against, and the later the
    * fork the more of the corpus has one. The 97 files that state nothing at
    * all here are what the skip count records.
    *
    * **Scale is not coverage, and at this fork the gap between the two is
    * wide.** Which of the upgrade's proposals this tier can decide is measured
    * in `CertificationCorporaSpec` by removing a proposal and rerunning: it
    * decides three of the nine by a wider margin than the generated tier, four
    * by a narrower one, and two not at all. A count of files predicts none of
    * that.
    */
  val LegacyByzantiumStateCorpus: String = "legacytests Constantinople/GeneralStateTests at Byzantium"

  /** The generated tier filled for the same fork.
    *
    * ==The only tier in this harness that publishes a receipt stating a
    * status==
    *
    * Its 1845 cases carry 1834 receipts and every one of them states `status`,
    * where every receipt in the four earlier directories states `postState` --
    * so it is the first corpus here whose receipts state the fork's new first
    * field at all. The legacy tier publishes no receipt for any case at any
    * fork, all 31291 of its post entries carrying exactly a hash, its indexes
    * and its logs, which is why registering it at this fork does not reach that
    * proposal however many cases it carries.
    */
  val GeneratedByzantiumCorpus: String = "execution-specs-fixtures state_tests/for_byzantium"

  /** The generated tier at the fork above Byzantium, under the ONLY name that
    * tier gives it.
    *
    * ==There is no `for_constantinople` directory, and that is the corpus
    * agreeing with the network rather than an omission==
    *
    * The release publishes sixteen `state_tests/for_*` directories and
    * `for_constantinople` is not among them, while `for_constantinoplefix` is.
    * Ethereum mainnet activated Constantinople and Petersburg at one block, so
    * the rules the generator can fill for are the ones with EIP-1283 removed --
    * which is what `ConstantinopleFix` names.
    *
    * **So this tier cannot reach EIP-1283 at all, in either direction.** It
    * certifies the four proposals that survived, and the fifth is certified
    * against its own document's published table instead.
    */
  val GeneratedConstantinopleFixCorpus: String = "execution-specs-fixtures state_tests/for_constantinoplefix"

  /** The legacy tier at the same fork, which unlike the generated one publishes
    * BOTH labels.
    *
    * `Constantinople` and `ConstantinopleFix` are separate post-state keys in
    * the same files, and the two are not synonyms: the first states what the
    * fork was specified to be, EIP-1283 included, and the second what mainnet
    * ran. **The tier uses `Petersburg` zero times**, which is why nothing here
    * is registered under that name however the schedule spells the upgrade.
    */
  val LegacyConstantinopleFixStateCorpus: String = "legacytests Constantinople/GeneralStateTests at ConstantinopleFix"

  /** The same files at the OTHER label -- the rules Ethereum mainnet never ran.
    *
    * ==The one tier in this harness that certifies a rule set no height
    * resolves to==
    *
    * `Upgrades.constantinople` is unreachable through the schedule by
    * construction, so it is passed here directly rather than through `rulesAt`.
    * That is deliberate and is the point: this corpus is the reason holding
    * that value is worth anything. Without it the specified-but-never-run rule
    * set would be a composition nothing could falsify.
    *
    * **It is also the only tier here that exercises EIP-1283**, since the
    * generated release does not fill for it and no other network in this build
    * adopts it.
    */
  val LegacyConstantinopleStateCorpus: String = "legacytests Constantinople/GeneralStateTests at Constantinople"

  /** The generated tier filled for the fork above that one.
    *
    * ==Three of its six proposals are named by no directory here, and the
    * differential reaches them anyway==
    *
    * Only three subdirectories carry this fork's name: the chain-identifier
    * operation with 1 case, the compression native with 73, and net gas
    * metering with 774. Nothing here is named for the curve repricing, the
    * trie-size repricing or the calldata repricing -- and the coverage matrix
    * in `CertificationCorporaSpec` decides all three anyway, at 191, 83 and
    * 1382 cases. Taken together those three rows draw on families named for
    * three earlier forks, on this fork's own two largest directories, and on
    * one directory named for no fork at all. That is the same reading the
    * matrix already records at the fork below: a corpus reaches a rule wherever
    * its cases happen to exercise it, which no reading of a directory listing
    * recovers.
    *
    * ==What the repricings reach is not what the additions reach==
    *
    * Four of the six move a price rather than adding an operation, so their
    * rows count cases that merely SPEND the figure, not cases that mention a
    * new one. The calldata repricing is the extreme of it: it is read once per
    * transaction, before any code runs, so no property of a case's code bounds
    * it. What bounds it is the envelope -- 1436 of the 2075 cases carry a
    * non-zero calldata byte, and the row is 1382, the shortfall being cases
    * whose verdict some other rule had already settled.
    *
    * ==Every case here signs, and nearly all of them name a chain==
    *
    * Of the 2075 cases, 2004 sign for chain 1 and 67 sign unprotected; the
    * remaining four are one signature naming another chain, one malformed `v`,
    * and two typed envelopes this fork does not admit. So the substitution the
    * Tangerine Whistle tier below performs is unavailable here for the reason
    * that tier's own note gives -- through another network nearly the whole
    * corpus would be refused as signed for chain 1, and the refusals would be
    * the harness disagreeing with itself rather than a divergence.
    */
  val GeneratedIstanbulCorpus: String = "execution-specs-fixtures state_tests/for_istanbul"

  /** The generated tier filled for the first fork whose blocks may carry a
    * second transaction format.
    *
    * ==The first tier here that runs a TAGGED transaction rather than refusing
    * one==
    *
    * Every directory below this one carries typed envelopes only as cases
    * asserting that the fork of the day refuses them -- two at the fork below,
    * both `TYPE_n_TX_PRE_FORK`. Here 154 of the 2742 entries carry a `0x01`
    * envelope the rules admit, execute, and publish a receipt for whose octets
    * begin with the same tag. So this is the first corpus in the harness that
    * can disagree about a typed transaction at all, in either direction:
    * whether it is admitted, what its declaration is charged, and what its
    * receipt encodes to.
    *
    * A further 144 typed entries here ARE refusals, and they are not a
    * duplicate of the fork below's two: the fork admits `0x01`, so what those
    * assert is a typed transaction refused for something other than its format.
    *
    * ==What the corpus reaches beyond the fork it is named for==
    *
    * The directory is partitioned by the fork a test was FILLED FOR rather than
    * by the fork it was authored at -- the reading the Tangerine Whistle tier
    * above already states -- and here it is what supplies the modular
    * exponentiation row. Eleven files of this directory name that native in
    * their path against one in the directory filled for the fork below, and ten
    * of the eleven sit under `osaka/eip7883_modexp_gas_increase`, a family
    * named for a later fork's repricing of the same native and filled at these
    * rules. Measured with a path match calibrated against a token no path
    * carries.
    */
  val GeneratedBerlinCorpus: String = "execution-specs-fixtures state_tests/for_berlin"

  /** The generated tier filled for the first fork that charges a base fee.
    *
    * ==The first corpus here whose transactions state a CEILING rather than a
    * price==
    *
    * 268 of its entries carry EIP-1559's format, which states the most it will
    * pay in total and the most it will pay above the block's charge rather than
    * one price. The directory below this one carries exactly one entry of that
    * format and cannot reach any of it: the fork does not admit the tag, so the
    * entry is refused for its format before a fee is read at all.
    *
    * ==What the fork reaches is the whole tier and not those 268==
    *
    * The charge is levied on every transaction whatever format it states, and
    * the producer is credited the price LESS that charge -- so a tier run
    * without the fee market settles a different beneficiary balance, and
    * therefore a different root, on every case that spends gas at all. The
    * coverage matrix measures it at 2858 of these 3010 entries against 268
    * carrying the format, which is the widest gap in this harness between what
    * a proposal is written into and what it decides.
    */
  val GeneratedLondonCorpus: String = "execution-specs-fixtures state_tests/for_london"

  /** The generated tier filled for the first fork whose blocks are not mined.
    *
    * ==The corpus states both randomness readings as ZERO, so this tier cannot
    * decide the proposal the fork is named for==
    *
    * Measured over all 134 files of the directory at the `tests@v20.0.1`
    * release: every entry states `currentDifficulty` `0x00` and `currentRandom`
    * thirty-two zero bytes, one distinct pair across the whole directory. The
    * one operation EIP-4399 changes reports the first below the fork and the
    * second above it, and both are zero here -- so a machine reading the wrong
    * one of the two agrees with every case in this corpus. **That is a
    * structural blindness of the tier and not a shortfall to be closed by
    * running more of it**, and what would decide it is a corpus stating the two
    * differently, which the directory filled two forks later does.
    *
    * What this tier DOES reach is the rest of the fork: it is filled under a
    * rule set whose difficulty is not mined, and every case's balances, gas and
    * root are settled against it.
    */
  val GeneratedParisCorpus: String = "execution-specs-fixtures state_tests/for_paris"

  /** The generated tier filled for the fork above that one.
    *
    * The largest generated directory below the one that carries the ported
    * legacy suite, and the first whose rules admit an operation pushing a
    * constant, bound the initcode a transaction may deploy, and start the
    * producer's account warm.
    *
    * **Its fourth proposal is not reachable here at all.** A withdrawal is
    * credited by a block rather than by a transaction, and a state fixture
    * settles one transaction against a block it is handed -- so no case in this
    * directory can state one. That is the same structural bound the coverage
    * matrix already records for a producer's credit and for the next block's
    * difficulty, arriving at a third proposal.
    */
  val GeneratedShanghaiCorpus: String = "execution-specs-fixtures state_tests/for_shanghai"

  /** What every corpus below that names a composition is read under, written
    * once so five names cannot drift apart.
    *
    * It gains a document at every phase that adopts one, and each such phase
    * must change it -- a name stating the rules is only worth having while it
    * states them in full.
    *
    * ==WHOEVER DELETES THIS ALSO FOLDS A SPEC AWAY, and that is the note this
    * constant exists to carry==
    *
    * This goes when the fork is whole and these directories resolve through the
    * schedule at an activation instead. At that moment
    * [[org.fukuii.chainspec.certification.CancunCompositionCertificationSpec]]
    * stops being the right shape, and **its differentials belong in
    * `CertificationCorporaSpec`'s coverage matrix rather than beside it.** They
    * are held apart today for one reason and one only: every matrix row is
    * resolved through a schedule at a height, and these are not. Resolve them
    * through the schedule and the distinction is gone, so they become ordinary
    * rows -- and that spec's `ordinary`/`heavy` split, keyed on what a rerun
    * costs, is then what decides which run they belong to.
    */
  private val ComposedForCancun: String =
    " at Shanghai with EIP-1153, EIP-5656, EIP-6780, EIP-4844's blob-gas accounting and EIP-7516"

  /** The generated tier's memory-copying directory, read under the fork below
    * with the fork's built proposals added.
    *
    * ==The rules are named directly, and NOT for the reason the other such
    * corpus here is==
    *
    * [[LegacyConstantinopleStateCorpus]] names a composition directly because
    * its rule set is unreachable by construction. This one is named directly
    * for a different reason, and the two must not be read as the same case: the
    * fork these files are filled for is being BUILT, one proposal at a time,
    * and a rule set carrying some of its six is not that fork. There is no
    * `Upgrades.cancun` to resolve to yet, and writing one holding a subset
    * would put a value in the chain configuration whose NAME asserted a
    * completeness it did not have -- which is the defect a schedule entry ahead
    * of its rules is, one layer down.
    *
    * So the name says which rules these files are read under, in full, and it
    * stops being accurate on the commit that changes them. That is a property a
    * corpus resolved through a schedule does not need and this one does.
    *
    * ==Reading a Cancun directory under something other than Cancun is only
    * sound where the directory cannot tell==
    *
    * These files are filled for the whole fork, not for the proposal they are
    * named after, so nothing about the directory guarantees that its cases turn
    * on the proposals this composition carries. What guarantees it is the run:
    * every case here agrees, and
    * [[org.fukuii.chainspec.certification.CancunCompositionCertificationSpec]]
    * records what that does and does not establish.
    */
  val GeneratedCancunMemoryCopyCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/cancun/eip5656_mcopy" + ComposedForCancun

  /** The legacy static suite's transient-storage directory, ported and filled
    * for this fork, under the same rules.
    *
    * ==A SECOND independent directory for the same proposal, and it was the
    * only one for a while==
    *
    * `cancun/eip1153_tstore` is the directory a reader looks for first, and it
    * is now beside this one. It could not be admitted while the rules carried
    * two of the fork's six documents: two of its 123 cases were decided by a
    * third that the composition did not hold, so admitting it would have meant
    * either a failing tier or a per-corpus allowance for known divergences --
    * and such an allowance, once it exists, absorbs a real divergence as readily
    * as an expected one.
    *
    * These 4 files carry 48 cases of the same subject and every one of them
    * agrees. **They are worth keeping now that the other directory is here**,
    * for the reason the memory-copying pair below is kept: a different
    * generator over a different source suite, so an error in one set of fixtures
    * is not an error in both.
    *
    * ==It overlaps the tier a later phase wires, and that is the ordinary shape
    * here rather than a collision==
    *
    * The `ported_static` tree is read again at the whole fork's rules once that
    * fork exists, which is a different question over the same files -- exactly
    * what the legacy tier already does at five forks. A name stating the rules
    * is what keeps the two apart.
    */
  val PortedStaticCancunTransientStorageCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/ported_static/stEIP1153_transientStorage" + ComposedForCancun

  /** The same suite's memory-copying directory, under the same rules.
    *
    * A second independent directory for the same proposal, and the reason to
    * carry it rather than rest on the one above: 106 cases against 99, filled
    * by a different generator from a different source suite, so an error in
    * either one's fixtures is not an error in both.
    */
  val PortedStaticCancunMemoryCopyCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/ported_static/stEIP5656_MCOPY" + ComposedForCancun

  /** The generated tier's directory for the destruction proposal, under the same
    * rules.
    *
    * ==It is the first Cancun directory here whose subject is a RULE rather than
    * an operation, and that changes what a case can look like==
    *
    * The two directories above are decided by a byte being in the table: a case
    * that never executes it cannot tell. This one is decided by a condition
    * every destruction consults, so its cases divide into those whose account
    * this transaction created and those whose it did not -- and both halves
    * answer differently under the two rule sets. The differential in
    * `CancunCompositionCertificationSpec` is what measures that rather than
    * assuming it.
    */
  val GeneratedCancunSelfDestructCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/cancun/eip6780_selfdestruct" + ComposedForCancun

  /** The generated tier's transient-storage directory, admitted here now that the
    * rules can answer it in full.
    *
    * ==It was measured, left out, and is now readmitted -- which is the shape a
    * deliberate absence should take==
    *
    * Under the two-proposal composition this directory was 121 of 123: both
    * divergences sat in `tstorage_selfdestruct/reentrant_selfdestructing_call.json`
    * and both were an account this build removed that the fixture expects to
    * survive, which is the destruction proposal's rule and not transient
    * storage's. It was excluded rather than admitted with a per-corpus allowance
    * for known divergences, because such an allowance absorbs a real divergence
    * as readily as an expected one.
    *
    * **So the readmission is this phase's evidence and not a bookkeeping
    * change.** Nothing about the census total says it: the suite counts test
    * cases and this corpus is one table-driven row, so admitting it moves the
    * census MAP and the corpus count, and moves no test total at all.
    */
  val GeneratedCancunTransientStorageCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/cancun/eip1153_tstore" + ComposedForCancun

  /** The generated tier's directory for the blob-charge operation, under the
    * same rules.
    *
    * ==The smallest directory here, and the one whose limits have to be stated
    * loudest==
    *
    * Two files and four cases, against directories of a hundred and more. Its
    * subject is an operation that reports a number, and **every case in it
    * states a zero excess** -- so the charge it reports is the minimum in all
    * four, and the whole directory is satisfied by a build that pushes the
    * constant one. `org.fukuii.chainspec.certification.CancunBlobGasCertificationSpec`
    * measures that rather than describing it, and
    * `org.fukuii.evm.BlobGasPriceSpec` is what certifies the arithmetic the
    * directory cannot reach.
    *
    * **It is still worth registering**, for what a state tier does that a unit
    * spec cannot: the operation is reached through a published transaction, at a
    * published gas limit, against a published post-state root -- so a build
    * pricing it at the wrong tier, or leaving the stack wrong, diverges here
    * without anyone having written a case for it.
    */
  val GeneratedCancunBlobGasFeeCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/cancun/eip7516_blobgasfee" + ComposedForCancun

  /** The generated tier's directory for the blob transaction itself, under the
    * same rules.
    *
    * ==The first directory here whose subject is ADMISSION rather than the
    * machine==
    *
    * Every other Cancun directory settles a transaction and compares a root.
    * This one is mostly about transactions that never run: of its 875 cases,
    * 153 publish an expected refusal, and six of the seven refusals named are
    * rules this upgrade introduces. What it therefore certifies is the branch
    * ORDER as much as the branches -- a refusal is compared by name, so a case
    * refused by the right rule for the wrong reason is a divergence here.
    *
    * **The balance requirement is where it is thickest and least obvious.** 144
    * of the 153 expect `INSUFFICIENT_ACCOUNT_FUNDS`, and every one of them is
    * decided by whether what the transaction offered for its blobs is counted
    * into that requirement: measured against the published pre-state balances,
    * all 144 are covered without it and none is.
    * `org.fukuii.chainspec.certification.CancunBlobTransactionCertificationSpec`
    * asserts that figure rather than leaving it in prose.
    *
    * **`blob_txs_full` is NOT part of this tier and never was.** It is a sibling
    * directory under the same proposal, it exists only under
    * `blockchain_tests`, `blockchain_tests_engine`, `blockchain_tests_engine_x`
    * and `blockchain_tests_sync`, and this build has no runner for any of them.
    * A reader looking for it here will not find it, which is the reason it is
    * named.
    */
  val GeneratedCancunBlobTransactionCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/cancun/eip4844_blobs/blob_txs" + ComposedForCancun

  /** The generated tier's directory for what the operation reporting a
    * commitment COSTS, under the same rules.
    *
    * One file, 28 cases, and not one of them publishes a refusal -- which is
    * the property that decides what this and its sibling below can see. They
    * are entirely about a build that runs the operation and gets the wrong
    * answer, never about one that should have refused. A build that never
    * implemented the operation at all halts on an undefined byte and diverges
    * on every case, which is the coarsest thing they catch and not the finest.
    *
    * **Registered beside its sibling rather than merged with it**, because a
    * report is keyed by directory and merging two would give one census for two
    * questions -- the same reason the legacy tier is read once per fork rather
    * than once.
    */
  val GeneratedCancunBlobHashCostCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/cancun/eip4844_blobs/blobhash_opcode" + ComposedForCancun

  /** The generated tier's directory for WHERE that operation may be reached
    * from, under the same rules.
    *
    * Two files and 11 cases, across a top-level call, an initcode, a delegated
    * call, a static call, and a transaction of each format that is not a blob
    * transaction. **Those last are the discriminating ones**: the operation is
    * reachable from a transaction carrying no commitments at all, where every
    * index is past the end and the answer is zero, so they separate a build
    * that reports zero from one that refuses or halts.
    *
    * **The two point-evaluation directories under the same parent are
    * deliberately not registered.** This build installs no precompile at
    * `0x0a`, so every case in them would reach an address holding nothing and
    * diverge for a rule nobody claimed to have implemented --
    * `org.fukuii.chainspec.proposals.eip.Eip4844` states that the precompile is
    * the one part of the document its component leaves out.
    */
  val GeneratedCancunBlobHashContextCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/cancun/eip4844_blobs/blobhash_opcode_contexts" +
      ComposedForCancun

  /** The ported tier's own directory for the blob transaction, under the same
    * rules.
    *
    * ==Five cases, and one of them is the only published statement of a rule
    * the type system was expected to make unreachable==
    *
    * `create_blobhash_tx` states a blob transaction with an EMPTY recipient, and
    * it is the only case in the whole state tier that does. **This build cannot
    * read it and neither can the specification**: both type a blob
    * transaction's recipient as an address rather than an address-or-empty, so
    * the signed bytes the fixture publishes decode as no blob transaction at
    * all. It is therefore the census's one skip, recorded as undecodable, and
    * the refusal the fixture names is reached by nothing here.
    *
    * **The skip is the finding rather than a defect to tune away.** A case
    * whose published bytes no conformant decoder accepts is a case about the
    * DECODER, and this build agrees with the specification about it -- what
    * differs is only that the harness reports an unreadable signature as a skip
    * rather than as a refusal, which is a property of every tier it reads and
    * not of this one.
    *
    * The other four restate rules the generated directory already covers -- an
    * empty commitment list, a malformed version, and the operation read inside
    * and past the end of what a transaction carries. **They are registered
    * anyway and are not redundant**: they are a second corpus's independent
    * statement of the same rules, written by a different generation and ported
    * rather than filled, which is the near-complement relationship the legacy
    * and generated tiers already have.
    */
  val PortedStaticCancunBlobTransactionCorpus: String =
    "execution-specs-fixtures state_tests/for_cancun/ported_static/stEIP4844_blobtransactions" + ComposedForCancun

  /** The same directory as the Tangerine Whistle tier, resolved through the
    * other network's schedule instead.
    *
    * ==One corpus, two schedules, and that is the whole claim==
    *
    * Both networks adopted EIP-150 unaltered, so a case filled for that fork is
    * a case either of them must satisfy. What differs is WHERE each switched it
    * on -- block 2,463,000 and block 2,500,000 -- so running one corpus through
    * both schedules at each network's own height exercises the two activations
    * against material neither of them can influence.
    *
    * **This is the only corpus whose passing depends on Ethereum Classic's
    * activation being right.** Move that block and every case here is resolved
    * under the rules of the fork before it.
    *
    * ==The substitution stops at EIP-155, and what stops it is in the published
    * bytes rather than in the rules==
    *
    * Resolving a corpus through a second network is sound only while nothing in
    * it names a chain, and from the fork that admits a chain identifier into a
    * signature the generated tier's own cases name one. Every published
    * transaction is decoded here and its scheme read: of this directory's 536
    * cases 533 sign unprotected and the remaining three are refused for their
    * format or their signature before any chain is asked about, which is why the
    * substitution costs nothing. Of the 1845 cases in the directory filled two
    * forks later, 1807 sign for chain 1 -- so the same substitution there
    * refuses nearly the whole corpus as signed for another chain, and every one
    * of those refusals is the harness disagreeing with itself rather than a
    * divergence.
    *
    * **The legacy tier does not have this bound**, for the reason its own
    * EIP-155 coverage row already gives: it publishes no signed bytes for any
    * case at any fork, so a stated sender stands and nothing ever asks which
    * chain a signature names. That makes it substitutable at any fork and, by
    * the same property, unable to say anything about a chain identifier at any
    * fork either.
    */
  val ClassicTangerineWhistleCorpus: String =
    "execution-specs-fixtures state_tests/for_tangerinewhistle through Ethereum Classic"

  /** The height at which each corpus asks its network what is in force.
    *
    * ==These figures are the harness's, and duplicating the schedule's is the
    * mechanism rather than an oversight==
    *
    * A schedule states where a fork begins. These state where the harness
    * BELIEVES it begins, which is what makes the two comparable: the corpus is
    * filled for a named fork, it is run at the height that fork is supposed to
    * start at, and the schedule answers with whatever it actually holds there.
    * When the two disagree the corpus is resolved under a neighboring fork's
    * rules and diverges.
    *
    * **So do not replace these by reading the activation off the schedule.**
    * That closes the loop: the harness would ask the schedule where the fork is
    * and then ask the same schedule what runs there, which is true of any
    * schedule whatsoever and certifies nothing.
    *
    * Each figure is cited on the schedule entry that is supposed to match it.
    */
  private[certification] val EthereumFrontierStarts: Long = 0L
  private[certification] val EthereumHomesteadStarts: Long = 1150000L
  private[certification] val EthereumTangerineWhistleStarts: Long = 2463000L
  private[certification] val EthereumSpuriousDragonStarts: Long = 2675000L
  private[certification] val EthereumByzantiumStarts: Long = 4370000L

  private[certification] val EthereumConstantinopleStarts: Long = 7280000L
  private[certification] val EthereumIstanbulStarts: Long = 9069000L
  private[certification] val EthereumBerlinStarts: Long = 12244000L
  private[certification] val EthereumLondonStarts: Long = 12965000L
  private[certification] val EthereumParisStarts: Long = 15537394L
  private[certification] val ClassicGasRepriceStarts: Long = 2500000L

  /** Where the harness believes the first fork on this network that activates
    * on a CLOCK begins, in seconds rather than in blocks.
    *
    * Held apart from the figures above and named for its axis, because the two
    * are not comparable and nothing in a bare integer says which it is. A
    * timestamp mistaken for a height resolves to the rules at block
    * 1,681,338,455, which is above every activation this network has and so
    * answers plausibly with the latest rules it holds -- the wrong answer that
    * looks most like the right one.
    */
  private[certification] val EthereumShanghaiStartsAtSecond: Long = 1681338455L

  /** The coordinate a corpus asks its network's schedule about: a block number
    * and a clock reading together.
    *
    * ==Both axes, because a schedule answers on both and stops at the first
    * entry that has not activated==
    *
    * [[UpgradeSchedule.at]] takes the entries in order and stops at the first
    * one that has not fired, so a fork activating on a clock is only reached
    * from a coordinate whose NUMBER has already carried every block-activated
    * entry below it. Naming one axis and defaulting the other would therefore
    * resolve a clock-activated corpus to the rules of the last fork before the
    * axis changed -- silently, because that is a rule set the schedule really
    * holds.
    *
    * A record rather than a pair of numbers: two adjacent `Long`s of the same
    * type transpose without complaint, and the two that get transposed here are
    * a six-figure height and a ten-figure second.
    */
  final private[certification] case class ResolutionPoint(number: Long, timestamp: Long)

  private val ethereumFrontier = ResolutionPoint(EthereumFrontierStarts, 0L)
  private val ethereumHomestead = ResolutionPoint(EthereumHomesteadStarts, 0L)
  private val ethereumTangerineWhistle = ResolutionPoint(EthereumTangerineWhistleStarts, 0L)
  private val ethereumSpuriousDragon = ResolutionPoint(EthereumSpuriousDragonStarts, 0L)
  private val ethereumByzantium = ResolutionPoint(EthereumByzantiumStarts, 0L)
  private val ethereumConstantinople = ResolutionPoint(EthereumConstantinopleStarts, 0L)
  private val ethereumIstanbul = ResolutionPoint(EthereumIstanbulStarts, 0L)
  private val ethereumBerlin = ResolutionPoint(EthereumBerlinStarts, 0L)
  private val ethereumLondon = ResolutionPoint(EthereumLondonStarts, 0L)
  private val ethereumParis = ResolutionPoint(EthereumParisStarts, 0L)

  /** The earliest coordinate at which the clock-activated fork's rules can be
    * in force on this network: its own second, over the block the last
    * block-activated entry begins at.
    *
    * The number is that entry's figure rather than a second one written here,
    * so a wrong height there is wrong in both places at once rather than in
    * one -- and it is the entry the schedule must already have passed for the
    * clock to be read at all.
    */
  private val ethereumShanghai = ResolutionPoint(EthereumParisStarts, EthereumShanghaiStartsAtSecond)

  private val classicGasReprice = ResolutionPoint(ClassicGasRepriceStarts, 0L)

  /** Every network-and-coordinate pair the corpora above are resolved at.
    *
    * Built from the same values [[assemble]] uses, so a figure moved for one is
    * moved for both -- which is the intent, because the figure is the thing
    * under test. No count is stated here: it rises whenever a corpus is
    * resolved at a coordinate none of the others uses, and a figure would rot
    * on that commit rather than on this one.
    *
    * What this does NOT close is a corpus added later at a coordinate never
    * listed here; the count property beside the census is what makes adding a
    * corpus a visible act. **That gap was live rather than hypothetical**: the
    * three corpora filled at this network's Constantinople-era label were
    * resolved at a height this vector did not carry, so nothing checked that an
    * activation was there at all.
    */
  private[certification] val resolutionPoints: Vector[(Network, ResolutionPoint)] =
    Vector(
      ethereum.Mainnet.network -> ethereumFrontier,
      ethereum.Mainnet.network -> ethereumHomestead,
      ethereum.Mainnet.network -> ethereumTangerineWhistle,
      ethereum.Mainnet.network -> ethereumSpuriousDragon,
      ethereum.Mainnet.network -> ethereumByzantium,
      ethereum.Mainnet.network -> ethereumConstantinople,
      ethereum.Mainnet.network -> ethereumIstanbul,
      ethereum.Mainnet.network -> ethereumBerlin,
      ethereum.Mainnet.network -> ethereumLondon,
      ethereum.Mainnet.network -> ethereumParis,
      ethereum.Mainnet.network -> ethereumShanghai,
      ethereumclassic.Mainnet.network -> classicGasReprice
    )

  /** What a network runs at a coordinate, taken from that network's schedule.
    *
    * The whole of the indirection between a corpus and the rules it runs under.
    * Nothing downstream names a composition, so every corpus below is certifying
    * an activation as well as a machine.
    *
    * The whole rule set is carried rather than the machine's facet alone: a
    * state fixture is settled around an invocation, so it is read under what
    * admits a transaction as well as under what executes it, and taking one
    * facet here would put the two resolutions in different places.
    */
  private def rulesAt(schedule: UpgradeSchedule, point: ResolutionPoint): UpgradeRules =
    schedule.at(UInt64.fromBits(point.number), UInt64.fromBits(point.timestamp))

  /** One state corpus: where its files are, which fork's expectations they are
    * read under, which network is asked, and the rules they are resolved to.
    *
    * ==The network is carried as well as the rules, and it is not derivable
    * from them==
    *
    * A rule set holds no identity by design, so it cannot say which chain it
    * belongs to. Admission needs the chain identifier for a rule the rules
    * themselves do not hold: a signature naming a chain must name this one.
    * That makes the pairing part of what a corpus IS -- the same files at the
    * same rules through two different networks are two different questions, and
    * this project already asks exactly that of one tier.
    */
  final private[certification] case class StateCorpus(
      name: String,
      directory: Path,
      fork: String,
      chainId: UInt64,
      rules: UpgradeRules
  )

  private def stateCorporaAt(
      root: Path,
      ethereumSchedule: UpgradeSchedule,
      classicSchedule: UpgradeSchedule
  ): Vector[StateCorpus] =
    val frontier = rulesAt(ethereumSchedule, ethereumFrontier)
    val homestead = rulesAt(ethereumSchedule, ethereumHomestead)

    // Bound once, because the two tiers below reach these rules through corpora
    // that name the fork differently -- `TangerineWhistle` in the generated
    // tier, `EIP150` in the legacy one. Two resolutions would let the two drift
    // into certifying different machines under one section's name.
    val tangerineWhistle = rulesAt(ethereumSchedule, ethereumTangerineWhistle)

    val spuriousDragon = rulesAt(ethereumSchedule, ethereumSpuriousDragon)

    val byzantium = rulesAt(ethereumSchedule, ethereumByzantium)

    // Resolves to PETERSBURG's rules, because two entries share that height and
    // a schedule answers with the last one to activate. That is what this
    // network runs there, and it is what both `ConstantinopleFix` tiers are
    // filled against.
    val constantinopleFix = rulesAt(ethereumSchedule, ethereumConstantinople)

    // NOT from the schedule, and it cannot be: no height resolves to it. The
    // composition is named directly so the legacy tier's other label has
    // something to run against.
    val constantinople = ethereum.Upgrades.constantinople

    val istanbul = rulesAt(ethereumSchedule, ethereumIstanbul)

    // Resolved at this network's Berlin activation and not at Muir Glacier's,
    // even though the corpus is the first thing here that could tell the two
    // apart on a header: no state fixture settles one, so what separates them
    // for this tier is the four proposals rather than the bomb delay between
    // them.
    val berlin = rulesAt(ethereumSchedule, ethereumBerlin)

    val london = rulesAt(ethereumSchedule, ethereumLondon)

    // Resolved at this network's own transition and not at either glacier
    // between it and the fork below: both move a difficulty delay alone, which
    // no state fixture settles, so what separates these rules from London's for
    // this tier is the two proposals rather than anything between them.
    val paris = rulesAt(ethereumSchedule, ethereumParis)

    // The one resolution here whose CLOCK decides the answer. Its number is the
    // entry above's, which is what carries the schedule past every
    // block-activated entry so the clock is read at all.
    val shanghai = rulesAt(ethereumSchedule, ethereumShanghai)

    // NOT from the schedule, and not because no height resolves to it. The fork
    // these directories are filled for is under construction, so what a schedule
    // would resolve to at its activation is a rule set that does not exist yet
    // -- and each corpus's own name says which proposals stand in for it.
    // `Upgrades.cancun` is what this becomes once the fork is whole, and until
    // then a value under that name would claim a completeness it did not have.
    val composedForCancun =
      shanghai.adopting(
        Eip1153.component,
        Eip5656.component,
        Eip6780.component,
        Eip4844.component,
        Eip7516.component
      )

    val gasReprice = rulesAt(classicSchedule, classicGasReprice)

    // Taken from the same schedule the rules are taken from, so the pair cannot
    // drift into asking one network's rules as though it were the other.
    val ethereumChain = ethereumSchedule.network.chainId
    val classicChain = classicSchedule.network.chainId

    Vector(
      StateCorpus(
        LegacyFrontierStateCorpus,
        FixtureCorpus.legacy(root).resolve("GeneralStateTests"),
        StateFixture.Fork,
        ethereumChain,
        frontier
      ),
      StateCorpus(
        GeneratedStateCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_frontier"),
        StateFixture.Fork,
        ethereumChain,
        frontier
      ),
      StateCorpus(
        LegacyEip150StateCorpus,
        FixtureCorpus.legacy(root).resolve("GeneralStateTests"),
        "EIP150",
        ethereumChain,
        tangerineWhistle
      ),
      StateCorpus(
        LegacyEip158StateCorpus,
        FixtureCorpus.legacy(root).resolve("GeneralStateTests"),
        "EIP158",
        ethereumChain,
        spuriousDragon
      ),
      StateCorpus(
        GeneratedHomesteadCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_homestead"),
        "Homestead",
        ethereumChain,
        homestead
      ),
      StateCorpus(
        GeneratedTangerineWhistleCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_tangerinewhistle"),
        "TangerineWhistle",
        ethereumChain,
        tangerineWhistle
      ),
      StateCorpus(
        GeneratedSpuriousDragonCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_spuriousdragon"),
        "SpuriousDragon",
        ethereumChain,
        spuriousDragon
      ),
      StateCorpus(
        LegacyByzantiumStateCorpus,
        FixtureCorpus.legacy(root).resolve("GeneralStateTests"),
        "Byzantium",
        ethereumChain,
        byzantium
      ),
      StateCorpus(
        GeneratedByzantiumCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_byzantium"),
        "Byzantium",
        ethereumChain,
        byzantium
      ),
      StateCorpus(
        GeneratedConstantinopleFixCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_constantinoplefix"),
        "ConstantinopleFix",
        ethereumChain,
        constantinopleFix
      ),
      StateCorpus(
        LegacyConstantinopleFixStateCorpus,
        FixtureCorpus.legacy(root).resolve("GeneralStateTests"),
        "ConstantinopleFix",
        ethereumChain,
        constantinopleFix
      ),
      StateCorpus(
        LegacyConstantinopleStateCorpus,
        FixtureCorpus.legacy(root).resolve("GeneralStateTests"),
        "Constantinople",
        ethereumChain,
        constantinople
      ),
      StateCorpus(
        GeneratedIstanbulCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_istanbul"),
        "Istanbul",
        ethereumChain,
        istanbul
      ),
      StateCorpus(
        GeneratedBerlinCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_berlin"),
        "Berlin",
        ethereumChain,
        berlin
      ),
      StateCorpus(
        GeneratedLondonCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_london"),
        "London",
        ethereumChain,
        london
      ),
      StateCorpus(
        GeneratedParisCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_paris"),
        "Paris",
        ethereumChain,
        paris
      ),
      StateCorpus(
        GeneratedShanghaiCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_shanghai"),
        "Shanghai",
        ethereumChain,
        shanghai
      ),
      StateCorpus(
        ClassicTangerineWhistleCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_tangerinewhistle"),
        "TangerineWhistle",
        classicChain,
        gasReprice
      ),
      StateCorpus(
        GeneratedCancunMemoryCopyCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_cancun/cancun/eip5656_mcopy"),
        "Cancun",
        ethereumChain,
        composedForCancun
      ),
      StateCorpus(
        PortedStaticCancunTransientStorageCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_cancun/ported_static/stEIP1153_transientStorage"),
        "Cancun",
        ethereumChain,
        composedForCancun
      ),
      StateCorpus(
        PortedStaticCancunMemoryCopyCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_cancun/ported_static/stEIP5656_MCOPY"),
        "Cancun",
        ethereumChain,
        composedForCancun
      ),
      StateCorpus(
        GeneratedCancunSelfDestructCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_cancun/cancun/eip6780_selfdestruct"),
        "Cancun",
        ethereumChain,
        composedForCancun
      ),
      StateCorpus(
        GeneratedCancunTransientStorageCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_cancun/cancun/eip1153_tstore"),
        "Cancun",
        ethereumChain,
        composedForCancun
      ),
      StateCorpus(
        GeneratedCancunBlobGasFeeCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_cancun/cancun/eip7516_blobgasfee"),
        "Cancun",
        ethereumChain,
        composedForCancun
      ),
      StateCorpus(
        GeneratedCancunBlobTransactionCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_cancun/cancun/eip4844_blobs/blob_txs"),
        "Cancun",
        ethereumChain,
        composedForCancun
      ),
      StateCorpus(
        GeneratedCancunBlobHashCostCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_cancun/cancun/eip4844_blobs/blobhash_opcode"),
        "Cancun",
        ethereumChain,
        composedForCancun
      ),
      StateCorpus(
        GeneratedCancunBlobHashContextCorpus,
        FixtureCorpus
          .generated(root)
          .resolve("state_tests/for_cancun/cancun/eip4844_blobs/blobhash_opcode_contexts"),
        "Cancun",
        ethereumChain,
        composedForCancun
      ),
      StateCorpus(
        PortedStaticCancunBlobTransactionCorpus,
        FixtureCorpus
          .generated(root)
          .resolve("state_tests/for_cancun/ported_static/stEIP4844_blobtransactions"),
        "Cancun",
        ethereumChain,
        composedForCancun
      )
    )

  /** One censused state corpus run again with its rules altered, or nothing
    * where the harness cannot be assembled.
    *
    * ==What a corpus MENTIONS and what it can SEE are different claims==
    *
    * A tier filled for a fork is routinely taken to certify that fork's
    * proposals, and for one of them here that is false: a rule whose case the
    * corpus never states is a rule the corpus agrees with however it is
    * implemented. Reading the files cannot settle which is which, because the
    * absence being looked for is the absence of a state no field names.
    *
    * Turning the rule off and rerunning does settle it. Verdicts that move mean
    * the corpus discriminates; verdicts that do not mean it never asked. Both
    * directions are asserted, because a rerun that silently failed to apply its
    * change would report every corpus as blind.
    */
  private[certification] def rerun(corpus: String, change: UpgradeRules => UpgradeRules): Option[CorpusReport] =
    for
      root <- FixtureCorpus.root
      registry <- KnownNetworks.registry.toOption
      ethereumSchedule <- registry.at(ethereum.Mainnet.network.chainId)
      classicSchedule <- registry.at(ethereumclassic.Mainnet.network.chainId)
      wanted <- stateCorporaAt(root, ethereumSchedule, classicSchedule).find(_.name == corpus)
    yield stateReport(wanted.copy(rules = change(wanted.rules)))

  private def assemble(
      root: Path,
      ethereumSchedule: UpgradeSchedule,
      classicSchedule: UpgradeSchedule
  ): Vector[CorpusReport] =
    val frontier = rulesAt(ethereumSchedule, ethereumFrontier)
    vmReport(FixtureCorpus.legacy(root).resolve("VMTests"), frontier.evm) +:
      stateCorporaAt(root, ethereumSchedule, classicSchedule).map(stateReport)

  /** What running one case established, with a case that THREW recorded as a
    * divergence rather than as a skip.
    *
    * ==A throw is a divergence, and calling it a skip fails open==
    *
    * A skip means there was nothing here to compare. A throw means the machine
    * broke on something there was. Counting the second as the first would let a
    * machine that threw on every case report as entirely skipped and therefore
    * green -- the same shape [[FixtureCorpus.read]] records one layer up, where
    * an unreadable file aborted every test in the suite instead of becoming one
    * counted outcome. That boundary was put at the reader and not at the runner,
    * and this is the other half of it.
    *
    * ==Without it, one throwing case costs the whole run and says nothing==
    *
    * The reports are assembled inside a `lazy val`, so an initializer that
    * throws leaves it uninitialized and the next access starts again. Every test
    * that asks for a report therefore re-runs every corpus from the first tier,
    * and the failure surfaces as a run that produces no output for as long as
    * anyone is willing to wait rather than as an error naming a case.
    *
    * `NonFatal` for the reason [[FixtureCorpus.read]] gives: an
    * `OutOfMemoryError` should stop the run rather than be recorded as a wrong
    * answer.
    */
  private[certification] def outcomeOf(name: String)(running: => Verdict): CaseOutcome =
    val verdict =
      try running
      catch
        case NonFatal(cause) =>
          Verdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    CaseOutcome(name, verdict)

  private def vmReport(directory: Path, rules: EvmRules): CorpusReport =
    val files = FixtureCorpus.jsonFilesUnder(directory)
    val outcomes = files.flatMap { file =>
      FixtureCorpus
        .read(file)
        .flatMap(VmFixture.decodeFile(file.getFileName.toString, _)) match
        case Left(error) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        case Right(fixtures) =>
          fixtures.map(fixture => outcomeOf(fixture.name)(VmFixtureRunner.run(fixture, rules)))
    }
    CorpusReport(LegacyVmCorpus, files.length, outcomes)

  private def stateReport(corpus: StateCorpus): CorpusReport =
    val StateCorpus(name, directory, fork, chainId, rules) = corpus
    val files = FixtureCorpus.jsonFilesUnder(directory)
    val outcomes = files.flatMap { file =>
      FixtureCorpus
        .read(file)
        .flatMap(StateFixture.decodeFile(file.getFileName.toString, _, fork)) match
        case Left(error) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        case Right(contents) =>
          val skipped = contents.withoutExpectation.map { case_ =>
            CaseOutcome(case_, Verdict.Skipped(SkipReason.NoExpectationAtThisFork))
          }
          val run =
            contents.fixtures.map { fixture =>
              outcomeOf(fixture.name)(StateFixtureRunner.run(fixture, chainId, rules))
            }
          skipped ++ run
    }
    CorpusReport(name, files.length, outcomes)
