# Warning ratchet — gate the category before the code exists

**currency:** the incident is inherited from this project's prior implementation
and unverified beyond the record describing it. **This protocol has been applied
once, to the compiler: `build.sbt` promotes a set of warning categories to hard
errors under `-Werror`, scoped so they bind test sources too — read the set and
the reasoning for each from `build.sbt`, which is the only place either is
stated.** The gate was calibrated in both directions at the time it landed, per
"Verifying the gate actually gates" below.

**It has NOT been applied to the linter, and that window is still open.** No
`.scalafix.conf` exists. **A `.scalafmt.conf` does, and it does not narrow this
gap by a single prohibition** — a formatter decides layout, never which
constructs are permitted, so read its arrival as leaving this window exactly as
wide as it was. The prohibitions `.claude/rules/scala3-style.md` § "Build
configuration" names — `return`, `null`, `asInstanceOf`, `isInstanceOf` outside
a match, and the `println` family — are reachable by **no compiler flag**,
measured against the compiler's own option dumps. They need a lint plugin, which is a dependency decision and is
operator-gated. **The zero-cost argument below applies to them with full force
and is not discharged by the compiler half.** Re-read this file at that moment;
it is the change it was written to be applied *at*.

**No frontmatter, and none is possible.** A file under `.claude/protocols/` does
not auto-load — Claude Code discovers `.claude/rules/`, not this directory — so
`paths:` here would do nothing. **Something has to reach this file by name**,
whether that is an import, a pointer a reader follows, or a task brief quoting
it. Nothing warns you when that stops happening.

---

## The incident

**What the source records, and this is its dated finding:** in this project's
prior implementation, *"scalafmt was gated from the start but scalafix was not,
so idiom violations accumulated uncaught until CI"* — caught at L2 on
**2026-07-15**, with **26 `DisableSyntax` violations piled up across L0–L2**.

**What that suggests is mine, not the source's, and is offered as a reading of
one occurrence.** The plausible difference between the two gates is *when* each
became enforceable: the gated category could not accumulate, and the ungated one
did, silently, across three layers. **The source does not measure the formatted
half against the ungated one** — and in the same paragraph it records a
formatting failure of a different kind, a stalled agent leaving two files
unformatted. So this is one dated occurrence read for its mechanism, not a
controlled comparison between two arms.

**The test this protocol claims: first occurrence, severity-promoted — on
irreversibility, not on price.**

The 26 fixes are the wrong number to weigh, and weighing them is what makes this
look like an ordinary tidiness rule. That figure is the cost *observed in a
codebase where the code already existed*. **The cost here is different in kind:
not 26 fixes, but the permanent loss of the option to have zero.**

**A category gated before any code exists costs nothing. There is exactly one
moment at which that is true, it does not recur, and writing ungated code
destroys it.** Gating afterwards is no longer a configuration choice; it is a
migration, and no amount of later diligence recovers the free path. The end
state stays reachable — you can always gate later and pay — but the *zero-cost
route to it* cannot be restored.

**That is the axis the severity bar actually rides on.** The four cases it names
— a host freeze, data loss, a leaked credential, a destroyed pre-image — are not
united by expense. A leaked credential can be rotated in minutes; what puts it
on the list is that rotation *mitigates* rather than *undoes*. The bar's own
wording is "costs more than wasted time", and wasted time is precisely the
recoverable kind of cost: you pay it again and you are whole. These are the
costs you cannot pay again.

**And the promotion clause's logic applies with unusual force here, because the
second occurrence cannot happen.** The bar says waiting for a second occurrence
is "a decision to let it happen again", and that for this class the second
occurrence is the thing the protocol existed to prevent. **A codebase's first
lint configuration happens once.** Waiting for a recurrence is not caution; it
is forfeiting the only chance to apply the rule at all.

> **The guard, so this is not read as a general bypass.** "Cheaper if adopted
> early" is true of nearly every convention and must not become a severity
> claim. The discriminator is a **single, identifiable, non-recurring moment at
> which the cost is genuinely zero** — after which the rule can never again be
> applied at that cost. Most conventions have no such moment: a naming rule
> costs about the same to adopt whenever you adopt it. A codebase's first lint
> configuration is one of the few that does.

