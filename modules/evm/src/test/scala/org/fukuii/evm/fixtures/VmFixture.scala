package org.fukuii.evm.fixtures

import io.circe.Json

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.evm.*
import org.fukuii.rlp.RlpCodec
import org.fukuii.storage.{InMemoryKeyValueStore, Layout, Namespace, NamespaceId, RepresentationId, Seam, WriteMode}
import org.fukuii.trie.{Securing, StateTrie, StoredNodeTrie}
import org.fukuii.types.Log

/** The invocation a legacy VM fixture asks for. */
final case class VmInvocation(
    target: Address,
    caller: Address,
    origin: Address,
    code: Bytes,
    data: Bytes,
    gas: BigInt,
    gasPrice: BigInt,
    value: BigInt
)

/** What a legacy VM fixture expects an invocation that ran to completion to
  * leave behind. Absent when the fixture expects an exceptional halt: those
  * fixtures carry no `gas`, no `out` and no `post` at all.
  */
final case class VmExpectation(
    gasLeft: BigInt,
    output: Bytes,
    post: Map[Address, FixtureAccount],
    logs: Hash
)

/** One case of `ethereum/legacytests` `Constantinople/VMTests`.
  *
  * ==This tier drives the machine, and nothing above it==
  *
  * A fixture here states an invocation directly rather than a transaction, so
  * it exercises the interpreter with no intrinsic cost, no upfront charge and
  * no fee settlement in the way. That is the only published tier shaped like
  * this, and it is why the corpus is worth reading despite being retired
  * upstream.
  */
final case class VmFixture(
    name: String,
    block: BlockContext,
    invocation: VmInvocation,
    pre: Map[Address, FixtureAccount],
    expectation: Option[VmExpectation]
)

object VmFixture:

  /** Every case in one file, keyed by the name the file gives it. */
  def decodeFile(path: String, contents: String): Either[String, Vector[VmFixture]] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap { json =>
        json.asObject.toRight(path + ": expected an object of cases").flatMap { obj =>
          obj.toVector.foldLeft(Right(Vector.empty): Either[String, Vector[VmFixture]]) {
            case (Left(error), _)          => Left(error)
            case (Right(sofar), (name, c)) => decode(name, c).left.map(path + " " + name + ": " + _).map(sofar :+ _)
          }
        }
      }

  def decode(name: String, json: Json): Either[String, VmFixture] =
    val cursor = json.hcursor
    for
      envJson <- cursor.downField("env").focus.toRight("no env")
      execJson <- cursor.downField("exec").focus.toRight("no exec")
      preJson <- cursor.downField("pre").focus.toRight("no pre")
      block <- blockOf(envJson)
      invocation <- invocationOf(execJson)
      pre <- FixtureValues.accounts(preJson)
      expectation <- expectationOf(cursor.downField("post").focus, cursor)
    yield VmFixture(name, block, invocation, pre, expectation)

  private def blockOf(json: Json): Either[String, BlockContext] =
    for
      coinbase <- FixtureValues.addressAt(json, "currentCoinbase")
      number <- FixtureValues.quantityAt(json, "currentNumber")
      timestamp <- FixtureValues.quantityAt(json, "currentTimestamp")
      difficulty <- FixtureValues.quantityAt(json, "currentDifficulty")
      gasLimit <- FixtureValues.quantityAt(json, "currentGasLimit")
    yield BlockContext(coinbase, number, timestamp, difficulty, gasLimit)

  private def invocationOf(json: Json): Either[String, VmInvocation] =
    for
      target <- FixtureValues.addressAt(json, "address")
      caller <- FixtureValues.addressAt(json, "caller")
      origin <- FixtureValues.addressAt(json, "origin")
      code <- FixtureValues.bytesAt(json, "code")
      data <- FixtureValues.bytesAt(json, "data")
      gas <- FixtureValues.quantityAt(json, "gas")
      gasPrice <- FixtureValues.quantityAt(json, "gasPrice")
      value <- FixtureValues.quantityAt(json, "value")
    yield VmInvocation(target, caller, origin, code, data, gas, gasPrice, value)

  private def expectationOf(post: Option[Json], cursor: io.circe.HCursor): Either[String, Option[VmExpectation]] =
    post match
      case None           => Right(None)
      case Some(postJson) =>
        for
          gasLeft <- cursor
            .downField("gas")
            .as[String]
            .left
            .map(_ => "no gas beside post")
            .flatMap(FixtureValues.quantity)
          output <- cursor
            .downField("out")
            .as[String]
            .left
            .map(_ => "no out beside post")
            .flatMap(FixtureValues.bytesOf)
          accounts <- FixtureValues.accounts(postJson)
          logs <- cursor.downField("logs").as[String].left.map(_ => "no logs beside post").flatMap(FixtureValues.hashOf)
        yield Some(VmExpectation(gasLeft, output, accounts, logs))

