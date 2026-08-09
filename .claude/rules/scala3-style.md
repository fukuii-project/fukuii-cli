---
paths:
  - "**/*.scala"
---

# Scala 3 style: idiom, design, and the conventions new code follows

**currency:** the language-semantics claims below were verified against primary
sources on **2026-08-06** — the `given` import rule and opaque-type scope
transparency against the Scala 3 Book (`scala/docs.scala-lang` @ commit
`43833274`, 2026-07-10, an untagged repository), and `given` finality, the
alias-to-lazy-val analogy and the recursive-lazy-val behavior against the Scala 3
reference at tag **`3.3.8`**, which is the version `build.sbt` pins. Also
measured against this repository the same day: `build.sbt` declares **no logging,
metrics, actor or effect dependency** — which is what the logging section and its
deferral rest on. **Re-derive that rather than trusting this sentence**, with
`grep -nE 'slf4j|logback|log4j|micrometer|pekko|cats-effect|zio' build.sbt`; a
declared dependency list is exactly the thing that changes without anyone
re-reading a rule header.

**The same sentence used to add "and no `src/main` tree exists", and that went
false** the moment the first module landed. It is removed rather than updated,
because a state claim of that shape rots on a commit nobody connects to this
file: what the logging deferral actually depends on is the *dependency* half
above, and the source tree's existence was never load-bearing for it.

**A third category, and it is the one a header can hide.** Several blocks below
are **inherited factual claims from this project's prior implementation, and are
unverified** — the incident behind the comparison-typeclass rule, the log field
vocabulary, the runtime and metrics stack named in the deferral, and the sample
that has not been compiled. **Each is marked in place**, and none of them is
verified merely because the verified claims above are. Everything that is neither
sourced nor marked is project policy. Re-verify against the tag the build
actually pins when Scala is next bumped.

**`scala/scala3` tags without a `v` prefix** — `3.3.8`, not `v3.3.8`. Stated
because the surrounding ecosystem does the opposite (`scala/scala` tags
`v2.13.18`, `sbt/sbt` tags `v2.0.4`), so the prefix gets added back by reflex.
A tag that does not resolve is `.claude/rules/evidence-and-citation.md` §1's
failure in its worst form: not a ref that moved, but one no reader can reach.

**Path-scoped, so it loads when Claude opens a Scala file.** The built-in
`Explore` and `Plan` agents skip the rules hierarchy and will not load it;
restate anything load-bearing in their prompt. A path-scoped rule is also **not
re-injected after `/compact`** — it reloads the next time a matching file is
read, so a long session may be running without it.

**This governs language idiom, code design, logging conventions, and the build
configuration that enforces what a linter can.** Comment *content* is
`.claude/rules/comment-content.md`; the vocabulary a name is drawn from is
`.claude/rules/nomenclature.md`.

**Read the admissibility gate before proposing any library this implies.**
Several rules below would be more convenient with a functional-programming or
effect library. Adding one is a dependency decision under
`.claude/rules/scala-dependency-admissibility.md` and is operator-gated. **None
of these rules requires a dependency**; each is expressible in the standard
library.

---

## What this repository is, and why that changes the shape of this rule

This project is **Scala 3 from the first line**. It carries no Scala 2 code and
runs no migration.

That matters because the standard this was re-authored from was written
mid-migration, and most of its content was *remediation* — climb a ratchet from
a Scala 2 form to the Scala 3 form, counting occurrences down to zero. **A
greenfield Scala 3 codebase writes the target form directly and never produces
the source form**, so a ratchet here would count from zero to zero: a check that
cannot fail, reporting clean forever.

So the migration ratchets are gone and what remains is in four groups:

| Group | What it is | How it is enforced |
|---|---|---|
| **Design rules** | Choices the compiler cannot make for you | Review |
| **Language-semantics facts** | Things Scala 3 does that surprise people | Knowing them |
| **Output conventions** | The shape of what the code emits — logging today | Review, plus one done-gate |
| **Build configuration** | Prohibitions a linter can enforce outright | A lint rule, once configured |

