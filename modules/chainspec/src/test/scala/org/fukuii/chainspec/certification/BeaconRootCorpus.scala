package org.fukuii.chainspec.certification

import io.circe.{Json, parser}

import java.nio.file.Path

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.evm.fixtures.{FixtureAccount, FixtureCorpus, FixtureValues, VmFixtureRunner}
import org.fukuii.evm.{BlockContext, EvmRules, StateTrieWorldState, Word}
import org.fukuii.execution.SystemCall

/** One published block's answer to the two questions a system call reads off a
  * header.
  *
  * @param timestamp
  *   what the block states, which the contract stores and indexes by.
  * @param parentBeaconBlockRoot
  *   the root the block commits to, absent on a block below the proposal --
  *   which is the case a transition file exists to state, and the reason this
  *   is an option rather than a value.
  */
final case class BeaconRootBlock(timestamp: BigInt, parentBeaconBlockRoot: Option[Hash])

/** One published case, reduced to the part a system call decides.
  *
  * @param deployed
  *   the contract as the file's own `pre` holds it, absent where the file
  *   states no account at the address at all.
  * @param expected
  *   what the file's `postState` states about that one account, absent where
  *   the file names no entry for it.
  */
final case class BeaconRootCase(
    name: String,
    blocks: Vector[BeaconRootBlock],
    deployed: Option[FixtureAccount],
    expected: Option[FixtureAccount]
)

/** How this build answered one published case. */
enum BeaconRootVerdict:

  case Agreed

  case Diverged(detail: String)

  case Skipped(reason: String)

/** The published EIP-4788 material, read as the system calls it asserts and
  * nothing else.
  *
  * ==Why this tier exists at all, which is a fact about the published corpus
  * rather than a preference==
  *
  * **EIP-4788 has no state-test coverage, and it structurally cannot have
  * any.** A state test states one transaction and the state around it; a system
  * call is made by the block, before any transaction, by nobody. Measured over
  * `ethereum/execution-specs-fixtures` @ `tests-v20.0.1`: `state_tests` holds
  * **0** files matching `*4788*` and **0** matching `*beacon*`, against **69**
  * for EIP-1153, **77** for EIP-4844, **30** for EIP-5656 and **59** for
  * EIP-6780 in that same tier -- four Cancun siblings, so the instrument fires
  * on the fork this material is filled for and the zero is the corpus rather
  * than the search.
  *
  * Every published case is therefore a `blockchain_test`, and this build has no
  * block-execution runner to read one with.
  *
  * ==What makes a narrow reading sound, and it is a property of the contract==
  *
  * **The beacon-roots contract can only be written by a caller that is the
  * system address**, so no transaction in any of these files can move the
  * storage this reads. That is read off the deployed code rather than assumed:
  * walking the runtime bytes and skipping every push immediate, the two
  * `SSTORE`s sit at `0x056` and `0x05f`, both inside the block entered at
  * `0x04d`, which is reachable only through the `JUMPI` at `0x019` under
  * `CALLER == SYSTEM_ADDRESS`. The other path -- everything from `0x01a` to
  * `0x04c` -- holds `SLOAD`, `MSTORE`, `RETURN` and `REVERT` and no store at
  * all.
  *
  * **So a block's transactions are not needed to reach the storage these files
  * publish**, and replaying the system calls alone reproduces it. That was
  * checked before this reader was written, against all 194 published cases: the
  * replay agreed with `postState` on **191**, disagreed on **2** -- both
  * `beacon_root_contract_deploy`, where the contract is deployed by a
  * transaction inside the file and so is genuinely unreachable without running
  * one -- and found one case where both were empty. Mutating the replay to
  * store the root at the timestamp's own slot instead of the offset one took
  * agreement from 191 to **0**, which is what establishes that the comparison
  * discriminates rather than passing anything handed to it.
  *
  * ==What this reader deliberately does NOT assert==
  *
  *   - **Any account other than the contract.** The files' `postState` entries
  *     for senders, producers and called accounts are the result of
  *     transactions this does not run.
  *   - **A state root.** No root here is compared with a published one; what is
  *     compared is one account's storage, nonce and code.
  *   - **A header's own fields**, including whether the block was entitled to
  *     carry a beacon root at its height. That is
  *     `org.fukuii.chainspec.HeaderRules`'s rule and a header validator's check.
  *   - **The gas a system call spends**, which no published file states and
  *     which no block charges for.
  *
  * **A case whose `postState` entry for the contract is absent is skipped
  * rather than passed.** An assertion with nothing to compare against is the
  * vacuous-corpus shape, and it would read as coverage.
  *
  * ==One assertion here is NOT a reading of the files, and it is marked==
  *
  * [[run]] also refuses an EMPTY account at the contract's address. No published
  * file states that: the one case with no contract gives the address a nonzero
  * balance in its `postState`, so an account described there is not empty and
  * comparing field by field cannot separate creating an empty account from
  * creating nothing. **So that check is this project's own claim about what a
  * system call must not do, held against published material rather than derived
  * from it**, and `CancunBeaconRootCertificationSpec` asserts the same property
  * directly so it does not depend on that file existing.
  */
