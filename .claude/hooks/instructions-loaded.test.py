#!/usr/bin/env python3
"""Calibration fixture for instructions-loaded.py.

Run: python3 .claude/hooks/instructions-loaded.test.py

WHAT THIS HAS TO PROVE, AND WHY THE OBVIOUS SUITE WOULD NOT.
The event this hook listens to fires only on LOAD. A suite that fed it payloads
and confirmed records appeared would exercise the one verdict the mechanism can
always reach, and would pass unchanged on a build where every path-scoped rule
was silently dead. So the arms below are built around the opposite claim: that
the reconciler reports a rule that did NOT load, and that it refuses to call an
empty observation clean.

Five requirements, from scripts/README.md, and where each lands:

  1. fail on known-bad, naming which case fired   -> arms 4, 9
  2. pass on known-good                           -> arm 5
  3. "could not run" distinct from "clean"        -> arms 6, 7, 12 (exit 2, not 0)
  4. catch a plausible seeded regression          -> arms 10, 11, 13
  5. touch nothing in this repository             -> every arm builds a throwaway
                                                     tree and passes it as ROOT

Arm 8 is the discriminator between the two rule classes, and arm 12 is the one
that keeps the instrument from blaming the rules for its own blind spot.

WHERE THE KNOWN-BAD REFERENCE RESOLVES FROM. The fixtures are literals in this
tracked file at a stable path. They are not read from the repository's own
.claude/rules/, not from `git show HEAD:`, and not from the live log -- so no
edit to this repository's rules, and no amend, can move what the arms compare
against. Changing a fixture means editing this file, visibly, in the diff.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile

HOOK = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "instructions-loaded.py")
LOG_REL = os.path.join(".local", "instructions-loaded.jsonl")

SCOPED_RULE = '---\npaths:\n  - "**/*.scala"\n---\n\n# scoped fixture rule\n'
UNSCOPED_RULE = '# unscoped fixture rule\n\nNo frontmatter, so it always loads.\n'

SESSION = "sess-current"
OLD_SESSION = "sess-previous"


def build_tree(with_local=True):
    """A throwaway repo carrying two rules: one scoped, one not."""
    root = tempfile.mkdtemp()
    os.makedirs(os.path.join(root, ".claude", "rules"))
    with open(os.path.join(root, "CLAUDE.md"), "w") as fh:
        fh.write("@AGENTS.md\n")
    with open(os.path.join(root, "AGENTS.md"), "w") as fh:
        fh.write("# fixture agents file\n")
    with open(os.path.join(root, ".claude", "rules", "scoped.md"), "w") as fh:
        fh.write(SCOPED_RULE)
    with open(os.path.join(root, ".claude", "rules", "unscoped.md"), "w") as fh:
        fh.write(UNSCOPED_RULE)
    if with_local:
        os.makedirs(os.path.join(root, ".local"))
    return root


def write_log(root, records):
    path = os.path.join(root, LOG_REL)
    with open(path, "w") as fh:
        for rec in records:
            fh.write(json.dumps(rec) + "\n")


def rec(file, reason="session_start", session=SESSION, **kw):
    out = {"ts": "2026-01-01T00:00:00Z", "session_id": session,
           "file": file, "memory_type": "Project", "load_reason": reason}
    out.update(kw)
    return out


# The four roster members a fully-loaded session would show. Scoped rules are
# deliberately excluded: they are not expected at session start.
FULL = [rec("CLAUDE.md"), rec("AGENTS.md", "include"),
        rec(os.path.join(".claude", "rules", "unscoped.md"))]


def run_report(root, hook=HOOK, extra=()):
    env = dict(os.environ)
    env.pop("CLAUDE_PROJECT_DIR", None)
    return subprocess.run([sys.executable, hook, "--report", root, *extra],
                          capture_output=True, text=True, env=env)


def run_record(root, payload):
    env = dict(os.environ)
    env["CLAUDE_PROJECT_DIR"] = root
    return subprocess.run([sys.executable, HOOK], input=json.dumps(payload),
                          capture_output=True, text=True, env=env)


def read_log(root):
    path = os.path.join(root, LOG_REL)
    if not os.path.isfile(path):
        return []
    with open(path) as fh:
        return [json.loads(x) for x in fh if x.strip()]


def mutate(substitution):
    """Copy the hook with one edit applied. Returns (path, applied)."""
    with open(HOOK) as fh:
        src = fh.read()
    old, new = substitution
    if old not in src:
        return None, False
    fd, path = tempfile.mkstemp(suffix=".py")
    with os.fdopen(fd, "w") as fh:
        fh.write(src.replace(old, new, 1))
    return path, True


def main():
    failures = []

    def check(label, ok, detail=""):
        print(f"  {'ok  ' if ok else 'FAIL'} {label}" + (f"  [{detail}]" if detail else ""))
        if not ok:
            failures.append(f"{label} {detail}")

    # ---- ARM 1: RECORD writes a record through the real stdin path ---------
    root = build_tree()
    proc = run_record(root, {
        "hook_event_name": "InstructionsLoaded", "session_id": SESSION,
        "cwd": root, "file_path": os.path.join(root, ".claude", "rules", "scoped.md"),
        "memory_type": "Project", "load_reason": "path_glob_match",
        "globs": ["**/*.scala"],
        "trigger_file_path": os.path.join(root, "src", "Main.scala"),
    })
    log = read_log(root)
    ok = (proc.returncode == 0 and len(log) == 1
          and log[0]["file"] == os.path.join(".claude", "rules", "scoped.md")
          and log[0]["load_reason"] == "path_glob_match"
          and log[0]["trigger"] == os.path.join("src", "Main.scala")
          and log[0]["globs"] == ["**/*.scala"])
    check("RECORD: payload on stdin -> one record, paths relativized", ok,
          f"exit={proc.returncode} n={len(log)}")
    shutil.rmtree(root)

    # ---- ARM 2: a clone (no .local/) is silent and writes nothing ----------
    root = build_tree(with_local=False)
    proc = run_record(root, {"hook_event_name": "InstructionsLoaded",
                             "session_id": SESSION, "cwd": root,
                             "file_path": "CLAUDE.md", "load_reason": "session_start"})
    ok = (proc.returncode == 0 and proc.stdout.strip() == ""
          and not os.path.exists(os.path.join(root, ".local")))
    check("RECORD: clone (no .local/) -> silent, creates nothing", ok,
          f"exit={proc.returncode}")
    shutil.rmtree(root)

    # ---- ARM 3: a subagent payload is recorded AS a subagent --------------
    # This is the arm that makes the log able to answer whether the event fires
    # for subagent dispatches. Without it the report's subagent section could
    # only ever say "no records", which is not the same claim as "does not fire".
    root = build_tree()
    run_record(root, {"hook_event_name": "InstructionsLoaded", "session_id": SESSION,
                      "cwd": root, "file_path": "CLAUDE.md",
                      "load_reason": "session_start",
                      "agent_id": "agt-1", "agent_type": "general-purpose"})
    log = read_log(root)
    ok = len(log) == 1 and log[0].get("agent_id") == "agt-1" and \
        log[0].get("agent_type") == "general-purpose"
    check("RECORD: subagent payload -> agent_id/agent_type captured", ok)
    out = run_report(root).stdout
    check("REPORT: names the subagent dispatch when agent_id is present",
          "OBSERVED:" in out and "general-purpose" in out)
    shutil.rmtree(root)

    # ---- ARM 4: KNOWN-BAD -- an unscoped rule never loaded ----------------
    root = build_tree()
    write_log(root, [r for r in FULL
                     if r["file"] != os.path.join(".claude", "rules", "unscoped.md")])
    proc = run_report(root)
    named = "unscoped.md" in proc.stdout
    ok = proc.returncode == 1 and named and "FINDINGS" in proc.stdout
    check("KNOWN-BAD: unscoped rule absent -> exit 1, and NAMES the file", ok,
          f"exit={proc.returncode} named={named}")
    shutil.rmtree(root)

    # ---- ARM 5: KNOWN-GOOD -- every unscoped member observed --------------
    root = build_tree()
    write_log(root, FULL)
    proc = run_report(root)
    ok = proc.returncode == 0 and "CLEAN" in proc.stdout
    check("KNOWN-GOOD: all unscoped members observed -> exit 0", ok,
          f"exit={proc.returncode}")
    shutil.rmtree(root)

    # ---- ARM 6: EMPTY log is COULD NOT RUN, never clean -------------------
    root = build_tree()
    write_log(root, [])
    proc = run_report(root)
    ok = proc.returncode == 2 and "COULD NOT RUN" in proc.stdout
    check("EMPTY log -> exit 2 (not 0). The 0-for-73 discriminator.", ok,
          f"exit={proc.returncode}")
    shutil.rmtree(root)

    # ---- ARM 7: ABSENT log is COULD NOT RUN -------------------------------
    root = build_tree()
    proc = run_report(root)
    ok = proc.returncode == 2 and "COULD NOT RUN" in proc.stdout
    check("ABSENT log (hook never ran) -> exit 2", ok, f"exit={proc.returncode}")
    shutil.rmtree(root)

    # ---- ARM 8: a SCOPED rule's absence is reported but is NOT a failure ---
    # The discriminator between the two rule classes. Collapsed, the report
    # either cries wolf on every session or goes silent on a dead unscoped rule.
    root = build_tree()
    write_log(root, FULL)
    proc = run_report(root)
    ok = proc.returncode == 0 and "scoped.md" in proc.stdout \
        and "the glob is dead" in proc.stdout
    check("SCOPED rule absent -> reported, exit 0 (not a failure on its own)", ok,
          f"exit={proc.returncode}")
    shutil.rmtree(root)

    # ---- ARM 9: session scoping -- an OLD load must not mask a new gap -----
    root = build_tree()
    write_log(root, [rec(os.path.join(".claude", "rules", "unscoped.md"),
                         session=OLD_SESSION),
                     rec("CLAUDE.md", session=OLD_SESSION),
                     rec("AGENTS.md", "include", session=OLD_SESSION),
                     rec("CLAUDE.md"), rec("AGENTS.md", "include")])
    proc = run_report(root)
    ok = proc.returncode == 1 and "unscoped.md" in proc.stdout
    check("SESSION SCOPING: a previous session's load does not mask a gap", ok,
          f"exit={proc.returncode}")

    # ---- ARM 10: MUTANT -- session scoping dropped ------------------------
    # The plausible edit: "why filter by session, just use every record."
    # It silently converts arm 9's finding into a clean report.
    mutant, applied = mutate((
        'window = [r for r in records if r.get("session_id") == target]',
        'window = records'))
    if not applied:
        check("MUTANT 1 applied (session filter)", False, "anchor moved")
    else:
        mproc = run_report(root, hook=mutant)
        check("MUTANT 1 (session filter dropped) is CAUGHT -> was 1, now not 1",
              mproc.returncode != 1, f"mutant exit={mproc.returncode}")
        os.unlink(mutant)
    shutil.rmtree(root)

    # ---- ARM 11: MUTANT -- every missing member treated as scoped ---------
    # The plausible edit: "absence is never hard-fail, just report it."
    # It removes the only reachable fail state the report has.
    root = build_tree()
    write_log(root, [r for r in FULL
                     if r["file"] != os.path.join(".claude", "rules", "unscoped.md")])
    mutant, applied = mutate(('            if member["globs"]:\n'
                              '                scoped_missing.append(member)',
                              '            if True:\n'
                              '                scoped_missing.append(member)'))
    if not applied:
        check("MUTANT 2 applied (scoped/unscoped split)", False, "anchor moved")
    else:
        mproc = run_report(root, hook=mutant)
        check("MUTANT 2 (missing always 'scoped') is CAUGHT -> was 1, now not 1",
              mproc.returncode != 1, f"mutant exit={mproc.returncode}")
        os.unlink(mutant)
    shutil.rmtree(root)

    # ---- ARM 12: registered MID-SESSION -> could not run, not findings -----
    # The window holds only lazy loads, so the recorder was not live when the
    # eager loads happened. Reporting that as FINDINGS would blame the rules for
    # the instrument's own blind spot -- the false positive this artifact would
    # otherwise produce on its very first real run.
    root = build_tree()
    write_log(root, [rec(os.path.join(".claude", "rules", "scoped.md"),
                         "path_glob_match", trigger="src/Main.scala")])
    proc = run_report(root)
    ok = proc.returncode == 2 and "registered mid-session" in proc.stdout
    check("MID-SESSION registration -> exit 2 (not 1). No false finding.", ok,
          f"exit={proc.returncode}")
    shutil.rmtree(root)

    # ---- ARM 13: MUTANT -- the mid-session guard broadened to always fire --
    # The plausible edit: "this guard keeps mis-firing, widen it." It swallows
    # every real finding, which is arm 4 turning green.
    root = build_tree()
    write_log(root, [r for r in FULL
                     if r["file"] != os.path.join(".claude", "rules", "unscoped.md")])
    mutant, applied = mutate((
        'if not any(r.get("load_reason") == "session_start" for r in window):',
        'if True:'))
    if not applied:
        check("MUTANT 3 applied (mid-session guard)", False, "anchor moved")
    else:
        mproc = run_report(root, hook=mutant)
        check("MUTANT 3 (guard always fires) is CAUGHT -> was 1, now not 1",
              mproc.returncode != 1, f"mutant exit={mproc.returncode}")
        os.unlink(mutant)
    shutil.rmtree(root)

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)})")
        for f in failures:
            print("  " + f)
        return 1
    print("RESULT: PASS — records through the real stdin path, stays silent in a")
    print("clone, reports a rule that DID load and one that did NOT, refuses to")
    print("call an empty, absent, or mid-session log clean, keeps scoped and")
    print("unscoped absence distinct, and catches every seeded regression.")
    print("No arm touched this repository.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
