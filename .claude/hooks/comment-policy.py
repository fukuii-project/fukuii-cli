#!/usr/bin/env python3
"""PostToolUse(Edit|Write) advisory: comment genres that belong in a commit.

Reads a Claude Code PostToolUse payload as JSON on stdin. Emits
`hookSpecificOutput.additionalContext` naming added comment lines that match a
genre .claude/rules/comment-content.md places in the commit message rather than
in source. Always exits 0. It NEVER blocks and never denies.

ADVISORY, NOT A GATE -- and the distinction is deliberate, not incidental.
Sibling hook bash-guard.py BLOCKS: it exits 2, the tool call does not run, and
the reason goes to stderr. This one does the opposite on every axis. It runs
after the write has already landed, it returns context rather than a decision,
and its exit code is 0 whatever it finds. A reader who expects the bash-guard
posture here would conclude a clean exit means the text was checked and
approved; it means nothing of the sort. The rule is enforced by review, and this
hook only reduces how often review has to be the first thing that notices.

WHY THIS EVENT AND NOT ITS NEIGHBOR. PreToolUse carries the same tool_input and
could match the same text, and it fires BEFORE the permission flow -- so it
would advise on writes that are then denied and never happen, which is comment
on text that does not exist. PostToolUse fires after the tool call succeeds, so
every line it names is a line now in the file. Neither event can prevent the
write: PreToolUse could block it outright, which is a gate, and this is not one.

WHAT IT POINTS AT, AND WHAT IT DOES NOT RESTATE. The policy is
.claude/rules/comment-content.md. That file is the authority on what each genre
is, on the exceptions, and on the standing carve-out for a comment form some
future gate mandates. This hook holds only the patterns, and the message it
emits names the rule rather than paraphrasing it. Two copies of a policy drift;
one copy and a matcher do not.

WHAT IT DELIBERATELY DOES NOT COVER, so a clean run is not read as a clean file:

  * "Restating the code" and "the same rationale repeated at several sites" --
    two of the rule's own named genres. Both are judgments about whether a
    comment adds anything over the code beside it, which no pattern decides.
  * Post-mortem storytelling that names no date, branch or deployment. The
    detectable half of that genre is its artifacts, not its register.
  * Every comment already in the tree. Write carries full `content` and Edit
    carries only `new_string`, so this sees ADDED text only. A retroactive
    sweep of existing comments is a different instrument.
  * Scaladoc, for the two genres the rule scopes to inline `//`. See below.

THE SCALADOC SPLIT, WHICH IS A READING OF AN AMBIGUOUS RULE AND IS FLAGGED AS
ONE. comment-content.md carves scaladoc out of "the four categories" and the
one-sentence limit, then says "the rule that does reach scaladoc is the
vocabulary one" -- which reads as carving it out of the "Never in code" bans
too. But the rebuild-provenance ban in that same section anchors itself in
.claude/rules/evidence-and-citation.md section 4, "a public artifact does not
carry internal development vocabulary", which has no scaladoc carve-out and
reaches tracked prose of every kind.

So the two readings disagree, and the split below picks the cheaper error in
each direction: PROVENANCE patterns apply to every comment form, because a
scaladoc paragraph narrating a rebuild is the exact leak that rule exists to
stop and this repository names it as its live risk; SCOPE and INCIDENT patterns
apply to `//` lines only, because those are the genres the carve-out most
plausibly covers. An over-fire here costs one dismissable line; an under-fire
ships. If the rule is later disambiguated, this split follows it.

SCOPE OF FILE. Scala source only. Build definitions are a different register
that comment-content.md explicitly does not reach, so anything under a
`project/` path segment is skipped, as are `.sbt` files -- which the extension
test already excludes. The `project/` test is by path SEGMENT rather than by
position under the repository root, which also skips a hypothetical Scala
package literally named `project`. That under-fire is accepted deliberately:
the register this protects is the one the rule names, and resolving the
repository root here would buy precision in a case that does not exist.

SILENCE IS THIS HOOK'S CLEAN RESULT, WHICH IS WHY IT NEEDS A SECOND CHANNEL.
Finding nothing produces no stdout and exit 0. If a payload it could not read
produced the same thing, the two would be one signal: "checked, nothing found"
and "checked nothing" would be the same bytes, and the second is the
silent-no-op this repository's tooling standard exists to rule out. So a
payload that cannot be evaluated -- unparseable stdin, a missing `tool_input`,
or a governed file whose added text is under no key this hook knows -- exits 2
with the reason on stderr. For PostToolUse the vendor documents exit 2 as
showing stderr to Claude with the tool already run, so it announces a broken
instrument and reverses nothing.

That third case is the one that earns its keep: if the vendor renames Write's
`content` field, this hook goes blind, and going blind loudly on the next edit
is worth far more than reporting clean forever. It cannot fire for a payload
the registered matcher actually delivers, which arms 1-9 of the calibration
suite are what demonstrate.

FAILURE DIRECTION EVERYWHERE ELSE IS OPEN. A file out of scope, a comment that
matches nothing, a body with no comments at all: exit 0, no output, no notice.
An advisory that chatters gets switched off, and switched off it protects
nothing.

MULTIEDIT IS ABSENT ON PURPOSE, having been checked rather than overlooked. The
vendor tools reference lists Edit and Write and no MultiEdit (0 occurrences, as
in the hooks guide, 2026-08-06), so the `edits[].new_string` shape has no tool
that produces it. Handling it would be a branch no fixture can reach honestly.
"""

