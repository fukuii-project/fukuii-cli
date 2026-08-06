# Scope boundary — when a scoped task hits a wall, stop

**currency:** the incident is inherited from this project's prior implementation
and is undated in its source; it is unverified beyond the record describing it.
The rule itself is project policy and does not expire. Checked 2026-08-06.

**No frontmatter, and none is possible.** A file under `.claude/protocols/` does
not auto-load — Claude Code discovers `.claude/rules/`, not this directory — so
`paths:` here would do nothing. **Something has to reach this file by name**,
whether that is an import, a pointer a reader follows, or a task brief quoting
it. Nothing warns you when that stops happening.

---

## The incident

**In this project's prior implementation**, an agent was given a test-only task:
migrate a spec file. The test failed, and the fix appeared to lie outside the
stated scope.

Instead of stopping, it edited **two production source files** to add
`<AGENT>-DEBUG` trace statements, and added temporary DEBUG entries to the
test-scope logging configuration. Both edits were left **uncommitted in the
working tree**, where the next session would have found production files it had
no record of anyone changing.

Both were undone. This protocol is the fix.

**The test this protocol claims: first occurrence, severity-promoted.** The
edits were left **uncommitted in the working tree** — so the failure is not the
wasted effort, it is **state corruption the next session inherits blind**: a
later reader finds production files modified with nothing recording who changed
them or why, and cannot distinguish a deliberate change from a diagnostic
nobody reverted. A second occurrence is exactly what a standing rule is meant to
prevent, not the evidence needed to write one.

**The tell is worth naming, because it is what recurs:** at no point did the
agent decide to change production code. It decided to *look at something*, and
the instrument it reached for happened to require editing production code. The
scope crossing was a side effect of a diagnostic, which is why "do not change
things outside your scope" does not by itself prevent it.

---

## The rule

**A scoped task that appears to require work outside its scope is a stop
condition, not an implementation decision.**

This holds for any boundary a task was given — a file, a subsystem, a layer, a
test tree — and for any reason the wall appears, including a missing tool grant.

When you hit it:

- **Stop and report.** Name the specific file, the specific reason the task
  cannot be completed within the stated scope, and a proposed next step.
- **Do not cross the boundary "just to see if it helps."** A change made to
  test a hypothesis is a change, and it is the one nobody will remember making.
- **Report before investigating further, not after.** The wall is the finding.
  A report that arrives after four more tool calls has already spent the budget
  the stop was meant to save.

**A blocked task reported is a result. A boundary crossed silently is a defect
that outlives the session**, because the next reader cannot tell an intentional
change from a diagnostic someone forgot to revert.

## Never instrument production code to diagnose a test

**Instrument the test.** If runtime visibility is genuinely needed, put the
logging or the assertions in the test file itself, where they are in scope and
visibly temporary.

**Revert any test-scope configuration change before the task is done.** A
temporary logging level left in the tree is not a fix and is not "done" — and
unlike a source edit, nothing about it looks unfinished.

> The prohibition on `println` and similar in production code, and the search
> that closes it out, are `.claude/rules/scala3-style.md`'s under "Debug
> instrumentation" — **that file owns the ban; this one owns the moment**. The
> incident above is the ban's most common cause and its least visible one,
> because the author intends the edit to be temporary and so does not think of
> it as a change at all.

## When the wall is a permission rather than a scope

The same discipline applies when the missing thing is a tool or permission
grant. **Stop and report the gap**: what is missing, the specific step that
needs it, and the narrowest grant that would unblock it.

**Never route around it.** Not a shell heredoc substituting for a missing write
capability, not having another party perform the write on your behalf as a
routine substitute for the grant.

**A missing grant is information about the configuration.** Working around it
destroys that information and leaves the configuration wrong for the next agent,
who will hit the same wall and route around it the same way.

## What this produces

A stop is a finding, and it carries one of the four house dispositions —
**FIXED, SCHEDULED, DECLINED, NEEDS DECISION**. Most often it is NEEDS DECISION,
because the whole point is that the call belongs to whoever set the scope.
Report it as the outcome of the task, not as an apology inside one.

**Reporting the wall is the task succeeding at its actual job**, which was to
find out whether the work could be done as scoped.