The third group is a fourth category rather than a subsection of the others,
because it is neither a language fact nor a compiler-checkable prohibition: it
is a convention whose entire value is that everything follows the same one.

---

## Writing new Scala 3 code

```scala
// Reach for
given config: SomeConfig = ...                       // not `implicit val`
extension (x: SomeType) def describe: String = ...   // not `implicit class`
enum Phase { case Idle, Fetching, Done }             // not sealed trait + case objects
opaque type BlockHash = Array[Byte]                  // not a bare alias
```

**Braceless syntax for new code.** Indentation-based syntax is the Scala 3
default and is what new code should use. This is a preference for what you write
now, not a license to reformat anything. `AGENTS.md` § Commands is the authority
for what tooling exists; where it records no formatter, formatting is matched by
hand.

---

## Design rules

### Model states with a type, not with booleans

A function taking two or more boolean parameters is unreadable at the call site —
`process(true, false)` carries no information about which flag is which, and
transposing them compiles cleanly and behaves wrongly.

```scala
// Avoid: what does `process(true, false)` mean at the call site?
def process(isRetry: Boolean, isFinal: Boolean): Result

// Reach for: the call site names the state, and an impossible combination
// cannot be expressed at all
enum Attempt { case First, Retry }
enum Position { case Interior, Final }
def process(attempt: Attempt, position: Position): Result
```

The gain is not only readability. **A pair of booleans admits four states where
the domain may have three**, and the compiler cannot tell you which one is
nonsense. An enum or a sealed hierarchy admits exactly the states that exist, and
makes a match over them exhaustive.

### A pure function returns its failure; it does not throw

**Where a function is a computation over its arguments, express failure in the
return type** — `Either` for a failure that carries a reason, `Option` where
absence is the whole story.

A thrown exception is invisible in the signature, so a caller has no way to know
it must be handled and the compiler will never tell them. A returned failure is
part of the type, and ignoring it is a visible choice rather than an accident.

This is not a ban on exceptions everywhere. At an I/O boundary, in a runtime's
own error channel, or where an established library's contract is to throw,
follow the surrounding contract. The rule is about the project's own pure
computations, which is where the leak is silent.

### Inject dependencies; do not reach for global state

**A component's dependencies arrive as parameters** — ordinarily a `using`
clause, so the call sites stay uncluttered — rather than being read from a
singleton, a global registry, or mutable module-level state.

```scala
// Reach for
def validate(block: Block)(using config: ChainConfig): Either[Error, Unit]

// Avoid: untestable without mutating a global, and the dependency is invisible
// in the signature
def validate(block: Block): Either[Error, Unit] =
  if block.number < Globals.config.forkBlock then ...
```

Global state makes a component untestable in isolation, hides a real dependency
from the signature, and creates initialization-order hazards that appear only
under a particular startup path. **In a multi-network codebase it is also how one
network family's configuration silently reaches the other's code path** — the
same failure `.claude/rules/nomenclature.md` guards at the naming layer.

---

## Opaque types

### Let the type flow through the whole layer

An opaque type earns its cost only if it is *used*. Declare one and then unwrap
it immediately, and you have the ceremony without the safety.

**The rule: within a layer, the opaque type flows through every value, field,
collection element, map key, and message field. It is unwrapped exactly once, at
a true boundary.**

A true boundary is where the underlying representation is genuinely required: a
codec, a storage serializer, a wire encoder, or a conversion for display.

```scala
// Avoid — "half-typed": the type exists at the entry point and is discarded
class Tracker(root: RawBytes):          // parameter should be the opaque type
  private var current: RawBytes = root  // field should be the opaque type
  def update(r: TrieRoot): Unit = current = r.value   // unwrapped mid-layer

// Reach for — the type flows; unwrapping happens at the storage boundary only
class Tracker(root: TrieRoot):
  private var current: TrieRoot = root
  def update(r: TrieRoot): Unit = current = r
  def persist(): Unit = store.put(current.value)
```

