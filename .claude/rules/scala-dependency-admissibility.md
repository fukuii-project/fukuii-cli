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

**Every library the Scala compiler links against must be built with Scala <= our
LTS minor (3.3.x) — at ANY scope, including `% Test`.**

**Scope, stated explicitly because the natural reading gets it wrong.** Gate 0
binds anything the compiler links against: compile scope, `% Test`, and
transitive arrivals alike. TASTy incompatibility is a compile-time property, and
test sources go through the same compiler as main sources, so a Next-only
artifact breaks a test build exactly as it breaks a main one. **The metabuild is
the one exception** — see "The scope correction" below.

**The supply-chain gates below are the ones whose bar legitimately varies by
scope.** A `% Test` dependency never enters the runtime classpath, cannot reach
a user, and its exposure is bounded by the build machine. That is a real
distinction and it applies to channel, maturity and withdrawal — never to Gate 0.

Scala 3 guarantees **backward** output compatibility across all releases and
**forward** compatibility only within a minor line. Formally: *"Scala 3.b.y can
consume the output of Scala 3.a.x only if b is greater or equal to a."*
Therefore:

> **Scala LTS output is consumable by any newer Scala Next.
> Scala Next output is NOT consumable by Scala LTS.**

A library published **only** for Scala Next is not merely newer. It is
**unusable here**: the build will not resolve it, and no version bump from our
side fixes it.

**Walk the transitives.** "Every library the compiler links against" includes the
ones nobody declared. A dependency's own dependencies arrive on the classpath and
are compiled against, so each needs the same check. This is not hypothetical:
ScalaTest 3.2.20 pulls `scala-xml_3:2.1.0`, built with Scala 3.0.2, which no
reading of the declared coordinates would have surfaced.

**Check this first.** It is dispositive. A dependency that fails it is rejected
before "is there a better alternative" is worth asking, and before the three
supply-chain gates — **channel** (a stable release, not an RC or milestone),
**maturity** (published longer ago than the cooldown), and **not deprecated or
withdrawn** — are worth spending time on. Those three ask whether a version is
*safe to adopt*. This one asks whether it can be adopted **at all**.

**Gate 3 has no direct instrument in Maven, and this is a real gap rather than an
oversight.** There is no `deprecated` field in the POM schema — npm's
`pnpm view <pkg> deprecated` has no equivalent. What exists is
`<distributionManagement><relocation>` and `<status>` in the POM, plus upstream
repository health (archived, last push, whether the tag exists). Check those,
and know the gate is **weaker here than in npm**: a broken JVM release can sit
unmarked. The same coordinate-blindness that hides shaded advisories applies —
sbt's own vendored CVEs were findable only in the successor release's notes.

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

Three instruments answer it, in order of strength:

1. **The TASTy header inside the shipped bytecode. Definitive, and cheaper than
   its reputation.** Unzip one `.tasty` from the jar and read the first 64 bytes:
   `unzip -o -q artifact.jar 'path/To/Class.tasty' -d /tmp/x` then
   `head -c 64 /tmp/x/path/To/Class.tasty | strings` prints `Scala 3.1.3`. It sits
   inside the compiler's own output, so **neither a build-time override nor
   version eviction can defeat it.** Note TASTy `minorVersion` equals the Scala
   minor — 3.3.x → 28.3, 3.8.x → 28.8 — which is what the mismatch error reports.
2. **The published POM's declared `org.scala-lang:scala3-library_3`.** A record of
   the actual publish. Strong, and the practical default when checking several
   artifacts at once.
3. **The library's own build file, read at an immutable tag.** Weakest of the
   three, and it looks strongest, which is the trap. ScalaTest's
   `project/DottyBuild.scala` at tag `release-3.2.20` reads
   `System.getProperty("scalatest.dottyVersion", "3.1.3")` — **a default the
   release process can override**, so it cannot alone establish what shipped. It
   happened to be right. Cite the tag, never a branch
   (`.claude/rules/evidence-and-citation.md` §1).

**Prefer two agreeing instruments** over any one alone.

### The one place where "read the resolved value" gives the wrong answer

