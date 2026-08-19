# Read the field before recommending a structure

**No `paths:` frontmatter, deliberately, and the reason is the whole point of the
rule.** A path-scoped rule fires when a matching file is *read*. The failures
below happened while reasoning in prose, with no file open at all — a
recommendation about shape, written and delivered before anything was
consulted. That is decision-shaped, not file-shaped, so scoping this would make
it dead at exactly the moment it is needed.

`.claude/rules/evidence-and-citation.md` gives the same reasoning for its own
lack of frontmatter, and the two rules are siblings: that one governs how a
claim is *supported*, this one governs what must be *consulted before the claim
is formed*.

**The built-in `Explore` and `Plan` agents skip the rules hierarchy entirely**
and will not load this. `Plan` is the one that most needs it, since designing is
precisely when it binds. Restate it in their prompt.

---

## The rule

**A decision about structure, shape, placement or naming begins by reading the
production clients — before a recommendation is written, not after one is
challenged.**

`reference-corpus.md` says which repositories exist and what each is
authoritative for. **Derive the set from that file rather than recalling it**,
and read its framework-structure carve-out in full before leaning on any client
row — it bounds this rule in both directions and is not restated here.

**What counts as a structural decision**, since the trigger is the part that
gets missed:

- Where a type, module or codec lives, and what it is called.
- Whether something is one type or several, a sum or a record, a field or a
  parameter.
- Whether a seam is pluggable, and where the plug goes.
- Whether a shape should be supported at all.

**The last one is where this rule was written from.** A recommendation to *not*
support something is a structural decision exactly as much as a recommendation
to build it, and it is the one that feels like it needs no evidence.

## The order to read them in, because "the field" is not a flat set

**`reference-corpus.md` § "Reading order" is the statement; this is the pointer, because that file
does not auto-load and this one does.**

**Read proof-of-stake Ethereum first.** It is the leading EVM network and where EVM development
happens — proposals land, get implemented and get exercised there before anywhere else, and
**Ethereum Classic historically lags it.** A design derived from Classic first is derived from the
downstream copy and inherits the lag. The order is: the executable specification, then
`go-ethereum` as the largest production client, then `besu` as the largest production **JVM** client
— whose *shape* transfers most directly to a Scala client — **then** Ethereum Classic as the
downstream addition.

**One client exists only because of this order and is easy to miss.**
**`ethereum/go-ethereum-pow` @ `v1.10.26` is go-ethereum while it still ran proof-of-work**, frozen
deliberately. **Current `go-ethereum` cannot answer a proof-of-work question at all** — ethash was
removed in 2023 and `master` retains only shims — so a proof-of-work survey that reads `go-ethereum`
and concludes anything about proof-of-work has read the wrong tree. Read it as a **peer** of
`core-geth`, not a footnote: it ran the largest proof-of-work EVM network in production for years.

**This orders the reading, not the authority.** A value Ethereum Classic adjusts is the ECIP's, and a
mechanism it alone specifies is the ECIP's alone. What the order governs is the default path for what
the families *share*, which is most of the EVM.

## Why the field, and not just the specification

The specification says what the bytes are. **The clients say what goes wrong**,
and that is the half you cannot derive.

A specification will not tell you that one implementation has to clear a field
by hand on both decode paths because a stale value changes the block hash. It
will not tell you that two implementations chose different representations of
the same idea and both left a comment about the hazard. Those comments are the
most valuable thing in the corpus, because they are somebody's incident report
written at the point of the fix.

So read the implementation **and its comments and mitigations**, not only its
type declarations. A shape copied without its warnings is the shape plus a bug
nobody has hit yet.

## What a survey has to produce

Not "I looked." A survey answers, per client:

1. **Does it implement the thing at all?** An absence is a finding and is often
   the majority answer.
2. **If so, what is the mechanism**, in its own vocabulary?
3. **Where do the implementers disagree?** Agreement on semantics with
   disagreement on representation is the most common and most useful result: it
   means the semantics are settled and the representation is yours to choose.

**Then say which way the evidence points, and say it even when it reverses your
prior recommendation.** The survey is worth nothing if its conclusion was fixed
before it ran.

## Deriving beats recalling, and both beat a count

