#!/usr/bin/env python3
"""SessionStart hook: inject the ACTIVE roadmap section into startup context.

Reads a SessionStart payload as JSON on stdin, emits JSON on stdout with
`hookSpecificOutput.additionalContext`.

TIER: INJECTS. Not a block, and it cannot become one -- SessionStart cannot
block (Claude Code docs, exit-code table: SessionStart "Shows stderr to user
only"). Calling this enforcement would be the mislabelling this whole phase
exists to avoid. What it buys is that the ACTIVE row arrives without being
remembered, which is the specific failure it addresses: ~100 tasks captured
inline in prose and invisible, because status lived somewhere nobody loaded.

WHY A HOOK RATHER THAN THE RULES FILE. The three standing rules of
plan-management (row-first/file-lazy, WIP=1, capture-does-not-start-work) are
static, so they live in .claude/rules/ and are NOT repeated here -- one
authoritative location per fact. What a rules file cannot carry is the row
itself, which changes as work moves. Dynamic content is the only thing this
hook is for.

THE SEAM, STATED. ROADMAP.md lives under .local/, which is gitignored, so it
does NOT travel to a clone. This hook therefore degrades to silence outside this
machine. That is correct behavior, not a defect: a clone has no roadmap to
inject.

HOW A DEFECT IS MADE VISIBLE, AND WHY NOT ON EXIT 0. An earlier version of this
file wrote every degraded path to stderr, returned 0, and claimed that made a
missing roadmap "visible instead of looking identical to a session with nothing
to report." That was false, and verified false against the docs: stderr from a
SessionStart hook that exits 0 is informational only and reaches the debug log,
not the transcript. So the file asserted it was not a silent no-op while being
exactly one -- the defect class this phase exists to remove, shipped inside the
mechanism meant to remove it.

Exit 2 is what actually surfaces. SessionStart still cannot block (it is not in
the blockable set), but since v2.1.199 its exit-2 stderr renders in the
transcript as a hook-error notice. So exit 2 buys the visibility at zero
blocking risk, and the three paths are NOT equivalent:

  no .local/ at all      -> exit 0, silent. A clone. Expected, not a defect.
  .local/ but no roadmap -> exit 2. This machine owns a roadmap and lost it.
  unreadable             -> exit 2. Always a defect.
  heading renamed        -> exit 2. Dead config, and the one failure the test
                            suite exists to catch.
"""

import json
import os
import subprocess
import sys

ROADMAP_REL = os.path.join(".local", "fresh-build", "ROADMAP.md")
ACTIVE_HEADING = "## ACTIVE"


def project_dir(payload):
    """CLAUDE_PROJECT_DIR if Claude Code set it, else the repo root from cwd."""
    env = os.environ.get("CLAUDE_PROJECT_DIR")
    if env and os.path.isdir(env):
        return env
    cwd = payload.get("cwd") or os.getcwd()
    try:
        top = subprocess.run(
            ["git", "-C", cwd, "rev-parse", "--show-toplevel"],
            capture_output=True, text=True, timeout=5,
        )
        if top.returncode == 0 and top.stdout.strip():
            return top.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        pass
    return cwd


def extract_active(text):
    """The ACTIVE section: its heading through the line before the next '## '."""
    lines = text.splitlines()
    out = []
    capturing = False
    for line in lines:
        if line.startswith(ACTIVE_HEADING):
            capturing = True
            out.append(line)
            continue
        if capturing:
            if line.startswith("## "):
                break
            out.append(line)
    return "\n".join(out).strip()


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        payload = {}

    root = project_dir(payload)
    roadmap = os.path.join(root, ROADMAP_REL)

    if not os.path.isfile(roadmap):
        # A clone has no .local/ at all: correct silence, exit 0. This machine
        # HAS .local/ and lost the roadmap: a real defect, and exit 2 is the
        # only channel that surfaces it. See "HOW A DEFECT IS MADE VISIBLE".
        if not os.path.isdir(os.path.join(root, ".local")):
            return 0
        sys.stderr.write(
            f"session-roadmap: .local/ exists but no roadmap at {roadmap} - "
            "nothing injected. Not a clone, so this is a defect on the machine "
            "that owns the roadmap.\n"
        )
        return 2

    try:
        with open(roadmap, "r", encoding="utf-8") as fh:
            active = extract_active(fh.read())
    except OSError as exc:
        sys.stderr.write(f"session-roadmap: cannot read {roadmap}: {exc}\n")
        return 2

    if not active:
        sys.stderr.write(
            f"session-roadmap: no '{ACTIVE_HEADING}' section found in {roadmap}. "
            "The heading may have been renamed - this hook is now dead config.\n"
        )
        return 2

    context = (
        "Current ACTIVE roadmap section, injected at session start from "
        f"{ROADMAP_REL} (the authoritative status source; status is read from "
        "there, never declared in prose).\n\n"
        f"{active}\n\n"
        "WIP=1: this is the only ACTIVE section. A new fork is captured there as "
        "one row and nowhere else; capturing it does not start it."
    )

    json.dump({
        "hookSpecificOutput": {
            "hookEventName": "SessionStart",
            "additionalContext": context,
        }
    }, sys.stdout)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