**The cost of getting this wrong is specific:** where two distinct concepts share
one underlying representation — two different kinds of hash, say — unwrapping
mid-layer makes transposing them invisible to the compiler. The type existed
precisely to catch that, and unwrapping early is what discards it.

> The type names above are illustrative. **This rule applies to the first
> opaque type written and to every one after.** `.value` is
> likewise illustrative: an opaque type has no members of its own, so whatever
> unwraps it at the boundary is something the type must define — see the
> extension in "Pin the delegate" below.

### Pin the delegate on a comparison typeclass instance

**This is the highest-consequence item in this file.** Its language mechanism is
verified against two agreeing sources; the incident that found it is inherited
and unverified, and is marked as such below.

**The mechanism.** Inside the scope that defines an opaque type, the type is
*equal* to its underlying representation — that is the whole point of the
construct, and the Scala 3 Book states it directly: the type equality "can be
used to implement the methods", while "outside of the module the type … is
completely encapsulated, or 'opaque.'"

**Why that becomes a hazard.** A parameterless `given` alias behaves as a cached
lazy value. Two sources at tag `3.3.8` agree: the reference's `givens.md`
describes an alias given's access semantics directly — the value is created on
first access and "returned for this and all subsequent accesses" — and reasons
from an alias given being "analogous to a lazy val"; `relationship-implicits.md`
gives the desugaring as `final implicit lazy val`.

**With one exception that matters here.** The desugaring holds for an alias with
neither type nor context parameters **unless its right-hand side is a simple
reference**, in which case the compiler emits a forwarder and caches nothing. So
the lazy-val reading — and the hazard below — applies to an alias whose
right-hand side does real work. That covers the case in question: a typeclass
instance built by a method application is not a simple reference.

So inside the companion, an instance defined by letting implicit search find the
underlying type's instance can resolve *back to itself* — the two types are equal
there, so the instance being defined is a candidate for its own search. That is a
recursive lazy val, and the reference is explicit about what that means:
`lazy-vals-init.md` @ `3.3.8`, § "Note on recursive lazy vals" — *"Ideally
recursive lazy vals should be flagged as an error. The current behavior for
recursive lazy vals is undefined (initialization may result in a deadlock)."*

**Undefined, not guaranteed to deadlock.** That is the vendor's own wording and
it is the right strength: a hazard whose failure mode is unspecified is worse to
rely on than one that always fails, because it can appear to work.

```scala
opaque type Weight = BigInt

object Weight:
  def apply(n: BigInt): Weight = n
  // An opaque type has no members of its own. Anything the outside world uses
  // to unwrap it — `.value` here — must be defined, ordinarily as an extension.
  extension (w: Weight) def value: BigInt = w

  // Avoid: implicit search for the underlying type's Ordering can resolve back
  // to this instance, because inside this scope the two types are equal.
  given Ordering[Weight] = Ordering.by(_.value)

  // Reach for: supply the delegate explicitly, so no search runs.
  given Ordering[Weight] =
    Ordering.by[Weight, BigInt](_.value)(using scala.math.Ordering.BigInt)
```

> **Not compiled.** `Ordering.by[T, S]` and `scala.math.Ordering.BigInt` are both
> real; `BigInt` itself has **no** `value` member, which is why the extension
> above is written out rather than assumed. Check the exact form against the
> compiler when the first such instance is written.

**Two properties make this worth a standing rule rather than a review comment.**
It does not fail at compile time. And it need not fail on first use — a lazy
value that nothing forces early can sit latent indefinitely, so the failure
surfaces far from the code that caused it.

**Apply it to every comparison typeclass**, not only `Ordering` — `Numeric`,
`Integral` and `Fractional` have the same shape.

> **Inherited and unverified:** the recorded incident — a regression test
> exercising a set of such instances hanging the JVM on one of them, with most
> of the set already fixed and nobody having swept for the rest — is from this
> project's prior implementation. **The language mechanism above is verified;
> the incident is inherited.** Verify the exact pinned form against the compiler
> when the first such instance is written, since it has not been compiled here.

---

## `given` and `using` semantics worth knowing before you need them