**Stated plainly, without overstating it:** the prior incident was not
catastrophic, and this does not claim it was. The claim is that **this
repository has exactly one opportunity to apply this rule for free**, and
protecting that opportunity is what the protocol is for.

That is why this protocol is inverted from the one it was re-authored from.
Its source is a remediation procedure: survey an existing backlog, trisect it by
risk, fix it in graded commits, and only then promote the category to an error.
That procedure exists because the categories had already gone ungated. This repository has no Scala source yet, so the backlog is empty and the
expensive part of that procedure has no subject.

## The mechanism: switch it on first

A warning category costs almost nothing to promote to an error before the code
exists, and grows more expensive every day afterwards. **The ratchet is free
applied to the first line written and is a migration applied later.**

So, when linting is first configured:

- **Enable the category as an error, not as a warning to be worked down.** A
  warning nobody must fix is a warning nobody fixes; the ungated half above is
  what that looks like after three layers.
- **Enable it in the same change that introduces the linter.** Splitting "add
  the tool" from "turn on the rules" recreates the gap deliberately.
- **Prefer the strictest form the codebase can currently satisfy**, which — with
  no code — is every category it could ever satisfy.

**Where a category genuinely cannot be enabled**, that is a finding carrying
one of the four house dispositions — **FIXED, SCHEDULED, DECLINED, NEEDS
DECISION** — and naming what would have to change. It is not a silent
omission.

## An asymmetry worth naming, because it is live

`AGENTS.md` already reasons from this ratchet existing. Its recorded
rejection of one ScalaTest style turns on that style costing a type ascription
on every test method *"or a ratchet exemption"* — a trade-off that only makes
sense if a ratchet is there to be exempted from.

No ratchet is configured, so a live decision currently rests on a mechanism
this repository does not have. That is not an argument for rushing one in; it is
the reason this protocol exists before the linter does, and the reason the
`AGENTS.md` passage should be re-read when one lands.

## Narrow suppression, never blanket silence

When a specific site genuinely cannot satisfy an enabled category:

- Suppress **at the exact site**. Never at file level, never at build level.
- Carry a one-line reason **in the suppression itself**.
- **Never re-suppress a whole category to make a build green.** That converts a
  gate into decoration, and it is indistinguishable afterwards from never having
  had the gate.

A suppression is a recorded exception. A silenced category is an abandoned rule.

## Verifying the gate actually gates

**A ratchet that has never been observed failing is a ratchet you are assuming.**
Before treating a category as gated, confirm both directions: the build is clean
with the category enabled, **and** a deliberately introduced violation actually
fails the build.

**Run that check in the build's ordinary mode**, never under a development or
relaxed profile. A profile that changes which compiler flags apply can show a
clean result while the category is not actually being enforced — the same
false-green family `AGENTS.md` § Commands documents for the test cache, arriving
through a different door.

---

## Deferred: the backlog survey and its graded remediation

**Not in force. Trigger: a warning backlog exists** — that is, a category is
found to have accumulated violations before it was gated, which is exactly what
switching categories on early is meant to prevent.

The source procedure's first half is a remediation workflow: inventory every
occurrence of the category without editing anything; sort each into mechanical,
idiom, or semantic-risk; mark anything on a consensus-critical path; present the
triage and **stop for approval before any edit**; then fix in commits split
strictly by that classification, with the semantic-risk items one per commit,
each carrying its argument for why behavior is unchanged and the test that
covers it.

**Two parts of that are already owned elsewhere and must not be re-derived
here.** The split of a change into mechanical, idiom and semantic risk — and the
rule that a semantic change never rides inside a mechanical commit — is house
commit convention, held in this machine's git conventions and already followed
by this project; restating it here would give one rule two homes that drift. And
the consensus-path escalation is deferred separately, since those directories do
not exist here and have not been named.

**What would be genuinely new when this triggers** is the *stop-for-approval*
step: a backlog survey that proceeds straight into edits is how a triage becomes
an unreviewed sweep.
