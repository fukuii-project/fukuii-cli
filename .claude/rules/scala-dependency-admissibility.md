# Scala dependency admissibility: the gate that runs before the others

**Audience: `sentinel`, and anyone about to propose a library.** `sentinel` owns
every dependency change in every ecosystem, sbt and Maven included. This file
changes nothing about that ownership. It supplies the Scala-specific constraint
a dispatched `sentinel` must apply here and cannot infer from its own charter,
which frames ecosystems by **resolver** ("npm/pnpm, Cargo, Go modules, pip/uv,
sbt/Maven, Gemfile"). That framing is correct and should not change: every entry
names what resolves and pins dependencies. It is simply silent on what follows.

**No `paths:` frontmatter, deliberately, and the reason is mechanical.**
Path-scoped rules fire when Claude *reads a matching file*. A `sentinel`
dispatched to answer "does pekko 1.6.1 publish for Scala 3.3.x?" answers it from
a registry and may never open `build.sbt`. Scoping this to build files would
make it dead exactly when it is needed. Unconditional load is what reaches a
dispatched subagent.

**Exception, documented and unfixable from here:** the built-in `Explore` and
`Plan` agents skip the rules hierarchy, with no setting that changes it. Neither
owns a dependency decision, so the gap is narrow — but if either is ever asked
"can we use library X," restate this gate in its prompt, because it will not have
loaded it.

**This rule does not decide anything.** It rejects. Deciding a version or
adopting a dependency stays `sentinel`'s, and the adoption itself is
operator-gated.

---

## The gate

**Every library on the project classpath must publish an artifact built with
Scala <= our LTS minor (3.3.x).**

Scala 3 guarantees **backward** output compatibility across all releases and
**forward** compatibility only within a minor line. Formally: *"Scala 3.b.y can
consume the output of Scala 3.a.x only if b is greater or equal to a."*
Therefore:

> **Scala LTS output is consumable by any newer Scala Next.
> Scala Next output is NOT consumable by Scala LTS.**

A library published **only** for Scala Next is not merely newer. It is
**unusable here**: the build will not resolve it, and no version bump from our
side fixes it.

**Check this first.** It is dispositive. A dependency that fails it is rejected
before "is there a better alternative" is worth asking, and before the three
supply-chain gates in `~/.claude/rules/supply-chain-security.md` (channel,
maturity, not-deprecated) are worth spending time on. Those three ask whether a
version is *safe to adopt*. This one asks whether it can be adopted **at all**.

## The scope correction, or the gate misfires on every plugin

**It binds the project classpath. It says nothing about what compiles the
build.**

The metabuild and the project classpath are **separate compilations**. sbt 2's
metabuild runs on Scala 3.8.4 (Next) and builds Scala 3.3.x projects without
difficulty. sbt's own 2.0 announcement says so parenthetically: *"(Both sbt 1.x
and 2.x are capable of building Scala 2.x and 3.x)"*. Mill's metabuild is
likewise 3.8.x.

Stated too broadly, this rule would have disqualified **every candidate build
tool** on a constraint that applies to none of them, and would keep mis-firing
on every future plugin. That is not hypothetical: an earlier draft asserted the
broad form and was corrected before it landed.

So: **build tools and their plugins are out of scope for this gate.** Judge them
on the ordinary three.

## Line membership, not number comparison

**We run Scala LTS. Currently 3.3.x, at 3.3.8.**

State it as **membership**: 3.8.x is **Scala Next**, a different line we do not
run, whatever number it carries. Do not state it as "3.3.8 is newer than 3.8.4."
That is true, and it is the weaker framing, because it invites a re-argument
every time the numbers interleave.

That trap has already fired twice in this stack: Scala 3.8.x, and JDK 26 being
numerically newer while not being an LTS. Both are the same error, and the
membership framing is what prevents the third instance.

| Line | What it is | Us |
|---|---|---|
| **Scala LTS** | Stable long-term-support line. Patch updates only. All releases in one LTS line are forward *and* backward output-compatible | **The line fukuii runs** |
| **Scala Next** | Where language development happens. Frequent minors; all experimental features | **Not our line** |

Corollaries, recorded so they are not rediscovered: **do not cross-compile
between Scala 3 versions**, and a patch bump inside the LTS line (3.3.8 to
3.3.9) is forward and backward compatible by construction and needs no migration
ceremony.

## `%%` is version-dependent resolution

```scala
"org.apache.pekko" %% "pekko-actor" % pekkoVersion   // resolves to pekko-actor_3
```

`%%` appends the Scala binary version to the artifact name, so **the same
coordinate yields a different artifact per Scala binary version**. `%` (single)
does not, and is for Java artifacts.

Worth stating because a resolver-framed mental model does not predict it: the
coordinate in the build file is not the artifact name in the registry, and "does
this library support our Scala version" is a question about **which artifacts
were published**, not about a version range in a manifest.

## The instrument

**Scaladex, <https://index.scala-lang.org/>**, answers both questions this rule
raises: what else exists for this job, and which Scala versions a library
actually publishes for. Maven Central is the underlying registry.

**Query them live. Never clone them.** A clone is not a registry: it captures
what someone fetched once, and cloning an index's source gives you its code
rather than its data. This is the corpus-versus-registry rule from the authority
model, in the one place it bites hardest.

## The live instance

**Pekko's Scala output-compatibility has never been checked.** Pekko is a
library on the project classpath, so this gate genuinely applies, and nobody has
confirmed that 1.6.x publishes for Scala <= 3.3.x. That is roadmap row **R24**,
open, `sentinel`'s.

It is open *because this rule previously existed only as prose inside a branch
plan that no dispatched agent reads*. That is the same failure mode as every
other rule in this directory, which is why it lives here now instead.

## Where the reasoning lives

- `[local-only]` `.local/research/reference-authority/2026-08-02-authority-model.md`
  section 8 states this as an admissibility gate, with the sourcing.
- `[local-only]` `.local/research/stack/STACK_DEPENDENCY_GRAPH.md` owns **why
  each declared version is what it is**, including LTS lifetimes and the 3.9
  transition. **Version rationale is not restated here**, deliberately: two
  sources of truth for a version is how a stale pin survives a correction.

`[local-only]` marks a path under the gitignored `.local/` tree, absent in a
clone. Everything above stands without it; see
`.claude/rules/evidence-and-citation.md` section 4 for the convention.