object BeaconRootCorpus:

  /** The address EIP-4788 names, which every published file uses.
    *
    * It is not read out of the files: the proposal states it and
    * `org.fukuii.execution.SystemCall` is what a caller would target. What the
    * files establish instead is that no other contract stands there -- across
    * every published case, the code at this address is byte-identical canonical
    * code or nothing at all, which is why nothing in this corpus can decide
    * `SystemCall.GasPrice`.
    */
  val BeaconRoots: Address =
    Address
      .fromHex("0x000F3df6D732807Ef1319fB7B8bB8522d0Beac02")
      .getOrElse(throw new AssertionError("the beacon-roots address is a well-formed address"))

  /** Where the published material sits under the generated corpus.
    *
    * Only the `blockchain_tests` tier is read. The three sibling tiers --
    * `blockchain_tests_engine`, `_engine_x` and `_sync` -- carry the same cases
    * re-expressed for an engine-API driver, so reading them would triple the
    * count without adding a case this can decide.
    */
  def directory(under: Path): Path =
    FixtureCorpus.generated(under).resolve("blockchain_tests")

  def files(under: Path): Vector[Path] =
    FixtureCorpus
      .jsonFilesUnder(directory(under))
      .filter(_.toString.contains("eip4788_beacon_root"))

  /** Every case one published file holds, or the reason it could not be read. */
  def casesIn(text: String): Either[String, Vector[BeaconRootCase]] =
    for
      json <- parser.parse(text).left.map(failure => "unparseable: " + failure.getMessage)
      obj <- json.asObject.toRight("top level is not an object")
      cases <- obj.toVector.foldLeft(Right(Vector.empty): Either[String, Vector[BeaconRootCase]]) {
        case (Left(error), _)       => Left(error)
        case (Right(sofar), (k, v)) => caseOf(k, v).map(sofar :+ _)
      }
    yield cases

  private def caseOf(name: String, json: Json): Either[String, BeaconRootCase] =
    for
      pre <- FixtureValues.accounts(json.hcursor.downField("pre").focus.getOrElse(Json.obj()))
      post <- FixtureValues.accounts(json.hcursor.downField("postState").focus.getOrElse(Json.obj()))
      blocks <- blocksIn(json)
    yield BeaconRootCase(name, blocks, pre.get(BeaconRoots), post.get(BeaconRoots))

  private def blocksIn(json: Json): Either[String, Vector[BeaconRootBlock]] =
    json.hcursor
      .downField("blocks")
      .focus
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .foldLeft(Right(Vector.empty): Either[String, Vector[BeaconRootBlock]]) {
        case (Left(error), _)      => Left(error)
        case (Right(sofar), entry) =>
          entry.hcursor.downField("blockHeader").focus match
            // A file may carry a block stated only as RLP, for a case about a
            // block that must be refused. Nothing here can read one, and a
            // block with no header states no timestamp -- so it contributes no
            // system call rather than being an error.
            case None         => Right(sofar)
            case Some(header) =>
              for
                timestamp <- FixtureValues.quantityAt(header, "timestamp")
                root <- header.hcursor.downField("parentBeaconBlockRoot").focus match
                  case None       => Right(None)
                  case Some(held) =>
                    FixtureValues
                      .bytesOf(held.asString.getOrElse(""))
                      .flatMap(bytes => Hash.fromBytes(bytes.toIArray).left.map(_ => "beacon root is not 32 bytes"))
                      .map(Some(_))
              yield sofar :+ BeaconRootBlock(timestamp, root)
      }

  /** Replays one case's system calls and compares the contract against what the
    * file states.
    *
    * ==The seeded world holds ONE account, deliberately==
    *
    * Everything else a file's `pre` carries is there for its transactions, and
    * no transaction runs here. Seeding them would put accounts in a state
    * nothing reads and invite the reading that a whole pre-state was
    * reproduced.
    */
  def run(entry: BeaconRootCase, rules: EvmRules, chainId: UInt64): BeaconRootVerdict =
    entry.expected match
      case None         => BeaconRootVerdict.Skipped("the file states no entry for the contract")
      case Some(wanted) =>
        entry.deployed match
          case None if wanted.code.nonEmpty =>
            BeaconRootVerdict.Skipped("the contract is deployed by a transaction this does not run")
          case _ =>
            val world = new StateTrieWorldState(VmFixtureRunner.freshTrie())
            val seeded = entry.deployed match
              case None          => Right(())
              case Some(account) => FixtureValues.seed(world, Map(BeaconRoots -> account))
            seeded match
              case Left(error) => BeaconRootVerdict.Skipped("unreadable account: " + error)
              case Right(())   =>
                entry.blocks.foreach { block =>
                  block.parentBeaconBlockRoot.foreach { root =>
                    SystemCall.run(
                      SystemCall(SystemCall.Target.BeaconRoots, Bytes.fromIArray(root.toBytes)),
                      world,
                      contextAt(block.timestamp),
                      _ => Nothing32,
                      chainId,
                      rules
                    )
                  }
                }
                compare(world, wanted)

  /** What a system call reads off the block it is made by.
    *
    * Only the timestamp is load-bearing -- it is what the contract stores and
    * indexes by -- and the rest are the values a `BlockContext` requires. **A
    * published case cannot tell whether any of them is wrong**, which is the
    * honest limit of this reader and the reason they are stated here rather
    * than carried through from a file as though they were asserted.
    */
  private val Nothing32: Hash = Hash.fromBytesTruncating(IArray.empty)

  private def contextAt(timestamp: BigInt): BlockContext =
    BlockContext(
      coinbase = Address.fromBytesTruncating(IArray.empty),
      number = BigInt(0),
      timestamp = timestamp,
      difficulty = BigInt(0),
      gasLimit = BigInt(30000000),
      baseFee = Some(BigInt(0)),
      prevRandao = Some(Nothing32),
      excessBlobGas = Some(UInt64.Zero)
    )

  private def compare(world: StateTrieWorldState, wanted: FixtureAccount): BeaconRootVerdict =
    val slots = wanted.storage.keys.toVector.sorted
    val wrongSlots = slots.flatMap { slot =>
      val key = Word(slot)
      val held = world.storageAt(BeaconRoots, key).toBigInt
      val want = wanted.storage.getOrElse(slot, BigInt(0))
      if held == want then None
      else Some("slot " + slot.toString + " held " + held.toString + " wanted " + want.toString)
    }
    val existence =
      if wanted.code.isEmpty && world.accountExists(BeaconRoots) && world.codeOf(BeaconRoots).isEmpty &&
        world.balanceOf(BeaconRoots).toBigInt == 0 && world.nonceOf(BeaconRoots).toBigInt == 0
      then Some("an empty account was left at the contract's address")
      else None
    val wrongCode =
      if world.codeOf(BeaconRoots) == wanted.code then None
      else Some("the code at the contract's address is not the code the file states")
    (wrongSlots ++ existence.toVector ++ wrongCode.toVector) match
      case Vector() => BeaconRootVerdict.Agreed
      case reasons  => BeaconRootVerdict.Diverged(reasons.mkString("; "))