/** Runs one legacy VM fixture against the interpreter.
  *
  * ==The fixture's pre-state is the state AFTER the value has moved==
  *
  * These fixtures were written for an entry point that runs code without
  * bringing the account into being or moving the invocation's value: their
  * pre-state already shows the target holding what the value gave it, and the
  * caller is usually not an account at all. [[Interpreter.run]] does both, and
  * deliberately, so this seeds the world one transfer earlier -- the caller
  * credited by the value, the target debited by it -- and lets the interpreter
  * perform the move. The state the loop then runs against is exactly the
  * fixture's pre-state, and the move itself is being checked rather than
  * assumed.
  *
  * ==Two things the harness does that the machine does not==
  *
  * Accounts registered by `SELFDESTRUCT` are destroyed here, because at this
  * fork the removal belongs to whatever ends the transaction and a fixture's
  * expected state has it already gone. And the comparison is over the accounts
  * and slots the fixture names, because a trie answers a point lookup rather
  * than an enumeration; an account or slot named nowhere in the fixture is
  * outside what this tier can see.
  */
object VmFixtureRunner:

  /** Runs `fixture` under `rules`.
    *
    * ==The rules are the caller's to name, with nothing to fall back on==
    *
    * This built one network's genesis rules for itself, which made the harness
    * quietly opinionated about which chain a corpus belonged to. What a corpus
    * is read under is part of what the corpus IS, so it arrives from the caller.
    */
  def run(fixture: VmFixture, rules: EvmRules): Verdict =
    val trie = freshTrie()
    val base = new StateTrieWorldState(trie)
    FixtureValues.seed(base, fixture.pre) match
      case Left(error) => Verdict.Skipped(SkipReason.Undecodable(error))
      case Right(())   => runSeeded(fixture, rules, trie, base)

  private def runSeeded(
      fixture: VmFixture,
      rules: EvmRules,
      trie: StateTrie,
      base: StateTrieWorldState
  ): Verdict =
    val invocation = fixture.invocation
    val held = base.balanceOf(invocation.target).toBigInt
    if held < invocation.value then
      Verdict.Diverged(
        Vector(
          "harness precondition: the target holds " + held.toString +
            ", less than the invocation's value " + invocation.value.toString
        )
      )
    else
      base.setBalance(invocation.target, Word(held - invocation.value))
      base.setBalance(invocation.caller, base.balanceOf(invocation.caller).add(Word(invocation.value)))
      val journal = new JournaledWorldState(base)
      val environment = new Environment(
        journal,
        blockHashAt = VmFixtureRunner.blockHashOf,
        block = fixture.block,
        transaction = TransactionContext(invocation.origin, invocation.gasPrice),
        chainId = chainId,
        rules = rules
      )
      val frame = new Frame(
        Message(
          caller = invocation.caller,
          currentTarget = invocation.target,
          codeAddress = Some(invocation.target),
          value = Word(invocation.value),
          data = invocation.data,
          transfersValue = true,
          isStatic = false
        ),
        Code(invocation.code),
        invocation.gas
      )
      val outcome =
        Interpreter.run(frame, environment)
      judge(fixture, frame, trie, journal, base, outcome)

  /** The hash of an earlier block as the published corpora define it, which is
    * the digest of the number written in decimal. Both corpora were filled
    * against that convention, so a node's real answer would disagree with every
    * fixture that reads one.
    */
  /** Which network these fixtures run as.
    *
    * **This corpus states no chain identifier**: a fixture here is a single
    * invocation described by its code, its gas and its stack, with no
    * transaction and no signature for one to sit in. So the value is the
    * harness's own and answers a question the corpus never asks -- which is why
    * it is arbitrary, and why it is [[EvmFixtures.chainId]]'s value rather than
    * either network's.
    *
    * A fixture whose expectation depended on it would be a fixture this corpus
    * cannot state, and there is nothing to keep in step as a result.
    */
  val chainId: UInt64 = UInt64.fromBits(0x5eedL)

  def blockHashOf(number: BigInt): Hash = Keccak256.hash(IArray.unsafeFromArray(number.toString.getBytes("US-ASCII")))

  def freshTrie(): StateTrie =
    val backing = new InMemoryKeyValueStore(Layout(RepresentationId("fixture"), Set.empty))
    def space(id: String): Namespace.Standalone =
      Namespace.Standalone(NamespaceId(id), Seam.State, WriteMode.Mutable)
    new StateTrie(
      new StoredNodeTrie(Securing.Secured, backing, space("state-nodes")),
      owner => new StoredNodeTrie(Securing.Secured, backing, space("storage-" + owner.toHex)),
      backing,
      space("code")
    )

  private def judge(
      fixture: VmFixture,
      frame: Frame,
      trie: StateTrie,
      journal: JournaledWorldState,
      base: StateTrieWorldState,
      outcome: Either[Unsupported, Outcome]
  ): Verdict =
    (outcome, fixture.expectation) match
      case (Left(unsupported), _) =>
        Verdict.Diverged(Vector("this build cannot run " + unsupported.opcode.toString))
      // One arm for both expectations, because neither states anything about a
      // revert: a fixture carrying a post-state expects completion and one
      // without expects an exceptional halt. It is unproducible under the rule
      // set this corpus is run at, which admits no operation that reverts, so
      // reaching it means the rule set changed rather than the fixture failing.
      case (Right(Outcome.Reverted(gasLeft, _)), _) =>
        Verdict.Diverged(
          Vector("reverted with " + gasLeft.toString + " gas left, which this corpus states no expectation for")
        )
      case (Right(Outcome.Halted(halt)), None)    => Verdict.Agreed
      case (Right(Outcome.Halted(halt)), Some(_)) =>
        Verdict.Diverged(Vector("halted with " + halt.toString + " where the fixture expects completion"))
      case (Right(Outcome.Stopped(gasLeft, output)), None) =>
        Verdict.Diverged(
          Vector("completed with " + gasLeft.toString + " gas left where the fixture expects an exceptional halt")
        )
      case (Right(Outcome.Stopped(gasLeft, output)), Some(expected)) =>
        journal.commit()
        frame.accountsToDelete.foreach(trie.destroyAccount)
        val gas =
          Option.when(gasLeft != expected.gasLeft)("gas left " + gasLeft.toString + " != " + expected.gasLeft.toString)
        val returned = Option.when(output != expected.output)("output " + output.toHex + " != " + expected.output.toHex)
        val emitted = Keccak256.hash(RlpCodec.encodeTo[Seq[Log]](frame.logs))
        val logs = Option.when(emitted != expected.logs)("logs " + emitted.toHex + " != " + expected.logs.toHex)
        val slots = (address: Address) => fixture.pre.get(address).fold(Set.empty[BigInt])(_.storage.keySet)
        val state = FixtureValues.divergences(base, expected.post, slots)
        val gone = fixture.pre.keySet.diff(expected.post.keySet).toVector.sortBy(_.toHex).flatMap { address =>
          Option.when(base.accountExists(address))(address.toHex + " still exists where the fixture expects it gone")
        }
        val all = gas.toVector ++ returned.toVector ++ logs.toVector ++ state ++ gone
        if all.isEmpty then Verdict.Agreed else Verdict.Diverged(all)
