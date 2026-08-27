package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}

/** What one invocation was asked to do: who asked, as whom it runs, what came
  * with it.
  *
  * ==`currentTarget` is which account this runs AS, not which one was named==
  *
  * The two are the same for an ordinary call and separate for the forms that
  * borrow another account's code, so an operation reading storage or reporting
  * its own address has to name which of them it means. Every such operation at
  * this fork means this one: storage is read and written under it, and
  * `ADDRESS` reports it. The specification draws the same distinction under the
  * same name, and keeping it now costs a field rather than a later correction
  * at every site that reads one.
  *
  * ==Two adjacent booleans, so construct this by naming its arguments==
  *
  * [[transfersValue]] and [[isStatic]] sit next to each other and neither has a
  * default, so a positional construction transposing them compiles and runs.
  * What each costs when it is wrong is on the field itself, and neither is a
  * failure anything here reports. **Name them.**
  *
  * @param codeAddress
  *   the account whose code this invocation runs, which is not always the one
  *   it runs AS: the form that borrows another account's code names one here
  *   and the other above. **This is what a precompile is looked up by**, so an
  *   operation setting it to the account being run as would silently stop the
  *   borrowing form from ever reaching one -- and both sources are explicit
  *   that it does reach one. Nothing for a creation, whose code belongs to no
  *   account yet, and that absence is what keeps a creation from running a
  *   precompile however its address falls out.
  * @param data
  *   the input the invocation was called with, which the `CALLDATA` operations
  *   read. Reading past its end is not an error -- the specification pads with
  *   zeroes rather than refusing, so a short input is short and never missing.
  * @param transfersValue
  *   whether starting this invocation moves [[value]] from [[caller]] to
  *   [[currentTarget]]. It is not always so: the form that borrows another
  *   account's code while keeping its caller's identity carries the value **for
  *   the code to read** and moves nothing, because the move was already made by
  *   the invocation it is borrowing identity from. The specification carries the
  *   same fact under the same name on the descriptor it passes, and besu names
  *   it the same way.
  *
  *   **It has no default deliberately.** The fact is not a fork's to vary --
  *   this form has never moved value at any fork -- so it does not belong with
  *   the rules; and every site that starts an invocation genuinely has to
  *   answer, including the layer that settles a transaction, which does move
  *   value. A default would answer for them silently, and answering it wrongly
  *   is not a failure anything reports: the value simply moves twice, and only a
  *   caller that has already spent it turns that into an error rather than into
  *   a wrong state root.
  * @param isStatic
  *   whether this invocation was asked not to change state. Every operation
  *   that would -- a store, an emission, a creation, a destruction, and a call
  *   that sends something -- refuses instead of performing it, and all of them
  *   refuse with [[Halt.WriteInStaticContext]].
  *
  *   It rides here for [[depth]]'s reason: whoever asked for the invocation
  *   settles it, and it does not move while the invocation runs. **Nothing
  *   clears it**, which is what makes *"Once this call returns, the flag is
  *   reset to its value before the call"* (`ethereum/EIPs` @ `9e393a79`,
  *   `EIPS/eip-214.md`, Final) a consequence of this record belonging to one
  *   invocation rather than a step anything performs.
  *
  *   **It has no default, for [[transfersValue]]'s reason, and the direction of
  *   the failure is worse.** A forgotten argument would answer `false`, which
  *   turns an invocation that was asked not to write into one that may, and the
  *   write it then admits is one the network refuses. A missing argument is a
  *   compile error; a wrong default is a state root nothing here can catch.
  * @param depth
  *   how many invocations deep this one is, counting the outermost as zero. It
  *   rides here rather than on the frame because it is settled by whoever asked
  *   for the invocation and never moves while it runs, which is the same reason
  *   the caller and the value do. The operations that nest refuse rather than
  *   exceed [[Stack.Limit]].
  *
  *   **That reuse is this implementation's, NOT the specification's**, and an
  *   earlier version of this sentence claimed otherwise. The specification keeps
  *   two: a bare `1024` in `vm/stack.py` bounding the operand stack, and a named
  *   `STACK_DEPTH_LIMIT` in `vm/interpreter.py` bounding nesting -- different
  *   modules. go-ethereum likewise names `StackLimit` and `CallCreateDepth`
  *   separately. Both values are 1024 at this fork, so nothing diverges today;
  *   **a fork or a network moving one would silently move the other**, which is
  *   the shared-name hazard `.claude/rules/nomenclature.md` describes, applied
  *   to a constant rather than to a type.
  */
final case class Message(
    caller: Address,
    currentTarget: Address,
    codeAddress: Option[Address],
    value: Word,
    data: Bytes,
    transfersValue: Boolean,
    isStatic: Boolean,
    depth: Int = 0
)