import json
import re
import sys

# Python puts this script's own directory on sys.path, so a sibling module
# imports by name. A missing module raises and exits non-zero, which for
# PostToolUse is a visible non-blocking error -- the right failure. A try/except
# fallback here would be worse than the crash: it would ship unsanitized text.
from lib_harness_text import sanitize

# Every pattern traces to a phrase .claude/rules/comment-content.md names. The
# rule is the authority on what each genre is; these only decide when to point
# at it. Three groups, because the scaladoc split above treats them differently.

# "Scope, limitation, or 'honest' narration" -- the rule's own examples.
SCOPE_PATTERNS = [
    ("scope or limitation narration", re.compile(
        r"forward[-\s]only|safety[-\s]?net|cannot repair|\bNOTE:", re.I)),
]

# "Incident or reproduction narration -- dates, branch names, 'deployed via X,
# called N blocks later at M'". The branch alternation is AGENTS.md's own commit
# and branch prefix set. The lookahead is what keeps `docs/architecture.md` and
# `test/resources/x.json` out: a real branch name is not followed by a dot or a
# further path segment.
INCIDENT_PATTERNS = [
    ("incident or reproduction narration", re.compile(
        r"\b20\d{2}-\d{2}-\d{2}\b"
        r"|\bdeployed via\b"
        r"|\bblocks?\s+later\b"
        r"|\b(?:feat|fix|refactor|test|build|docs|chore)/[a-z0-9][a-z0-9-]*(?![.\w/])",
        re.I)),
]

# "Rebuild-provenance narration, which is this repository's live risk". Each
# alternative is narrowed to a provenance sense so it cannot fire on domain
# vocabulary: `legacy` alone would hit the EIP-2718 legacy transaction type, and
# a bare `previously` is ordinary technical prose.
PROVENANCE_PATTERNS = [
    ("rebuild-provenance narration", re.compile(
        r"\bpre-rebuild\b"
        r"|\bthe old (?:code|implementation|version|one)\b"
        r"|\bthis replaces\b"
        r"|\bwe pivoted\b"
        r"|\btech debt\b"
        r"|\bmodernization\b"
        r"|\bused to (?:be|use|live|have)\b"
        r"|\bpreviously (?:was|were|lived|named|called|implemented)\b"
        r"|\bformerly\b"
        r"|\blegacy (?:code|implementation|tree|branch|version)\b", re.I)),
]

LINE_PATTERNS = SCOPE_PATTERNS + INCIDENT_PATTERNS + PROVENANCE_PATTERNS
BLOCK_PATTERNS = PROVENANCE_PATTERNS