These are permanent properties of Scala 3, not migration notes. Both are
verified against a primary source, with the citation stated inline.

### A wildcard import brings in neither `given` instances nor extensions

**Verified**, Scala 3 Book, *Given Imports*: `import A.*` "imports all members of
`A` *except* the `given` instance", and `import A.given` "imports *only* that
`given` instance". The merged form is:

```scala
import A.{given, *}
```

**The rule is wider than givens, and the reference says so.** `given-imports.md`
@ `3.3.8`: "a normal wildcard selector `*` brings all definitions other than
givens **or extensions** into scope whereas a `given` selector brings all givens
(including those resulting from extensions) into scope."

That second half is a live case here, not a footnote: "Writing new Scala 3 code"
above prefers `extension` over `implicit class`, so **a companion holding only
extension methods hits this exact trigger** with no `given` in sight.

**The consequence is a resolution failure at the call site, not at the import.**
A companion imported with a bare wildcard compiles the import fine and then fails
wherever an instance or extension was needed — or, worse, silently resolves a
different instance that happens to be in scope.

**So: when a companion object holds `given` instances or extension methods, its
import sites use `{given, *}`.** The Book gives the design reason: it keeps the
origin of an in-scope `given` visible, rather than letting it hide inside a list
of wildcard imports.

### `given` is final, so anything overridable cannot be one

**Verified**, Scala 3 reference, *Relationship with Scala 2 Implicits*, at tag
`3.3.8`: givens desugar to **`final`** members. A parameterless alias becomes a
`final implicit lazy val`; it becomes a `final implicit def` when it is
parameterized, or when its right-hand side is a simple reference and the
compiler emits an uncached forwarder instead.

**In greenfield code this is a design constraint, not an exception to manage.**
If an instance must be replaceable by a subclass — a runtime swapped for a test
double, a base configuration specialized per context — **it cannot be a `given`**,
and the answer is not to work around the finality.

Prefer restructuring so the dependency is *passed* rather than *overridden* —
which is the injection rule above, and the reason the two belong in one file. A
value supplied through a `using` clause is substituted at the call site with no
inheritance involved, so the requirement that produced the override usually
disappears.

---

## Logging conventions

**None of this requires a logging dependency, and this repository declares
none.** These are the conventions the first log line should already follow,
because they cost nothing to adopt now and are expensive to retrofit across a
codebase later. The parts that genuinely need a facade or a runtime are deferred
below, under one trigger.

**Logging is an observability instrument, not an audit trail.** A line reading
"processing block" tells an observer nothing. A line carrying the identifiers,
the counts, the elapsed time and the outcome tells them the system's state and
whether it is healthy, without reading source or reproducing the scenario.

### Field shape

**Always `key=value`.** Never positional-only: `"peer {} height {}"` becomes
`"peer={} height={}"`. Consistent keys are what make logs aggregable by anything
other than a human reading them one line at a time.

| Convention | Why |
|---|---|
| A unit suffix on any ambiguous numeric — `elapsed=142ms`, not `elapsed=142` | A bare number invites the wrong unit |
| Rates carry `/s` | Same |
| Percentages carry **no** `%` sign — `pct=45` | Parseability; `%` is noise in every consumer |
| Long opaque identifiers truncated to a consistent prefix, or head-and-tail | A full hash on every line bloats the log until nobody reads it |
| Counts stated against their total where one is known | A bare count cannot show progress, or a stall |

**Name a field once and keep the name.** The value of the convention is entirely
its consistency; two names for one concept costs more than no convention at all.

> The prior implementation fixed a specific field vocabulary — for the acting
> component, peer, height, elapsed, rate, counts, percentage, operation id,
> reason code, attempt, mode, phase and state. **Those names are inherited and
> describe a domain this repository has not built.** Adopt the convention now;
> fix the vocabulary when the first subsystem needing it exists, and record it
> there and then, so it is set once rather than re-decided per file.

### Never build the message eagerly

