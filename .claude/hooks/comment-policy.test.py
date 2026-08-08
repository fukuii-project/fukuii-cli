#!/usr/bin/env python3
"""Calibration fixture for comment-policy.py.

Run: python3 .claude/hooks/comment-policy.test.py

WHAT THIS HAS TO PROVE, AND WHY A NAIVE SUITE WOULD NOT. An advisory hook's
whole output channel is optional: silence is its ordinary result. So a suite
that fed it text and confirmed nothing exploded would pass unchanged on a hook
whose patterns matched nothing at all -- a check with no reachable fire state,
reporting green forever. Every arm below therefore asserts a DIRECTION: which
lines fired, under which label, and which lines must not fire.

Five requirements, from scripts/README.md, and where each lands:

  1. fail on known-bad, naming which case fired   -> arms 1-4, 9; each asserts
                                                     the LABEL and the LINE,
                                                     never merely "output"
  2. pass on known-good                           -> arms 5-8
  3. "could not run" distinct from "clean"        -> arms 10, 11 (exit 2 with
                                                     stderr, against arm 6-8's
                                                     exit 0 and silence)
  4. catch a plausible seeded regression          -> arms 12-15, 17
  5. touch nothing in this repository             -> the hook reads only stdin;
                                                     no arm opens a file, and
                                                     the mutants are temp copies

Arms 4 and 5 are the discriminator for the scaladoc split, which is the one
place the hook resolves an ambiguity in the rule rather than applying it. Arm 6
is the negative control for the three narrowed patterns -- a branch-shaped doc
path, an EIP-2718 legacy transaction, and an ordinary use of "previously" --
and arms 12-14 are the mutants that turn each of those into a false positive.

ARMS 16 AND 17 ARE A DIFFERENT GRADE, AND THE SUITE IS INCOMPLETE WITHOUT THEM.
Arms 1-15 are input a careless author produces. This hook quotes text it did not
write into a system reminder, so it also needs input a hostile one constructs:
arm 16 is that fixture, and arm 17 is the mutant proving the escaping behind it
can still fail. Arm 17 edits the shared LIBRARY rather than the hook, because a
mutant applied to the hook cannot reach an import.

WHERE THE KNOWN-BAD REFERENCE RESOLVES FROM. Every fixture is a literal in this
tracked file at a stable path. Nothing is read from the repository's own rules,
from `git show HEAD:`, or from any file the hook itself might scan -- so no edit
to this repository, and no amend, can move what the arms compare against.
Changing a fixture means editing this file, visibly, in the diff.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile

HOOK = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "comment-policy.py")

SCALA = "/repo/src/main/scala/org/fukuii/Codec.scala"
BUILD = "/repo/project/Dependencies.scala"
NOT_SCALA = "/repo/docs/notes.md"

SCOPE = "scope or limitation narration"
INCIDENT = "incident or reproduction narration"
PROVENANCE = "rebuild-provenance narration"


def run(payload, hook=HOOK):
    """Drive the hook the way the dispatcher does: JSON on stdin, nothing else."""
    return subprocess.run([sys.executable, hook], input=json.dumps(payload),
                          capture_output=True, text=True)


def run_raw(text, hook=HOOK):
    return subprocess.run([sys.executable, hook], input=text,
                          capture_output=True, text=True)


def write_payload(path, content):
    return {"hook_event_name": "PostToolUse", "tool_name": "Write",
            "tool_input": {"file_path": path, "content": content}}


def edit_payload(path, new_string):
    return {"hook_event_name": "PostToolUse", "tool_name": "Edit",
            "tool_input": {"file_path": path, "old_string": "x",
                           "new_string": new_string}}


def context(proc):
    """The advisory text, or None when the hook stayed silent."""
    if not proc.stdout.strip():
        return None
    try:
        return json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
    except (json.JSONDecodeError, KeyError, TypeError):
        return None


LIB = "lib_harness_text.py"


def mutate(substitution, target="hook"):
    """Copy hook AND library into a temp dir, applying one edit to `target`.

    BOTH files are always copied, and that is not tidiness. An earlier version
    copied the hook alone to a bare temp path, where the sibling import failed
    -- so every mutant died of ModuleNotFoundError instead of the seeded
    regression, and one arm read as CAUGHT while proving nothing about the
    behavior it named. That is the placebo this file exists to rule out,
    reached through the harness rather than through the fixture.

    Copying the library pristine beside the target is also what makes
    target="lib" possible: escaping lives behind an import, so a mutant applied
    to the hook can never reach it, and a control that cannot be broken is not
    a control. Returns (hook path inside the temp dir, applied).
    """
    names = {"hook": os.path.basename(HOOK), "lib": LIB}
    edited = names[target]
    sources = {n: open(os.path.join(os.path.dirname(HOOK), n)).read()
               for n in names.values()}
    old, new = substitution
    if old not in sources[edited]:
        return None, False
    sources[edited] = sources[edited].replace(old, new, 1)
    d = tempfile.mkdtemp()
    for name, text in sources.items():
        with open(os.path.join(d, name), "w") as fh:
            fh.write(text)
    return os.path.join(d, os.path.basename(HOOK)), True


def discard(mutant):
    shutil.rmtree(os.path.dirname(mutant))


# --- fixtures -------------------------------------------------------------
# Each known-bad body carries exactly one offending line so an arm can assert
# WHICH case fired. A body with three offenders would pass on any one of them.

BAD_SCOPE = """package org.fukuii

