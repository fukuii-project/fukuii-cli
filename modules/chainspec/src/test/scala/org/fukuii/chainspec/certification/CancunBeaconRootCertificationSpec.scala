package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.proposals.eip.{Eip1153, Eip4788, Eip4844, Eip5656, Eip6780, Eip7516}
import org.fukuii.chainspec.{Component, HeaderRules}
import org.fukuii.evm.fixtures.{FixtureCorpus, VmFixtureRunner}
import org.fukuii.evm.{BlockContext, StateTrieWorldState, Word}
import org.fukuii.execution.SystemCall

/** EIP-4788's system call, against the published material and against the one
  * case that material cannot state.
  *
  * ==This is not a state tier and could not be==
  *
  * [[BeaconRootCorpus]] carries the measurement: the published `state_tests`
  * hold zero EIP-4788 files, against four Cancun siblings holding between 30
  * and 77 in the same tier, because a state test states a transaction and a
  * system call is not one. So the corpus read here is the `blockchain_tests`
  * tier, read for the one account a system call writes rather than as blocks --
  * and that reading is sound only because the contract's two `SSTORE`s sit
  * behind a `CALLER == SYSTEM_ADDRESS` branch, so no transaction in any of
  * these files can reach the storage compared here.
  *
  * ==What this does NOT close, stated first because the figures below read as
  * coverage==
  *
  * **Nothing here runs a block.** The files' transactions, receipts, gas and
  * state roots are untouched, and every account other than the contract is
  * ignored. A published case that would fail for any of those reasons passes
  * here, because none of it is read.
  *
  * **So EIP-4788 is certified for what its system call writes, and not for a
  * block that contains one.** Closing the second needs a block-execution
  * runner, which is a certification tier this build does not have.
  */
