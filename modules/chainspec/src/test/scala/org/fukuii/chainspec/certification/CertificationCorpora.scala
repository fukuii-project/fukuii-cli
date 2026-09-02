package org.fukuii.chainspec.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Network, UpgradeRules, UpgradeSchedule}
import org.fukuii.chainspec.networks.{KnownNetworks, ethereum, ethereumclassic}
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
  private[certification] val ClassicGasRepriceStarts: Long = 2500000L

  /** Every network-and-height pair the corpora above are resolved at.
    *
    * Built from the same constants [[assemble]] uses, so a figure moved for one
    * is moved for both -- which is the intent, because the figure is the thing
    * under test. No count is stated here: it rises whenever a corpus is
    * resolved at a height none of the others uses, and a figure would rot on
    * that commit rather than on this one.
    *
    * What this does NOT close is a corpus added later at a height never listed
    * here; the count property beside the census is what makes adding a corpus a
    * visible act. **That gap was live rather than hypothetical**: the three
    * corpora filled at this network's Constantinople-era label were resolved at
    * a height this vector did not carry, so nothing checked that an activation
    * was there at all.
    */
  private[certification] val resolutionPoints: Vector[(Network, Long)] =
    Vector(
      ethereum.Mainnet.network -> EthereumFrontierStarts,
      ethereum.Mainnet.network -> EthereumHomesteadStarts,
      ethereum.Mainnet.network -> EthereumTangerineWhistleStarts,
      ethereum.Mainnet.network -> EthereumSpuriousDragonStarts,
      ethereum.Mainnet.network -> EthereumByzantiumStarts,
      ethereum.Mainnet.network -> EthereumConstantinopleStarts,
      ethereum.Mainnet.network -> EthereumIstanbulStarts,
      ethereum.Mainnet.network -> EthereumBerlinStarts,
      ethereumclassic.Mainnet.network -> ClassicGasRepriceStarts
    )

  /** What a network runs at a height, taken from that network's schedule.
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
  private def rulesAt(schedule: UpgradeSchedule, height: Long): UpgradeRules =
    schedule.at(UInt64.fromBits(height), UInt64.Zero)

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
    val frontier = rulesAt(ethereumSchedule, EthereumFrontierStarts)
    val homestead = rulesAt(ethereumSchedule, EthereumHomesteadStarts)

    // Bound once, because the two tiers below reach these rules through corpora
    // that name the fork differently -- `TangerineWhistle` in the generated
    // tier, `EIP150` in the legacy one. Two resolutions would let the two drift
    // into certifying different machines under one section's name.
    val tangerineWhistle = rulesAt(ethereumSchedule, EthereumTangerineWhistleStarts)

    val spuriousDragon = rulesAt(ethereumSchedule, EthereumSpuriousDragonStarts)

    val byzantium = rulesAt(ethereumSchedule, EthereumByzantiumStarts)

    // Resolves to PETERSBURG's rules, because two entries share that height and
    // a schedule answers with the last one to activate. That is what this
    // network runs there, and it is what both `ConstantinopleFix` tiers are
    // filled against.
    val constantinopleFix = rulesAt(ethereumSchedule, EthereumConstantinopleStarts)

    // NOT from the schedule, and it cannot be: no height resolves to it. The
    // composition is named directly so the legacy tier's other label has
    // something to run against.
    val constantinople = ethereum.Upgrades.constantinople

    val istanbul = rulesAt(ethereumSchedule, EthereumIstanbulStarts)

    // Resolved at this network's Berlin activation and not at Muir Glacier's,
    // even though the corpus is the first thing here that could tell the two
    // apart on a header: no state fixture settles one, so what separates them
    // for this tier is the four proposals rather than the bomb delay between
    // them.
    val berlin = rulesAt(ethereumSchedule, EthereumBerlinStarts)

    val gasReprice = rulesAt(classicSchedule, ClassicGasRepriceStarts)

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
        ClassicTangerineWhistleCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_tangerinewhistle"),
        "TangerineWhistle",
        classicChain,
        gasReprice
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
    val frontier = rulesAt(ethereumSchedule, EthereumFrontierStarts)
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