**No string interpolation and no concatenation inside a log call.** Both
evaluate unconditionally — including at a level that is switched off — so a
`debug` line built by interpolation pays its full construction cost on every
call in production. Every mainstream facade offers a parameterized form that
defers formatting until the level is known to be enabled; use it.

### Levels

| Level | For |
|---|---|
| `error` | A non-recoverable failure, or an unexpected exception needing a stack trace |
| `warn` | A recoverable anomaly: a retry, a threshold crossed, a partial failure |
| `info` | Major lifecycle events — start and stop, a connection made or lost, a milestone reached |
| `debug` | Per-request detail: useful when diagnosing, too noisy in production |
| `trace` | Byte-level detail, for protocol debugging only |

**No `info` in a tight loop.** Anything that would fire more than about once a
second in normal operation is `debug` at most, and is usually better as a
counter logged once at the end.

### What not to log

| Avoid | Because |
|---|---|
| One line per item in a loop | O(n) noise. Aggregate, then log once |
| Duplicate events at two levels | Pick one |
| **Secrets: private keys, signatures, seed material, credentials** | This repository is public and its domain is node operation — `AGENTS.md` § Security |
| A full `.toString` of a large object | Unbounded output |
| Entry and exit of every method | That is what a profiler is for |

### Never a silent catch

A catch block with no log is invisible data loss. A success path with no log
means nobody can distinguish healthy from degraded.

A failure logs what failed, why, and the context it failed in. **A partial
success is logged distinctly from a full one** — "received fewer than requested"
is a different fact from "received them all", and collapsing the two hides the
most common degraded mode. If there is genuinely nothing worth saying in a catch
block, that is a sign the exception should not have been caught there.

### Debug instrumentation — zero tolerance, and this binds today

Never add `println`, `System.out.println`, `System.err.println`, or
`printStackTrace()` to production code. Not permanently, and **not temporarily
to trace down a failing test.** A print added to diagnose a failure is exactly as
much a violation as one left in by accident; it does not become acceptable
because the author intends to remove it.

The same applies to temporary level or logger entries added to a test-scope
logging configuration: **revert them before the task is done.** A config change
left in the working tree is not a fix and is not "done".

**If you need runtime visibility while diagnosing a failing test, instrument the
test** — put the logging or the assertions in the test file. Never edit
production sources to debug a test.

This is the policy behind the `println` row in "Build configuration" below. That
row is the **mechanism** — a lint rule, once linting is configured — and it is
strictly narrower: a linter does not recognize a test-scope config change, an ad
hoc trace marker, or an author's intention to clean up later.

#### The done-gate

Run before declaring a task done, not at review time. **Derive the file list
rather than hardcoding a source path:**

```
git ls-files '*.scala' | wc -l      # must be > 0, or the line below proved nothing
git ls-files '*.scala' | xargs grep -n 'println\|printStackTrace\|System\.out\|System\.err'
```

Three reasons for that shape, all load-bearing:

- **A hardcoded source path breaks when the layout moves**, and it breaks
  silently: the check matches nothing and reports clean forever, which is worse
  than no check. `git ls-files` follows the layout wherever it goes, including
  before the layout exists.
- **It cannot reach outside the tracked tree.** A bare recursive grep from the
  repository root descends into untracked reference material and reports other
  projects' code as findings.
- **The count line is the discriminator; the exit status is not.** Measured
  2026-08-06 on this repository: scanning a non-empty list and finding nothing
  exits **123**, and scanning an empty list *also* exits **123**. "Passed" and
  "never ran" are the same status, so read the count.

**Do not add `xargs -r` here, and the reason is counter-intuitive enough to
state.** It looks like the tidy fix and it makes the signal worse: measured the
same day, `-r` turns the empty-list case from 123 into **0** — so exit 0 would
then mean *either* "found a violation" *or* "scanned nothing", collapsing the two
in the dangerous direction. Without it, **0 always means there is something to
look at.** Keep that property.

Quote the glob: this shell is zsh, where an unquoted glob is expanded before
`git` ever sees it. Treat any ad hoc trace marker the same way. And remember
**the instrument is a search, not a finding** — open each hit before reporting
it, per `.claude/rules/evidence-and-citation.md` §3.