class CancunBeaconRootCertificationSpec extends AnyFlatSpec:

  private val Adopted: Vector[Component] =
    Vector(
      Eip1153.component,
      Eip5656.component,
      Eip6780.component,
      Eip4844.component,
      Eip7516.component,
      Eip4788.component
    )

  private val composed = ethereum.Upgrades.shanghai.adopting(Adopted*)

  private val chainId: UInt64 = ethereum.Mainnet.network.chainId

  private val HistoryBufferLength: BigInt = BigInt(8191)

  /** The runtime code EIP-4788 publishes, which is what every published case
    * carries at the address.
    *
    * Taken from the proposal's own deployment transaction (`ethereum/EIPs` @
    * `d2a64c2d4` (2026-09-09), `EIPS/eip-4788.md`, Final) with the constructor
    * prefix the document names removed, and independently corroborated by the
    * corpus: across every published EIP-4788 case, the code at that address is
    * byte-identical to this or absent.
    */
  private val BeaconRootsCode: Bytes =
    Bytes
      .fromHex(
        "0x3373fffffffffffffffffffffffffffffffffffffffe14604d57602036146024575f5ffd5b5f35801560495762001fff8106" +
          "90815414603c575f5ffd5b62001fff01545f5260205ff35b5f5ffd5b62001fff42064281555f359062001fff015500"
      )
      .getOrElse(throw new AssertionError("the published runtime code is well-formed hex"))

  private val results: Vector[(String, BeaconRootVerdict)] =
    FixtureCorpus.root.toVector.flatMap { under =>
      BeaconRootCorpus.files(under).flatMap { path =>
        FixtureCorpus.read(path).flatMap(BeaconRootCorpus.casesIn) match
          case Left(error)  => Vector(path.getFileName.toString -> BeaconRootVerdict.Skipped(error))
          case Right(cases) =>
            cases.map(entry => entry.name -> BeaconRootCorpus.run(entry, composed.evm, chainId))
      }
    }

  private val agreed: Int = results.count(_._2 == BeaconRootVerdict.Agreed)

  private val diverged: Vector[String] =
    results.collect { case (name, BeaconRootVerdict.Diverged(why)) => s"$name: $why" }

  private val skipped: Vector[String] =
    results.collect { case (name, BeaconRootVerdict.Skipped(why)) => s"$name: $why" }

  private def blockAt(timestamp: BigInt): BlockContext =
    BlockContext(
      coinbase = Address.fromBytesTruncating(IArray.empty),
      number = BigInt(1),
      timestamp = timestamp,
      difficulty = BigInt(0),
      gasLimit = BigInt(30000000),
      baseFee = Some(BigInt(7)),
      prevRandao = Some(Hash.fromBytesTruncating(IArray.empty)),
      excessBlobGas = Some(UInt64.Zero)
    )

  private def calling(world: StateTrieWorldState, at: BigInt, root: Bytes) =
    SystemCall.run(
      SystemCall(BeaconRootCorpus.BeaconRoots, root),
      world,
      blockAt(at),
      _ => throw new AssertionError("neither path of this contract asks for a block hash"),
      chainId,
      composed.evm
    )

  private def emptyWorld: StateTrieWorldState = new StateTrieWorldState(VmFixtureRunner.freshTrie())

  private def deployedWorld: StateTrieWorldState =
    val world = emptyWorld
    world.setCode(BeaconRootCorpus.BeaconRoots, BeaconRootsCode)
    world

  private val SomeRootOctets: Array[Byte] = Array.fill(32)(0x11.toByte)

  private val SomeRoot: Bytes = Bytes.fromArray(SomeRootOctets)

  private val SomeRootValue: BigInt = BigInt(1, SomeRootOctets)

  // The corpus is third-party material fetched into a machine-local tree, so a
  // clone that has not fetched it reads zero files. That is the one condition
  // under which the corpus assertions are vacuous, and it is why this is asked
  // first rather than left to a ratio.
  "the published beacon-root corpus" should "be found where a corpus root resolves" in
    assert(
      FixtureCorpus.root.isEmpty || results.nonEmpty,
      "the corpus root resolves but no EIP-4788 case was read from " +
        FixtureCorpus.root.map(BeaconRootCorpus.directory(_).toString).getOrElse("(no root)")
    )

  it should "agree with every case it can decide" in
    assert(diverged.isEmpty, diverged.mkString("\n"))

  it should "decide a majority of what it read, so the agreement is not a wall of skips" in
    assert(
      results.isEmpty || agreed * 2 > results.length,
      s"agreed=$agreed skipped=${skipped.length} of ${results.length}:\n" + skipped.mkString("\n")
    )

  /** An address holding no code, which is the one case the published file's own
    * `postState` cannot decide.
    *
    * ==Why it is asserted directly, and what the corpus reading does instead==
    *
    * `no_beacon_root_contract_at_transition.json` is the published statement of
    * the case, and its `postState` gives the address a NONZERO BALANCE -- so the
    * account it describes is not empty, and comparing this build's account
    * against those fields cannot distinguish creating an empty account from
    * creating nothing. That distinction is the whole of the hazard.
    *
    * **[[BeaconRootCorpus]] therefore asserts more than that file states**: it
    * checks separately that no empty account is left at the address, which is an
    * assertion of this project's rather than a reading of the published one. So
    * removing the code test in `SystemCall.run` is caught twice -- measured, by
    * dropping it: this test fails, and the corpus reading fails on that one case
    * with *"an empty account was left at the contract's address"*. Both are kept,
    * because the corpus detector depends on that file continuing to exist and
    * continuing to hold no contract, and this one depends on neither.
    *
    * ==What would break without the code test in `SystemCall.run`==
    *
    * `org.fukuii.evm.Interpreter.run` brings the account it runs as into being
    * and keeps it where the invocation ends normally, and no block-level sweep
    * offers that account to EIP-161's clearing rule -- that sweep belongs to
    * settling a transaction, and a system call settles none. The account would
    * be committed, and every source read for this leaves none:
    * `ethereum/go-ethereum` @ `02872e9ef` (2026-09-09) returns before creating
    * one, `besu-eth/besu` @ `b330564a9` (2026-09-09) never commits its updater,
    * `NethermindEth/nethermind` @ `3a98e0818` (2026-09-09) executes nothing,
    * and `ethereum/execution-specs` @ `0cc100eb1` (2026-09-08) runs empty code
    * whose only account-creating step is suppressed by
    * `should_transfer_value=False`.
    *
    * **This is a live case rather than an edge one.**
    * `.claude/protocols/consensus-poa.md` makes a network this project authors
    * itself a scheduled stage, and an authored genesis carries no beacon-roots
    * contract unless this client puts one there.
    */
  "a system call to an address holding no code" should "leave no account behind" in {
    val world = emptyWorld
    val ran = calling(world, 1000, SomeRoot)
    assert(
      !world.accountExists(BeaconRootCorpus.BeaconRoots) && ran.isEmpty,
      "an address with nothing to run was brought into being, or reported an unbuilt operation"
    )
  }

  /** The negative control for the test above, and it is not optional.
    *
    * Without it, "no account exists afterwards" is equally satisfied by a
    * system call that does nothing under any circumstances -- so the test above
    * would pass over an implementation that never ran anything at all.
    */
  it should "store the timestamp once the contract is deployed" in {
    val world = deployedWorld
    val ran = calling(world, 1000, SomeRoot)
    assert(
      ran.isEmpty && world.storageAt(BeaconRootCorpus.BeaconRoots, Word(BigInt(1000))).toBigInt == 1000,
      "the deployed contract did not store the block's timestamp at its own index"
    )
  }

  it should "store the root at the index offset by the history length" in {
    val world = deployedWorld
    val ran = calling(world, 1000, SomeRoot)
    assert(
      ran.isEmpty && world
        .storageAt(BeaconRootCorpus.BeaconRoots, Word(BigInt(1000) + HistoryBufferLength))
        .toBigInt == SomeRootValue,
      "the deployed contract did not store the root at the offset index"
    )
  }

  /** A system call made under any other identity takes the contract's reading
    * path, which is what makes the caller the load-bearing term rather than a
    * label.
    */
  "the system caller" should "be the address the proposal names" in
    assert(
      SystemCall.Caller.toHex == "fffffffffffffffffffffffffffffffffffffffe",
      "the caller is what the contract branches on, and this is not it: " + SystemCall.Caller.toHex
    )

  /** A header rule with two directions, which is the whole of what a fork
    * resolves about this proposal.
    */
  "the beacon-root header rule" should "be unset by every fork below the proposal" in
    assert(
      !HeaderRules.Unset.carriesParentBeaconBlockRoot &&
        !ethereum.Upgrades.shanghai.header.carriesParentBeaconBlockRoot,
      "a fork below the proposal requires a field the proposal introduces"
    )

  it should "be set by adopting the proposal, and by the composition carrying it" in
    assert(
      Eip4788.beaconRootCommitment(HeaderRules.Unset).carriesParentBeaconBlockRoot &&
        composed.header.carriesParentBeaconBlockRoot,
      "adopting the proposal did not require the field"
    )