// NOTE: this only covers the canonical encoding
def encode(x: Int): Array[Byte] = ???
"""

BAD_INCIDENT = """package org.fukuii

// reproduced on 2026-07-16 against fix/rlp-length-prefix
def decode(b: Array[Byte]): Int = ???
"""

BAD_PROVENANCE_LINE = """package org.fukuii

// this replaces the hand-rolled encoder
def encode(x: Int): Array[Byte] = ???
"""

BAD_PROVENANCE_SCALADOC = """package org.fukuii

/** Encodes an integer.
  *
  * The old implementation kept a mutable buffer here.
  */
def encode(x: Int): Array[Byte] = ???
"""

# The split's quiet half: scope narration inside a block comment. Under the
# reading this hook adopts, the "Never in code" scope genre is scoped to inline
# `//`, so scaladoc is left to review.
GOOD_SCOPE_SCALADOC = """package org.fukuii

/** Encodes an integer.
  *
  * NOTE: this only covers the canonical encoding.
  */
def encode(x: Int): Array[Byte] = ???
"""

# The negative control for all three narrowed patterns at once. Every line here
# is text a careful author legitimately writes, and each one is adjacent to a
# pattern: a doc path shaped like a branch name, the EIP-2718 transaction type
# named "legacy", and an ordinary temporal "previously".
GOOD_LEGITIMATE = """package org.fukuii