---

## Build configuration: prohibitions to switch on, not ratchets to climb

**`AGENTS.md` § Commands is the authority for what tooling exists here**, and
it is the one to check rather than this paragraph. The rules below are what
should be configured **as errors from the commit that first configures
linting**, rather than adopted as warnings to be worked down later.

The distinction is the point. In a codebase with existing violations these are a
cleanup backlog; here there are no violations to clean, so each can be a hard
error from the start at zero cost. **That option expires** — every one of these
gets more expensive to switch on the longer the codebase grows without it.

| Prohibit | Instead |
|---|---|
| `return` | Restructure as an expression. `return` in Scala is non-local and does not mean what it means elsewhere |
| `null` | `Option`, an empty collection, or an explicit sentinel |
| `asInstanceOf` | Pattern matching, or a design that does not need the cast |
| `isInstanceOf` outside a pattern match | A `match` on the type, or an exhaustive sealed hierarchy |
| `println`, `System.out`, `System.err` | The logging facade. This row is only the **mechanism**; "Debug instrumentation" above is the policy, and is wider than a linter can see |

**Where a narrow exception is genuinely required** — an interoperability boundary
is the usual honest case — suppress it at the single site with a stated reason,
never by relaxing the rule globally.

### If several such rules are ever applied as automated rewrites, order matters

A rewrite that introduces a new construct can invalidate an import form another
rewrite depends on, so applying them in the wrong order produces failures that
look like defects in the rules themselves. **Apply context-related rewrites
before rewrites that restructure the code around them**, and run the build
between passes rather than at the end.

Recorded as a caution rather than a procedure: the specific ordering the prior
implementation needed was between migration rules that do not apply here. What
transfers is that the ordering is not arbitrary and the failure mode is
misleading.

---

## Deferred: everything that needs a logging or observability dependency

**This is the deferred half only. The logging conventions that bind today are in
`## Logging conventions` above** — including the field shape, the levels, the
secrets ban and the debug-instrumentation gate. The two are separated by an
intervening section, so landing here first is easy and the wrong conclusion
("logging is all deferred") is the opposite of the truth.

**Not in force. Trigger: an observability or logging dependency is declared in
`build.sbt`.** Measured 2026-08-06 — it declares none: no facade, no
implementation, no metrics library, no exporter, no actor or effect runtime.

**One trigger and one home, deliberately.** The logger-API half and the metrics
half fire together, on the same event, and splitting them across two records is
how one of them gets recovered and the other forgotten.

**The logger-API half.** The facade-specific call shape — which argument carries
an exception, and where it must sit to produce a stack trace rather than a
one-line summary. And the off-thread rule: an actor runtime's context-bound
logger is typically safe only on the actor's own thread, so anything inside a
future, an effect block, a completion callback, or a closure handed to a foreign
execution context needs an independently obtained logger.

**The indirect case is the one that costs a debugging session.** A private
helper using the context-bound logger looks correct at its definition site and
becomes a violation the moment it is called from a callback. The recorded
symptom: the happy-path tests pass and one error-branch test fails, because the
error path was the only caller from a foreign thread. **The portable remedy is a
default** — any helper that logs uses the independent logger, and the
context-bound one is reserved for message handlers, where it is provably on the
right thread.

**The metrics half.** Counters, gauges, timers and distribution summaries
alongside significant log lines; a dotted hierarchical metric naming convention;
registration at component construction; and a table of per-operation time
budgets with warn thresholds.

**Recover the low-cardinality tag rule first.** A metric tag must never carry a
per-peer, per-hash, or otherwise unbounded value: cardinality multiplies, and the
result is a memory leak that looks like a slow resource problem rather than a
logging mistake. It is the one item here whose violation is expensive, silent,
and **independent of which stack is chosen** — so it applies on day one of
whatever is adopted, rather than after the first incident.

> Inherited and unverified. The runtime, effect system, facade and metrics stack
> named in the original are not declared here, and the original described its
> stack as already wired into a build this repository does not share. Re-derive
> against what is actually adopted; what ports is the hazard, the default, and
> the cardinality rule — never the API.

