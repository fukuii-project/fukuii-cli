#!/usr/bin/env python3
"""Calibration fixture for session-roadmap.py.

Run: python3 .claude/hooks/session-roadmap.test.py

Three directions, because an injector has three outcomes and only one of them is
"worked": it injected, it correctly stayed silent, or it went dead. The third is
the one that looks identical to the second in production, so it is tested here.
"""

import json
import os
import subprocess
import sys
import tempfile

HOOK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "session-roadmap.py")

ROADMAP_FIXTURE = """# Roadmap

## ACTIVE - exactly one

| # | Section | Plan |
|---|---|---|
| **01** | **fixture-section** | fixture/PLAN.md |

## Blocking the ACTIVE section

| # | Item |
|---|---|
| R99 | must NOT appear in injected context |
"""


def run(cwd, env_extra=None):
    env = dict(os.environ)
    env.pop("CLAUDE_PROJECT_DIR", None)
    if env_extra:
        env.update(env_extra)
    proc = subprocess.run(
        [sys.executable, HOOK],
        input=json.dumps({"hook_event_name": "SessionStart", "cwd": cwd,
                          "session_id": "test", "source": "startup"}),
        capture_output=True, text=True, env=env,
    )
    return proc


def main():
    failures = []

    with tempfile.TemporaryDirectory() as tmp:
        # --- Case 1: roadmap present -> must inject, and must scope correctly
        rm_dir = os.path.join(tmp, "present", ".local", "fresh-build")
        os.makedirs(rm_dir)
        with open(os.path.join(rm_dir, "ROADMAP.md"), "w") as fh:
            fh.write(ROADMAP_FIXTURE)
        proc = run(os.path.join(tmp, "present"),
                   {"CLAUDE_PROJECT_DIR": os.path.join(tmp, "present")})
        ok = proc.returncode == 0
        injected = ""
        try:
            injected = json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
        except (json.JSONDecodeError, KeyError, ValueError):
            ok = False
        has_active = "fixture-section" in injected
        # Negative control on CONTENT: the next section must be excluded, or the
        # extractor is really "dump the whole file" wearing a section's name.
        excludes_next = "R99" not in injected
        if not (ok and has_active and excludes_next):
            failures.append(
                f"present: exit={proc.returncode} json_ok={ok} "
                f"has_active={has_active} excludes_next_section={excludes_next}")
        print(f"  {'ok  ' if ok and has_active and excludes_next else 'FAIL'} "
              f"roadmap present -> injects ACTIVE only "
              f"(has_active={has_active}, excludes_next={excludes_next})")

        # --- Case 2: a CLONE (no .local/ at all) -> correct silence, exit 0
        # This is the only degraded path that is not a defect, so it is the only
        # one that may exit 0. Asserting stderr is EMPTY is the point: a clone
        # must not be nagged about a roadmap it is not supposed to have.
        os.makedirs(os.path.join(tmp, "clone"))
        proc = run(os.path.join(tmp, "clone"),
                   {"CLAUDE_PROJECT_DIR": os.path.join(tmp, "clone")})
        ok = (proc.returncode == 0 and proc.stdout.strip() == ""
              and proc.stderr.strip() == "")
        if not ok:
            failures.append(f"clone: exit={proc.returncode} "
                            f"stdout={proc.stdout!r} stderr={proc.stderr!r}")
        print(f"  {'ok  ' if ok else 'FAIL'} clone (no .local/) -> silent, "
              f"exit={proc.returncode}")

        # --- Case 3: .local/ EXISTS but the roadmap is gone -> defect, exit 2
        # The discriminator this suite previously lacked. Without it, cases 2
        # and 3 collapse into one and the hook cannot tell a clone from a
        # machine that lost its roadmap -- which is the whole distinction.
        lost = os.path.join(tmp, "lost")
        os.makedirs(os.path.join(lost, ".local"))
        proc = run(lost, {"CLAUDE_PROJECT_DIR": lost})
        ok = (proc.returncode == 2 and proc.stdout.strip() == ""
              and "no roadmap" in proc.stderr)
        if not ok:
            failures.append(f"lost: exit={proc.returncode} "
                            f"stdout={proc.stdout!r} stderr={proc.stderr!r}")
        print(f"  {'ok  ' if ok else 'FAIL'} .local/ but no roadmap -> defect, "
              f"exit={proc.returncode}")

        # --- Case 4: roadmap present but heading renamed -> dead config, exit 2
        # Exit 2 rather than 0 because exit-0 SessionStart stderr goes to the
        # debug log, not the transcript -- so on exit 0 this check announced
        # nothing anyone would see, which is the one failure it exists to catch.
        dead_dir = os.path.join(tmp, "dead", ".local", "fresh-build")
        os.makedirs(dead_dir)
        with open(os.path.join(dead_dir, "ROADMAP.md"), "w") as fh:
            fh.write("# Roadmap\n\n## Current work\n\nrenamed heading\n")
        proc = run(os.path.join(tmp, "dead"),
                   {"CLAUDE_PROJECT_DIR": os.path.join(tmp, "dead")})
        ok = (proc.returncode == 2 and proc.stdout.strip() == ""
              and "dead config" in proc.stderr)
        if not ok:
            failures.append(f"dead: exit={proc.returncode} stderr={proc.stderr!r}")
        print(f"  {'ok  ' if ok else 'FAIL'} heading renamed -> dead config, "
              f"exit={proc.returncode}")

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)})")
        for f in failures:
            print("  " + f)
        return 1
    print("RESULT: PASS - 4 cases (injects / clone silent / lost roadmap exit 2 / dead config exit 2)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