// see docs/architecture.md for the layering
// legacy transaction envelope, EIP-2718 type 0x00
// the digest previously computed by the caller is reused, not recomputed
// gas is charged before the copy, which the obvious ordering gets wrong
def encode(x: Int): Array[Byte] = ???
"""

# Build definitions are a register comment-content.md does not reach: rationale
# IS the content there. Same offending text as BAD_PROVENANCE_LINE.
BUILD_BODY = """// this replaces the hand-rolled encoder
val scalatestVersion = "3.2.20"
"""


def main():
    failures = []

    def check(label, ok, detail=""):
        print(f"  {'ok  ' if ok else 'FAIL'} {label}" + (f"  [{detail}]" if detail else ""))
        if not ok:
            failures.append(f"{label} {detail}")

    # ---- ARM 1: KNOWN-BAD -- scope narration on a `//` line ---------------
    proc = run(write_payload(SCALA, BAD_SCOPE))
    ctx = context(proc)
    ok = (proc.returncode == 0 and ctx is not None
          and SCOPE in ctx and "NOTE: this only covers" in ctx
          and ".claude/rules/comment-content.md" in ctx)
    check("KNOWN-BAD scope: fires, labels it SCOPE, quotes the line, names the rule",
          ok, f"exit={proc.returncode} ctx={'yes' if ctx else 'no'}")

    # ---- ARM 2: KNOWN-BAD -- incident narration (date AND branch name) ----
    proc = run(write_payload(SCALA, BAD_INCIDENT))
    ctx = context(proc)
    ok = ctx is not None and INCIDENT in ctx and "2026-07-16" in ctx
    check("KNOWN-BAD incident: fires on a date + branch name, labelled INCIDENT", ok)

    # ---- ARM 3: KNOWN-BAD -- provenance on a `//` line --------------------
    proc = run(write_payload(SCALA, BAD_PROVENANCE_LINE))
    ctx = context(proc)
    ok = ctx is not None and PROVENANCE in ctx and "this replaces" in ctx
    check("KNOWN-BAD provenance (line): fires, labelled PROVENANCE", ok)

    # ---- ARM 4: KNOWN-BAD -- provenance inside SCALADOC -------------------
    # The fire half of the scaladoc split. Provenance reaches every comment
    # form, because evidence-and-citation section 4 has no scaladoc carve-out.
    proc = run(write_payload(SCALA, BAD_PROVENANCE_SCALADOC))
    ctx = context(proc)
    ok = ctx is not None and PROVENANCE in ctx and "The old implementation" in ctx
    check("KNOWN-BAD provenance (scaladoc): fires -- the split's fire half", ok)

    # ---- ARM 5: KNOWN-GOOD -- scope narration inside SCALADOC -------------
    # The quiet half of the same split. Collapsing arms 4 and 5 would leave the
    # split untested in both directions at once.
    proc = run(write_payload(SCALA, GOOD_SCOPE_SCALADOC))
    ok = proc.returncode == 0 and context(proc) is None
    check("KNOWN-GOOD scope in scaladoc: SILENT -- the split's quiet half", ok,
          f"exit={proc.returncode}")

    # ---- ARM 6: KNOWN-GOOD -- legitimate comments near every pattern ------
    proc = run(write_payload(SCALA, GOOD_LEGITIMATE))
    ok = proc.returncode == 0 and context(proc) is None
    check("KNOWN-GOOD: doc path, EIP-2718 legacy tx, ordinary 'previously' -> SILENT",
          ok, f"exit={proc.returncode} out={proc.stdout.strip()[:60]!r}")

    # ---- ARM 7: KNOWN-GOOD -- a build definition is a different register --
    proc = run(write_payload(BUILD, BUILD_BODY))
    ok = proc.returncode == 0 and context(proc) is None
    check("KNOWN-GOOD build definition (project/): SILENT on the same bad text", ok,
          f"exit={proc.returncode}")

    # ---- ARM 8: KNOWN-GOOD -- a non-Scala file is out of scope ------------
    proc = run(write_payload(NOT_SCALA, BAD_PROVENANCE_LINE))
    ok = proc.returncode == 0 and context(proc) is None
    check("KNOWN-GOOD non-Scala file: SILENT", ok, f"exit={proc.returncode}")

    # ---- ARM 9: the Edit branch of the extractor is live ------------------
    # Write carries `content` and Edit carries `new_string`. A suite driving
    # only Write would leave half the extractor unexercised, and the "simplify
    # this to one key" edit would go uncaught -- which is arm 15.
    proc = run(edit_payload(SCALA, "// we pivoted from the buffered form\n"))
    ctx = context(proc)
    ok = ctx is not None and PROVENANCE in ctx and "we pivoted" in ctx
    check("Edit payload (new_string) is scanned, not just Write (content)", ok)

    # ---- ARM 10: COULD NOT RUN -- stdin is not JSON -----------------------
    # Requirement 3. Silence-and-0 is this hook's CLEAN result, so a malformed
    # payload that also produced silence-and-0 would be indistinguishable from
    # a clean file: the hook would report "checked, nothing found" having
    # checked nothing. Exit 2 is what separates them.
    proc = run_raw("this is not json")
    ok = proc.returncode == 2 and proc.stdout.strip() == "" and proc.stderr.strip() != ""
    check("COULD NOT RUN: unparseable stdin -> exit 2 + stderr, never a silent 0",
          ok, f"exit={proc.returncode}")

    # ---- ARM 11: COULD NOT RUN -- in-scope file, no added-text field ------
    # The vendor could rename `content` tomorrow. Reporting clean in that case
    # is the silent-no-op this whole suite exists to rule out.
    proc = run({"hook_event_name": "PostToolUse", "tool_name": "Write",
                "tool_input": {"file_path": SCALA}})
    ok = proc.returncode == 2 and proc.stderr.strip() != ""
    check("COULD NOT RUN: governed file, no content/new_string -> exit 2", ok,
          f"exit={proc.returncode}")

    # ---- ARM 12: MUTANT -- the branch-name lookahead dropped --------------
    # The plausible edit: "this negative lookahead is unreadable, drop it."
    # It converts every `docs/x.md` reference in a comment into a finding.
    mutant, applied = mutate((r"[a-z0-9][a-z0-9-]*(?![.\w/])",
                              r"[a-z0-9][a-z0-9-]*"))
    if not applied:
        check("MUTANT 1 applied (branch lookahead)", False, "anchor moved")
    else:
        mctx = context(run(write_payload(SCALA, GOOD_LEGITIMATE), hook=mutant))
        check("MUTANT 1 (lookahead dropped) is CAUGHT -> arm 6 was silent, now fires",
              mctx is not None and INCIDENT in mctx)
        discard(mutant)

    # ---- ARM 13: MUTANT -- the build-register exclusion dropped -----------
    # The plausible edit: "a .scala file is a .scala file."
    mutant, applied = mutate((
        'return "project" not in path.replace("\\\\", "/").split("/")',
        "return True"))
    if not applied:
        check("MUTANT 2 applied (project/ exclusion)", False, "anchor moved")
    else:
        mctx = context(run(write_payload(BUILD, BUILD_BODY), hook=mutant))
        check("MUTANT 2 (build register scanned) is CAUGHT -> arm 7 now fires",
              mctx is not None and PROVENANCE in mctx)
        discard(mutant)

    # ---- ARM 14: MUTANT -- the scaladoc split collapsed ------------------
    # The plausible edit: "why do block comments get a different pattern set."
    mutant, applied = mutate(("BLOCK_PATTERNS = PROVENANCE_PATTERNS",
                              "BLOCK_PATTERNS = LINE_PATTERNS"))
    if not applied:
        check("MUTANT 3 applied (scaladoc split)", False, "anchor moved")
    else:
        mctx = context(run(write_payload(SCALA, GOOD_SCOPE_SCALADOC), hook=mutant))
        check("MUTANT 3 (split collapsed) is CAUGHT -> arm 5 was silent, now fires",
              mctx is not None and SCOPE in mctx)
        discard(mutant)

    # ---- ARM 15: MUTANT -- the Edit branch of the extractor removed ------
    # The plausible edit: "Write is the only tool that adds a whole file."
    # It silently blinds the hook to every Edit, which is most edits.
    mutant, applied = mutate(('    if "new_string" in tool_input:\n'
                              '        return tool_input.get("new_string") or ""\n',
                              ""))
    if not applied:
        check("MUTANT 4 applied (Edit extractor)", False, "anchor moved")
    else:
        mproc = run(edit_payload(SCALA, "// we pivoted from the buffered form\n"),
                    hook=mutant)
        # The exit code is asserted, not merely the silence. Under this mutant
        # an Edit carries no recognizable added-text field, so the exit-2
        # could-not-run path must fire. "No advisory" alone would also be
        # satisfied by the mutant crashing on import, which is how this arm
        # once passed while proving nothing -- exactly the incidental-reason
        # placebo the harness note above describes.
        check("MUTANT 4 (Edit branch removed) is CAUGHT -> arm 9 becomes exit 2, "
              "not a crash",
              context(mproc) is None and mproc.returncode == 2,
              f"mutant exit={mproc.returncode}")
        discard(mutant)

    # ---- ARM 16: ADVERSARIAL -- text shaped to attack the output channel --
    # Every fixture above is what a careless author writes. This is what a
    # hostile one constructs: the quoted line lands in a system reminder, so a
    # Bidi override could reverse how the finding block reads and a NUL could
    # break the one-line-per-finding shape. Note splitlines() already consumes
    # newline-equivalents, U+2028 and U+2029 included -- these two are what
    # actually survives into an echoed line.
    # Built from escape sequences, never written literally: a real U+202E in
    # this file would reverse the display of every line after it, in the editor
    # and in the diff. The fixture must not be the attack it tests for.
    rlo, nul = "\u202E", "\x00"
    proc = run(write_payload(SCALA, f"// this replaces the old code {rlo}{nul}x\n"))
    ctx = context(proc) or ""
    check("ADVERSARIAL: fires on the crafted line at all", PROVENANCE in ctx)
    check("  Bidi override is ESCAPED, not passed through",
          "\\u202e" in ctx and rlo not in ctx)
    check("  NUL is ESCAPED, not passed through",
          "\\u0000" in ctx and nul not in ctx)

    # ---- ARM 17: MUTANT -- the Bidi ranges dropped from the library -------
    # The plausible edit, and the exact one the house standard records being
    # made twice by hand: "the control-character ranges are the real risk, the
    # Bidi ones look like ordinary text." It is invisible in the hook's own
    # diff, which is why this mutant has to reach the library.
    mutant, applied = mutate(
        ("    (0x202A, 0x202E),   # LRE, RLE, PDF, LRO, RLO\n", ""), target="lib")
    if not applied:
        check("MUTANT 5 applied (Bidi ranges)", False, "anchor moved")
    else:
        mctx = context(run(write_payload(
            SCALA, f"// this replaces the old code {rlo}x\n"), hook=mutant)) or ""
        check("MUTANT 5 (Bidi range removed) is CAUGHT -> raw override now reaches output",
              rlo in mctx)
        discard(mutant)

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)})")
        for f in failures:
            print("  " + f)
        return 1
    print("RESULT: PASS — fires on each of the three genres and names which one,")
    print("stays silent on legitimate comments sitting next to every narrowed")
    print("pattern, honours the build-definition register and the scaladoc split")
    print("in both directions, separates 'could not run' from 'clean' by exit")
    print("code, escapes a Bidi override and a NUL out of the reminder channel,")
    print("and catches all five seeded regressions including one in the library.")
    print("No arm touched this repository; the hook reads only stdin.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