## Deferred: specialist review for consensus-critical paths

**Not in force. Trigger: a consensus ownership standard exists, or the
directories it would govern are named.**

The original exempted several directories from idiom rules without specialist
sign-off. Those directories do not exist here and have not been named, so the
path list cannot be written — and a path list is the wrong portable form
regardless, since it names *where* rather than *what*.

The underlying rule is a routing question: who may change consensus-affecting
code, and what triggers a co-signature. **It belongs with the consensus
ownership standard, not in a style rule** — recorded here as a deferral rather
than dropped, because a style rule that silently stops exempting consensus paths
is how an idiom sweep reaches code that needed a second pair of eyes.

## Deferred: codec call-site conventions

**Not in force. Trigger: the serialization codec layer exists.**

The prior implementation fixed three zones for reaching a codec instance at a
call site — use a witness already in scope, summon explicitly when authoring a
codec that dispatches to a field or sibling type, and use the top-level or
extension surface in ordinary consuming code — with the summoning form preferred
in codec bodies because it makes the type parameter mandatory and so makes a
particular silent-recursion hazard unwritable.

**It also records that a sweep away from that form was made and then reverted.**
Recover the reasoning with the rule when the trigger fires; the reverted sweep is
the part most likely to be repeated by someone re-deriving it from scratch.

## Not carried forward

**The Scala 2 to Scala 3 migration ratchets** — converting `implicit` to
`given`, `implicit class` to `extension`, sealed-trait-plus-case-objects to
`enum`, `with` to `&` in self types, and brace-to-braceless rewriting — together
with their occurrence counts, backlog labels, and known-violation lists. Every
one describes a codebase mid-migration. The **target** forms are all above, in
"Writing new Scala 3 code", which is the whole of what a greenfield codebase
needs from them.

**Every grep in the original**, which targeted a source layout and package root
this repository does not have. A copied check would match nothing and report
clean — the same instrument discipline `.claude/rules/evidence-and-citation.md`
§3 states, reached here from the other direction: §3 warns that a hit may not
mean what you think, and this is its mirror, that an absence may not either.
Where a check is worth having, derive its file list from the repository rather
than hardcoding a path; the done-gate under "Debug instrumentation" shows the
shape.

**A rule requiring an explicit type on an anonymous `given`** — carried in the
prior implementation as an inference hazard, with `given = expr` as the form to
avoid. **That form is not grammatical at 3.3.8**, so the hazard as stated cannot
occur. `givens.md` @ `3.3.8` § Syntax:

```
GivenDef ::= [GivenSig] StructuralInstance | [GivenSig] AnnotType ‘=’ Expr
GivenSig ::= [id] [DefTypeParamClause] {UsingParamClause} ‘:’
```

`AnnotType` appears in every alternative and is never bracketed: **the type is
mandatory, and what `[id]` makes optional is the name.** The reference's own
anonymous example carries a type — `given Position = enclosingTree.position`. So
"anonymous" means unnamed, never untyped, and there is no form whose type is
inferred from its right-hand side.

`implicit val x = expr` *is* type-inferring in Scala 2, which is the likely
provenance: a Scala 2 hazard carried into a file whose premise is that this
project runs no migration. **Dropped rather than reworded into a different
hazard** — inventing a replacement to keep the slot would be an edge case with
no incident behind it. Naming a `given` remains worthwhile for traceability in
resolution errors, which is a preference, not a correctness rule.

**A table of the prior implementation's own unfixed logging gaps**, listing
subsystems that lacked start/stop timing, rate and progress reporting, or
failure-reason detail. It enumerated that codebase's state. There is no code here
for it to describe, and a gap list that matches nothing is a backlog nobody can
act on.

**Guidance on adding logging to a file you are already editing** — what to add
always, and what to leave for later. It presupposes an existing codebase with
existing gaps. The one rule in it that stands on its own, never leave a catch
block silent, is in "Logging conventions" above.