RULE = ".claude/rules/comment-content.md"

MAX_FLAGGED = 12
MAX_SNIPPET = 100


def added_text(tool_input):
    """The text this tool call ADDS, or None when no known key carries it.

    Write carries the whole file in `content`; Edit carries only the
    replacement in `new_string`. Neither carries what was already there, which
    is why this hook is forward-looking by construction rather than by choice.

    None and "" are kept apart on purpose: "" is a real empty write and is
    clean, None means the payload shape changed underneath this hook and it can
    no longer see what it is meant to read. Only the second is exit 2.
    """
    if "content" in tool_input:
        return tool_input.get("content") or ""
    if "new_string" in tool_input:
        return tool_input.get("new_string") or ""
    return None


def comment_lines(text):
    """Added comment lines, split by form.

    Returns (line_comments, block_comments). A `//` or `///` line is a line
    comment; a line opening, continuing or closing a block is a block comment,
    which is where scaladoc lives. `@nowarn(...)` and any other annotation start
    with neither marker and are never captured -- so a suppression form some
    future gate mandates is outside this hook without needing an exception.
    """
    lines, blocks = [], []
    for raw in text.splitlines():
        stripped = raw.strip()
        if stripped.startswith("//"):
            lines.append(stripped)
        elif stripped.startswith(("/*", "*/", "*")):
            blocks.append(stripped)
    return lines, blocks


def scan(candidates, patterns):
    """(label, line) for each candidate matching a pattern. First match wins."""
    hits = []
    for line in candidates:
        for label, rx in patterns:
            if rx.search(line):
                hits.append((label, line))
                break
    return hits


def governs(path):
    """True when comment-content.md reaches this file.

    Scala source only, and never a build definition: `.sbt` fails the extension
    test, and anything under a `project/` segment is the metabuild.
    """
    if not path.endswith(".scala"):
        return False
    return "project" not in path.replace("\\", "/").split("/")


def message(flagged):
    """Factual statements, not imperatives.

    The vendor's own guidance for additionalContext: text framed as out-of-band
    system commands can trigger Claude's prompt-injection defenses and get
    surfaced to the operator instead of read as context. So this states what the
    rule is and what matched, and leaves the instruction to the rule.
    """
    out = [
        f"{RULE} governs comment content in this repository. Its "
        '"Never in code" section names genres that belong in the commit '
        "message rather than in source. These added comment lines match "
        "those genres:"
    ]
    for label, line in flagged[:MAX_FLAGGED]:
        # The quoted line is text this hook did not author, going into a system
        # reminder. Escaping is what keeps one crafted comment from forging
        # extra findings with an embedded newline, or reversing how the block
        # reads with a Bidi override. See lib_harness_text.
        out.append(f"  - [{label}] {sanitize(line, MAX_SNIPPET)}")
    if len(flagged) > MAX_FLAGGED:
        out.append(f"  ... and {len(flagged) - MAX_FLAGGED} more")
    out.append(
        f"This is a text match, not a verdict. {RULE} is the authority on what "
        "each genre is, on its exceptions, and on the two genres it names that "
        "no pattern detects."
    )
    return "\n".join(out)


def could_not_run(reason):
    """Exit 2 with the reason on stderr. Never silent, never stdout."""
    sys.stderr.write(f"comment-policy hook could not run: {reason}\n")
    return 2


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return could_not_run("stdin was not JSON")
    if not isinstance(payload, dict):
        return could_not_run("payload was not an object")

    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        return could_not_run("payload carried no tool_input object")

    path = tool_input.get("file_path")
    if not isinstance(path, str) or not path:
        return could_not_run("tool_input carried no file_path")

    if not governs(path):
        return 0

    text = added_text(tool_input)
    if text is None:
        return could_not_run(
            "governed file, but neither content nor new_string was present "
            "-- the payload shape this hook reads has changed")

    lines, blocks = comment_lines(text)
    flagged = scan(lines, LINE_PATTERNS) + scan(blocks, BLOCK_PATTERNS)
    if not flagged:
        return 0

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": message(flagged),
        }
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