Everywhere else, the discipline is *read the resolved value, never grep a file* —
a file says what it claims, only resolution says what the tool will do. **For this
gate that discipline inverts, and following it silently produces a wrong answer.**

The resolved `scala3-library_3` on the classpath is **our own version**, evicted
upward by the build. Measured: with ScalaTest 3.2.20 declared, the resolved
`scala3-library_3` is **3.3.8** — ours — while every ScalaTest POM declares
**3.1.3**. Reading the resolved value tells you nothing about what compiled
ScalaTest; it tells you what you are compiling with.

**The value that answers this gate is the DECLARED one**, because it is a *record
of what built the artifact*, not an input to resolution. Eviction is correct
behavior and the gate is what permits it — LTS consuming older LTS output.

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

**ScalaTest is the first library actually declared in `build.sbt`** — three style
artifacts at `% Test` scope. Pekko is **decided but not yet declared**, so the
gate has been run against it in advance of a build entry. Both are worked below;
ScalaTest is the more instructive of the two, because its instruments disagreed
in strength.

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

## The second worked instance — ScalaTest, where the instruments disagreed

**ScalaTest 3.2.20 is built with Scala 3.1.3. The gate PASSES.** Checked
2026-08-04, at `% Test` scope — which the gate binds exactly as it binds compile
scope.

| Instrument | Reading | Strength |
|---|---|---|
| TASTy header in `AnyFlatSpec.tasty`, `AnyPropSpec.tasty`, `AnyFeatureSpec.tasty` | `Scala 3.1.3` | **definitive** |
| Four published POMs, declared `scala3-library_3` | `3.1.3` | strong |
| `project/DottyBuild.scala` at tag `release-3.2.20` | `System.getProperty("scalatest.dottyVersion", "3.1.3")` | **weak — an overridable default** |

**Transitives, which the declared coordinates never surface:** `scalactic_3:3.2.20`
→ 3.1.3 (pass); **`scala-xml_3:2.1.0` → 3.0.2** (pass, and nobody declared it);
`scalatest-compatible` → a Java artifact with no `_3` suffix, so the gate does not
apply.

**The gate's premise is measured, not assumed.** A library built with Scala 3.8.4
(Next) and consumed from 3.3.8 fails:

```
TASTy signature has wrong version.
 expected: {majorVersion: 28, minorVersion: 3}
 found   : {majorVersion: 28, minorVersion: 8}
This TASTy file was produced by a more recent, forwards incompatible release.
```

The gate bites exactly as claimed, and the error names the producing compiler.

## Transitive footprint is a selection criterion, not an afterthought

**Count the repositories a candidate adds, not just the artifacts.** They are different numbers and
only one of them is a cost. Four circe artifacts are one upstream repository; two cats artifacts are
one more. The current declared set is 11 artifacts on the classpath collapsing to 6 repositories.

**Weigh that footprint when candidates are otherwise equal.** A library that drags a runtime in to do
a small job is paying for it in every direction — Gate 0 surface to walk, advisories to track,
sources to keep, and a larger blast radius if it ever has to be removed. A worked instance: `zio-json`
cleared Gate 0 at every node checked and was still deprioritized, because it pulls the full ZIO
runtime and Magnolia as compile-scope dependencies to read JSON; `jsoniter-scala`'s zero non-test
transitives counted the other way.

**This is where transitive sprawl is controlled** — at the adoption decision, by the agent making it.
It cannot be controlled by where the resulting clones are filed, and a rule that tries to is solving
the wrong problem.

## Where the reasoning lives

**Everything this gate needs is stated above.** Two bodies of reasoning sit
behind it and are deliberately not restated here:

- **The durable authority model** states this as an admissibility gate, with the
  full sourcing.
- **The record of why each declared version is what it is** — LTS lifetimes, the
  3.9 transition — owns version rationale. **It is not restated here**, because
  two sources of truth for a version is how a stale pin survives a correction.

Both are internal and do not travel with a clone, so they are named by what they
are rather than by path — see `.claude/rules/evidence-and-citation.md` section 4
for why tracked text cites nothing a reader cannot reach.
