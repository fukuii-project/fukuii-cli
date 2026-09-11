package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.scalatest.flatspec.AnyFlatSpec

/** Which accounts a destruction removes, and the record that answers it.
  *
  * ==Two rule sets over the same programs, which is the whole shape of this
  * spec==
  *
  * [[InvocationSpec]] covers the destruction under the original rule, where
  * every account is reachable. Everything here runs the same operation under
  * [[SelfDestructScope.AccountsCreatedInTransaction]] as well, because the
  * proposal's claim is a DIFFERENCE between two rule sets and a case asserted
  * under one of them alone cannot see it.
  *
  * Expected values are `ethereum/EIPs` @ `d2a64c2d4` (2026-09-09),
  * `EIPS/eip-6780.md`, Final, cross-read against
  * `ethereum/execution-specs` @ `0cc100eb1`
  * `forks/cancun/vm/instructions/system.py:507-560` and
  * `ethereum/go-ethereum` @ `02872e9ef` `core/vm/instructions.go:930-975`.
  */
class SelfDestructScopeSpec extends AnyFlatSpec:

  private val runner = EvmFixtures.address(0x22)
  private val other = EvmFixtures.address(0x33)

  /** Names `beneficiary` and destroys. */
  private def destroying(beneficiary: Address): Seq[Int] =
    (0x73 +: (0 until Address.Width).map(index => beneficiary.toBytes(index) & 0xff)) :+ 0xff

  private val anyAccount: EvmRules =
    EvmFixtures.rules.copy(selfDestructScope = SelfDestructScope.AnyAccount)

  private val onlyCreatedHere: EvmRules =
    EvmFixtures.rules.copy(selfDestructScope = SelfDestructScope.AccountsCreatedInTransaction)

  /** A rule set that deploys the code a creation returns, so a nested creation
    * in these cases leaves an account behind rather than an empty one.
    */
  private val creating: EvmRules =
    onlyCreatedHere.copy(createdAccountNonce = UInt64.fromBits(1L))

  private def funded(balance: Int): EvmFixtures.MapWorldState =
    val world = new EvmFixtures.MapWorldState
    world.balances(runner) = EvmFixtures.word(balance)
    world

  private def destroy(rules: EvmRules, world: EvmFixtures.MapWorldState, beneficiary: Address): (Frame, Environment) =
    val environment = EvmFixtures.environmentUnder(rules, world)
    val program = destroying(beneficiary)
    val frame = new Frame(
      EvmFixtures.message(transfersValue = true),
      Code(Bytes.fromArray(program.map(_.toByte).toArray)),
      BigInt(100000)
    )
    val _ = Interpreter.run(frame, environment)
    (frame, environment)

  // ── The removal, which is what the proposal's title names ─────────────────

  "a destruction of an account this transaction did not create" should "remove it under the original rule" in {
    val (frame, _) = destroy(anyAccount, funded(500), other)
    assert(frame.accountsToDelete == Set(runner), "every account is reachable under the rule this narrows")
  }

  it should "leave it in place under the narrowed rule" in {
    val (frame, _) = destroy(onlyCreatedHere, funded(500), other)
    assert(
      frame.accountsToDelete.isEmpty,
      "EIP-6780: SELFDESTRUCT does not delete any data, including the account itself, where the account " +
        "predates the transaction"
    )
  }

  it should "still send its whole balance to the beneficiary" in {
    val (_, environment) = destroy(onlyCreatedHere, funded(500), other)
    assert(
      environment.world.balanceOf(other) == EvmFixtures.word(500) &&
        environment.world.balanceOf(runner) == Word.Zero,
      "EIP-6780: SELFDESTRUCT transfers the entire account balance to the target, whatever the removal answered"
    )
  }

  // ── The balance, which the title does not name and which splits with it ───

  "a destruction naming its own account" should "burn the balance under the original rule" in {
    val (_, environment) = destroy(anyAccount, funded(500), runner)
    assert(environment.world.balanceOf(runner) == Word.Zero, "the prior rule, which this proposal is leaving behind")
  }

  it should "keep the balance under the narrowed rule where the account predates the transaction" in {
    val (_, environment) = destroy(onlyCreatedHere, funded(500), runner)
    assert(
      environment.world.balanceOf(runner) == EvmFixtures.word(500),
      "EIP-6780: there is no net change in balances, and Ether will not be burnt in this case. A build that " +
        "gated only the removal agrees about every account and disagrees about this value"
    )
  }

  // ── The record the narrowed rule turns on ─────────────────────────────────

  "a deployment" should "record the address it ran at as created in this transaction" in {
    val world = new EvmFixtures.MapWorldState
    val environment = EvmFixtures.environmentUnder(creating, world)
    val target = EvmFixtures.address(0x44)
    val nested = new Frame(
      EvmFixtures.message(currentTarget = target, transfersValue = true),
      Code(EvmFixtures.bytesOf("00")),
      BigInt(100000)
    )
    val _ = Interpreter.deploy(nested, environment)
    assert(
      environment.world.wasCreatedInTransaction(target),
      "EIP-6780: a contract is considered created when a CREATE series operation begins execution"
    )
  }

  it should "record it even where the deployment then failed" in {
    val world = new EvmFixtures.MapWorldState
    val environment = EvmFixtures.environmentUnder(creating, world)
    val target = EvmFixtures.address(0x44)
    // 0x0c names no operation, so the deployment halts exceptionally and every
    // write it made is put back.
    val nested = new Frame(
      EvmFixtures.message(currentTarget = target, transfersValue = true),
      Code(EvmFixtures.bytesOf("0c")),
      BigInt(100000)
    )
    val outcome = Interpreter.deploy(nested, environment)
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidOpcode(0x0c))) &&
        environment.world.wasCreatedInTransaction(target) &&
        environment.world.nonceOf(target) == UInt64.Zero,
      "the specification's marker is not removed even if the account creation reverts, and the count written " +
        "beside it IS -- so the two are asserted together, or a restore that took back neither would pass"
    )
  }

  it should "let that account destroy itself under the narrowed rule" in {
    val world = new EvmFixtures.MapWorldState
    val environment = EvmFixtures.environmentUnder(creating, world)
    val target = EvmFixtures.address(0x44)
    val nested = new Frame(
      EvmFixtures.message(currentTarget = target, transfersValue = true),
      Code(Bytes.fromArray(destroying(other).map(_.toByte).toArray)),
      BigInt(100000)
    )
    val _ = Interpreter.deploy(nested, environment)
    assert(
      nested.accountsToDelete == Set(target),
      "EIP-6780: SELFDESTRUCT continues to behave as it did prior to this EIP where the contract was created " +
        "in the same transaction, which reaches a destruction from the initialization code itself"
    )
  }

  // ── What the machine charges, which the proposal leaves alone ─────────────

  "the two rule sets" should "charge a destruction the same" in {
    val (underAny, _) = destroy(anyAccount, funded(500), other)
    val (underNarrowed, _) = destroy(onlyCreatedHere, funded(500), other)
    assert(
      underAny.gasLeft == underNarrowed.gasLeft,
      "EIP-6780: the rules of EIP-2929 regarding SELFDESTRUCT remain unchanged, so the two answers differ in " +
        "what they leave behind and in nothing they spend"
    )
  }

  it should "end the invocation the same" in {
    val (underAny, _) = destroy(anyAccount, funded(500), other)
    val (underNarrowed, _) = destroy(onlyCreatedHere, funded(500), other)
    assert(!underAny.running && !underNarrowed.running, "the current execution frame halts under both")
  }

  it should "reach the beneficiary the same" in {
    val (underAny, _) = destroy(anyAccount, funded(500), other)
    val (underNarrowed, _) = destroy(onlyCreatedHere, funded(500), other)
    assert(
      underAny.touchedAccounts.contains(other) && underNarrowed.touchedAccounts.contains(other),
      "the account paid out to is reached whatever it receives, which is not a question the scope decides"
    )
  }