Never state how many clients do something from memory, and never write a roster
here. This repository has already paid for a hardcoded count going stale, which
is why `AGENTS.md` tells a reader to derive the built module set with
`git ls-files` rather than listing one. The same applies to the corpus.

## Calibrate the instrument, or the survey lies quietly

**A survey is a search, so `.claude/rules/evidence-and-citation.md` §3 governs
it**, and the absence half is the dangerous one: a client that implements the
thing, reported as not implementing it, is a false negative that reads exactly
like a real result.

**Worked instance, from the pass that produced this rule.** A sweep for
consensus engines used `-iname "aura"`, which matches a path component named
exactly that. One client's directory is `Nethermind.Consensus.AuRa`, so the
sweep reported **zero** for a client already known to implement it. The
instrument was wrong, the output was plausible, and nothing about the result
looked unchecked.

So: **run the sweep against a client you already know is positive, and one you
know is negative, before believing any row of it.** A survey that cannot report
a known-positive is not measuring what its column header says.

## The recurring shape: narrow scope, wide-enough survey

**This has now happened at every layer that has reached this rule — L1, then L2 — and it is
worth naming as a pattern rather than re-deriving per section.** Each time, the scope stayed
narrow; what widened was the SURVEY that decided the narrow scope's shape.

The shape, stated once so it does not need re-litigating:

1. **Scope the build narrow.** Build only what the current layer's own stated need requires.
   Do not build support for a consumer that does not exist yet.
2. **Before finalizing the SHAPE — not the implementation, the shape — survey the field for
   every consumer that shape will eventually have to serve.** Not hypothetical consumers:
   ones that already concretely exist, either as a shipped specification (a numbered proposal,
   a wire-protocol version) or as running code in a production client this project's own corpus
   already carries.
3. **Change the shape where the survey shows it would otherwise foreclose one of those
   consumers.** This is usually cheap — a field that can be recomputed instead of only stored,
   a case that admits a second variant, a boundary drawn one seam over from where it first
   looked obvious.
4. **Do not build the deferred consumers.** Record what would trigger building them, and stop.
   The survey's job is to keep today's narrow thing from becoming tomorrow's rewrite; it is not
   license to build tomorrow's thing today.

**The worked instance this rule already carries makes step 3 concrete.** L1's protocol-alignment
survey covered eight clients before any type shipped, and it changed two shapes on the strength of
consumers that already existed: `Receipt`'s bloom became derivable, because eth/69 — an
already-specified wire version — drops it from the message and a peer must recompute it;
`Seal` became a sum, because the survey found two production clients already sealing blocks a
different way. Neither eth/69 support nor a second consensus engine was built. Both remain
buildable without a rewrite.

**The test that keeps this from becoming a mandate to over-engineer:** *does the consumer this
finding is protecting already exist, checkably, in the field* — a specification at a stated
status, a client at a citable ref — *or is it a guess about what might someday be wanted?* The
first is this rule; the second is over-engineering — **build only what the present work
requires, and do not design for a requirement nobody has yet** — and this rule does not create
an exception to it. A survey that cannot name the concrete consumer it is protecting against
has not earned a shape change.

**That test is stated here rather than cited** because the citation it replaced pointed at
`CLAUDE.md`, which in this repository is the single line `@AGENTS.md` — so for anyone who
clones, the limit on this rule resolved to nothing. `.claude/rules/evidence-and-citation.md` §4
is the standard, and a rule file violating it is the worst place for the breach to sit.

**Recognize the shape at the moment a new layer is being scoped, not after its first type
ships.** The trigger is the same one this rule already states: a decision about whether a shape
should be supported at all is structural, and structural decisions begin with the field. What
this section adds is only the sequencing — narrow the build first, so the survey has a concrete
scope to survey *for*, rather than surveying in the abstract.

## What this does not license

**It is not a mandate to copy.** The clients are evidence about what is
possible, what is conventional, and what has hurt. Departing from all of them is
legitimate and this project has done it deliberately more than once — the
requirement is that the departure be *stated as one*, with its reason and, where
the design admits it, a trigger that would reverse it.

**Nor does it move the authority line for values**, which the carve-out cited
above already fixes and this rule leaves exactly where it was.
