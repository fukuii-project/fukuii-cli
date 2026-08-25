package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.Proposal

/** EIP-170 -- how long a deployed contract may be, and nothing else.
  *
  * ==One bound, and it is not a price==
  *
  * The document's specification is a single sentence: *"if contract creation
  * initialization returns data with length of more than `MAX_CODE_SIZE` bytes,
  * contract creation fails with an out of gas error"*, over a parameter
  * `MAX_CODE_SIZE: 0x6000 (2**14 + 2**13)` (`ethereum/EIPs` @ `4a79c79ab`,
  * `EIPS/eip-170.md`, Final).
  *
  * So this is the second kind of delta the machine's rules had to become a value
  * to carry -- neither an entry in the instruction set nor a figure in the
  * schedule, but a rule about what a creation may leave behind.
  * [[org.fukuii.evm.GasSchedule]] holds prices and a bound is not one, which is
  * why this reaches the record beside [[Eip2.codeDepositMustSucceed]] rather
  * than the schedule the way [[Eip160]] does.
  *
  * ==The comparison is strictly greater, and that is asserted rather than
  * assumed==
  *
  * The document sets *"more than"* in bold, so a creation returning exactly
  * `MAX_CODE_SIZE` bytes succeeds. A delta written with the other comparison
  * would be wrong by one byte on every network from this fork onward while
  * producing an entirely plausible figure, and only a case standing on the bound
  * itself distinguishes the two. The comparison lives at the deposit site rather
  * than here; what this file settles is the number.
  *
  * ==Where the bound is checked is the machine's, and the field disagrees about
  * it==
  *
  * Both orderings against the code-deposit charge are in production and the
  * document settles neither. `org.fukuii.evm.Interpreter.deploy` records the
  * survey and the reason for the one taken.
  *
  * ==The figure is fork-varying in the field already==
  *
  * Two documents have since moved it -- `ethereum/EIPs` carries `eip-7907.md`
  * (*"Meter Contract Code Size And Increase Limit"*) and `eip-7954.md`
  * (*"Increase Maximum Contract Size"*) -- and the clients that model it as a
  * value rather than a constant were right to.
  * `NethermindEth/nethermind` @ `c35ce1b1ab` reads it from a chain
  * configuration through `MaxCodeSizeTransition`, and
  * `openethereum/openethereum` @ `v3.0.1` does the same through
  * `max_code_size_transition`. A network may therefore state a figure that is
  * neither this document's nor a successor's, which is why the member holds a
  * number and this file supplies one rather than the member naming the document.
  *
  * ==One later document derives from this one, and that is why the number is
  * reachable==
  *
  * EIP-3860 bounds initialization code at twice whatever this bound is --
  * `bluealloy/revm` @ `3064c0901c` writes it as
  * `MAX_INITCODE_SIZE = 2 * eip170::MAX_CODE_SIZE`, and derives the same product
  * from an operator-supplied override where one is given. A rule set that
  * recorded only *"bounded"* could not express that derivation at all.
  */
object Eip170:

  /** A creation may leave behind at most 24,576 bytes of code.
    *
    * `0x6000`, which the document also writes as `2**14 + 2**13`. Two sources
    * that do not derive from one another agree on the figure:
    * `ethereum/execution-specs` @ `ccaaaba58` declares
    * `MAX_CODE_SIZE = 0x6000` in `forks/spurious_dragon/vm/interpreter.py`, and
    * `ethereum/go-ethereum-pow` @ `v1.10.26` declares `MaxCodeSize = 24576` in
    * `params/protocol_params.go`.
    */
  val deployedCodeBound: Proposal = _.copy(maxCodeSize = Some(24576))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(170), deployedCodeBound)
