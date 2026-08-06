# Dead code review

**currency:** the procedure is project policy and does not expire. The incident
below is inherited from this project's prior implementation and is unverified
beyond the record that describes it; the compiler behavior it turns on is
current Scala 3 semantics. Checked 2026-08-06.

**No frontmatter, and none is possible.** A file under `.claude/protocols/` does
not auto-load — Claude Code discovers `.claude/rules/`, not this directory — so
`paths:` here would do nothing. **Something has to reach this file by name**,
whether that is an import, a pointer a reader follows, or a task brief quoting
it. Nothing warns you when that stops happening.

---

## The incident

On 2026-07-05 a review pass declared a module-level `given` in this project's
prior implementation *"dead code, zero references anywhere."* The grep was
correct: no textual reference existed. The verdict was carried forward
unquestioned into an instruction to delete it.

The agent that carried out the deletion hit a compile failure. The symbol was
consumed through an **invisible `using` clause** — resolved by the compiler's
implicit search, named nowhere — so a textual search was structurally unable to
see the consumer. The code was restored rather than a broken build shipped.

**The grep was accurate and the conclusion drawn from it was wrong.** That is
the failure this protocol exists to prevent, and it is why the procedure below
never ends at a search.

**The test this protocol claims: first occurrence, severity-promoted.** This one
was caught, loudly, by a compile failure — but that was the lucky shape. **A
deleted `given` that still compiles is a silent resolution change**: the search
finds a different instance, the build stays green, and the behavior moves. That
failure costs more than wasted time and is not visible at the moment it is
made, so waiting for a second occurrence is a decision to let the silent version
happen.

> **The instrument half of this lesson is not restated here.**
> `.claude/rules/evidence-and-citation.md` §3 owns why a search cannot close an
> absence claim, including the `using` case specifically. This protocol owns
> what to *do* about a deletion candidate.

---

## Before the `git rm`, one question

**Zero call sites does not mean zero value.** Ask which of three things is true:

1. Is the code non-functional or broken?
2. Does it add value and should it be **wired**?
3. Is it genuinely low-value, redundant, or superseded?

**Deletion is correct only for the third.** If the second is true, the code
should be wired — or, when wiring is out of scope, deferred with a note. Never
silently erased because nothing calls it yet.

## The three verdicts

### WIRE — connect it, do not delete it

Reach this verdict when the implementation is complete and correct, it addresses
a real gap the codebase currently works around, its responsibility is clear and
does not overlap something else, or it arrived alongside related code that *is*
wired and was simply forgotten.

**Also reach it when the only callers are tests.** A candidate appearing solely
in test sources is either behavior that needs wiring to production code, or a
dead test — and a dead test is its own finding, not something to drop while
deleting something else.

**Action:** name the wiring point. Wire it if that is in scope; otherwise record
it with the wiring point and the gap it fills.

### DELETE — genuinely dead

Reach this verdict when there are no callers **and** the pattern has been
superseded by a different mechanism, or the code is a stub with no real logic,
or it has sat unadopted long enough that the absence of follow-up is itself
evidence, or it was built for a use case that never materialized and nothing —
no test, no caller, no recorded decision — points at a future one.

**Action, in this order:** confirm no callers, delete, **compile**, then commit
with the rationale. The compile is not a formality; it is the step the incident
above turned on.

### DEFER — the decision needs context you do not have

Reach this verdict when the code is good and fills a real gap but wiring is out
of scope, when it conflicts with other code in a way that suggests an unresolved
design question, or when history shows it belonged to a larger effort that was
never finished.

**Action: do not delete.** Record what it does, what gap it fills, where it would
be wired, and why the decision is deferred.

## Confirming "no callers" — the part that is not a search

**A search result is a candidate, not a verdict.** A symbol reached only through
implicit resolution has no textual reference and is not dead.

So a deletion claim is confirmed **by removal and compile**, not by searching.
Until someone has actually removed it and built, the honest phrasing is *"a
search shows no references — confirm by removal and compile"*, never *"confirmed
dead."* Writing the stronger claim in a review that did not attempt the removal
is what turned one wrong verdict into a delete instruction.

**Trace intra-package chains to their outermost point.** Code called only within
its own package is not thereby live: if the package's external entry point is
itself uncalled, the whole subtree is dead and should be assessed as one unit,
not one symbol at a time.

## The questions worth answering before deciding

What does it actually do — is the implementation complete, or a skeleton? Who
calls it, and are those callers themselves reachable? Does the codebase work
around its absence? What does its history say about why it was added and never
wired? And has a different mechanism since taken over its job?

**This is a short assessment, not an investigation.** If it is taking long, that
is itself the signal for DEFER.

## What not to do

- Do not delete because there are no tests. A live thing can be poorly tested.
- Do not delete because the code "looks experimental." Assess it.
- Do not delete something mid-migration or carrying an explicit intent marker
  without raising the deferral first.
- **Do not put a WIRE candidate and a DELETE candidate in the same commit.**
  They are different claims and a reviewer waving through one should not be
  waving through the other.

## What this produces

Every candidate leaves this pass with a disposition — **FIXED, SCHEDULED,
DECLINED, or NEEDS DECISION**, the house finding-resolution vocabulary. "Noted"
is the absence of a disposition, not one of them. A candidate assessed as WIRE
or DEFER and then left without one is the same lost finding as one never
assessed at all.

The commit message carries **why** the code was dead and what superseded it, not
merely that it was removed. A future reader deciding whether to re-add the same
thing has only that sentence to go on.
