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
supply-chain gates — **channel** (a stable release, not an RC or milestone),
**maturity** (published longer ago than the cooldown), and **not deprecated or
withdrawn** — are worth spending time on. Those three ask whether a version is
*safe to adopt*. This one asks whether it can be adopted **at all**.

The three gates are stated here rather than cited to a path because this file is
tracked in a public repository, and the standard they come from is a
machine-local operator rule that no clone can open. A reference a reader cannot
follow trains readers to skip references.

## The scope correction, or the gate misfires on every plugin

**It binds the project classpath. It says nothing about what compiles the
build.**

The metabuild and the project classpath are **separate compilations**. sbt 2's
metabuild runs on Scala 3.8.4 (Next) and builds Scala 3.3.x projects without
difficulty. sbt's own 2.0 announcement says so parenthetically: *"(Both sbt 1.x
and 2.x are capable of building Scala 2.x and 3.x)"*. Mill's metabuild is
likewise 3.8.x.

Stated too broadly, this rule disqualifies **every candidate build tool** on a
constraint that applies to none of them, and mis-fires on every future plugin.

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

## The instruments — and the gate's own question needs a different one

Two questions, and they do **not** share an instrument. Conflating them is how
this section read until 2026-08-03, and it named the wrong tool for the one that
matters.

**"What else exists for this job?"** — **Scaladex**, <https://index.scala-lang.org/>,
the canonical Scala library index, over Maven Central underneath it. This is
question 2 of the record format, and Scaladex answers it well.

**"Which Scala minor was this artifact built with?"** — **the coordinate is the
KEY, not the ANSWER.** Look the artifact up by coordinate; read the minor out of
the artifact's own metadata.

Two things do not carry it. The **coordinate string**: all Scala 3 artifacts
share the single `_3` suffix, and `scala3-compiler_3` spans 3.8.0 through
3.9.0-RC4 under one name, so a library built with 3.3.7 and one built with 3.9.0
are `foo_3` alike. And **Scaladex**, whose project pages report only
`3.x / 2.13 / 2.12` with no minor anywhere.

Two instruments that do answer it, in order of preference:

1. **The library's own build file, read at an immutable tag.** This is what
   actually settled the gate for Pekko: `project/Dependencies.scala` at tag
   `v1.6.0` reads `val scala3Version = "3.3.7"`. Cite the tag, never a branch —
   `.claude/rules/evidence-and-citation.md` §1.
2. **The published POM.** A `_3` artifact declares its own
   `org.scala-lang:scala3-library_3` dependency, and that version *is* the
   compiling minor. Four Pekko POMs agreed with the build file at 3.3.7, which
   is what made the answer two-instrument rather than one.

A third exists and is heavier: the **TASTy version** embedded in the artifact,
which is definitive but requires unpacking the jar. Reach for it only when the
first two disagree.

**Note the gate's wording is already right and should not be "corrected" to
match a coordinate.** It says *built with Scala <= our LTS minor*. Phrased as
"publishes for 3.3" it would be unanswerable as posed, because every Scala 3
artifact publishes for `_3`.

**Query registries live. Never clone them.** A clone is not a registry: it
captures what someone fetched once, and cloning an index's source gives you its
code rather than its data. This is the corpus-versus-registry rule from the
authority model, in the one place it bites hardest.

**An unreachable instrument has not told you the artifact is absent — and
"unreachable" is a property of the instrument, not the host. Try a second one
before believing it.** Measured 2026-08-03: `repo1.maven.org` and
`repo.maven.apache.org` return **403 to WebFetch and 200 to `curl`**. Use
`curl`; the canonical hosts serve `maven-metadata.xml`, which is the most direct
answer available.

Same date: `search.maven.org` failed to connect, Scaladex returned 503
repeatedly, and `central.sonatype.com` returned correct version ordering with
**garbled dates**. That last is the dangerous one — it answers instead of
failing, so use its ordering and never its dates.

## The worked instance — Pekko

Pekko is the one declared dependency that is a **library on the project
classpath**, so it is the only one this gate currently binds.

**Pekko 1.6.0 is built with Scala 3.3.7 — inside our LTS line, one patch behind
3.3.8. The gate PASSES, and would still pass on 3.3.0.** Checked 2026-08-03.

Two instruments agreeing, which is what makes it an answer rather than a reading:

| Instrument | Reading |
|---|---|
| `project/Dependencies.scala` at tag `v1.6.0` | `val scala3Version = "3.3.7"` |
| Four published `_3` POMs (`pekko-actor`, `-stream`, `-remote`, `-serialization-jackson`) | each declares `org.scala-lang:scala3-library_3:3.3.7` |

Pekko states the policy directly too: *"Pekko is built with Scala 3.3 LTS
version."*

**Read the result the right way round.** It does not mean the gate is
theoretical — it means Pekko was never this gate's risk. **The risk is whichever
future library first publishes only for Scala Next**, and it will not announce
itself, because its coordinate will look identical to every other `_3` artifact.
That is exactly why the instrument question above is the load-bearing part of
this rule.

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
