#!/usr/bin/env python3
"""Calibration fixture for bash-guard.py. Run: python3 .claude/hooks/bash-guard.test.py

This is tracked, not scratch, because the guard is tracked. A clone that cannot
re-run the calibration cannot tell a working guard from a dead one -- and a guard
never observed to fire is indistinguishable from one that cannot fire.

Every case runs the real hook as a subprocess with a real payload on stdin, and
asserts the real exit code. Both directions are required: MUST-BLOCK cases prove
it fires, MUST-ALLOW cases prove it discriminates. A suite with only the first
kind would pass for a hook hardcoded to `exit 2`.
"""

import json
import os
import subprocess
import sys

HOOK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bash-guard.py")

# (label, command) -- the guard MUST exit 2 for each.
MUST_BLOCK = [
    ("for loop at start",        'for f in *.md; do wc -l "$f"; done'),
    ("while after a pipe",       "ls | while read x; do echo $x; done"),
    ("if at start",              'if [ -f x ]; then echo y; fi'),
    ("case at start",            'case $x in a) echo b;; esac'),
    ("until at start",           "until false; do echo x; done"),
    ("for after &&",             "cd /tmp && for i in 1 2; do echo $i; done"),
    ("for after ;",              "echo start; for i in 1 2; do echo $i; done"),
    ("loop hidden in bash -c",   'bash -c "for i in 1 2; do echo $i; done"'),
    ("loop hidden in sh -c",     "sh -c 'while true; do echo x; done'"),
    ("check-ignore -v && ",      "git check-ignore -v .env.example && echo IGNORED"),
    ("check-ignore -v ||",       "git check-ignore -v foo || echo 'not ignored'"),
    ("check-ignore --verbose &&","git check-ignore --verbose foo && echo IGNORED"),
]

# (label, command) -- the guard MUST exit 0 for each. These are the negative
# controls, and they are the half that actually constrains the design.
MUST_ALLOW = [
    # The protocol's own prescribed remedy must never be blocked by its own guard.
    ("the prescribed remedy",    "bash .local/scratch/count-md.sh"),
    ("heredoc writing a loop",
     "cat > .local/scratch/x.sh <<'EOF'\nfor f in *.md; do echo $f; done\nEOF"),
    # compound-command-scratch.md Scope: keywords in quoted arguments are excluded.
    ("keyword in -m message",    'git commit -m "fix: while loop in parser"'),
    ("keyword in echo string",   'echo "if you see this, for real"'),
    ("keyword as grep pattern",  "grep -rn 'for' --include='*.md' ."),
    ("keyword as bare arg",      "grep -c if file.txt"),
    ("case as find arg",         "find . -name '*.scala' -exec grep -l case {} ;"),
    # compound-command-scratch.md Scope: simple pipelines with no control flow.
    ("plain pipeline",           "git log --format='%h %s' | head -20"),
    ("plain command",            "sbt compile"),
    ("pipeline with sort/uniq",  "grep x file | sort | uniq -c"),
    # shell-environment.md: -v to DISPLAY the matching line is legitimate.
    ("check-ignore -v display",  "git check-ignore -v .env.example"),
    ("check-ignore -q decision", "git check-ignore --no-index -q -- .env && echo IGNORED"),
    # Documented failure direction: unparseable -> allow, never a false block.
    ("unbalanced quote",         'echo "unterminated'),
    ("empty command",            "   "),
]


def run(payload_obj):
    proc = subprocess.run(
        [sys.executable, HOOK],
        input=json.dumps(payload_obj),
        capture_output=True,
        text=True,
    )
    return proc.returncode, proc.stderr


def bash_payload(command):
    return {
        "session_id": "test",
        "cwd": os.getcwd(),
        "permission_mode": "default",
        "hook_event_name": "PreToolUse",
        "tool_name": "Bash",
        "tool_input": {"command": command, "description": "test"},
        "tool_use_id": "toolu_test",
    }


def main():
    failures = []

    print("--- MUST BLOCK (expect exit 2) ---")
    for label, command in MUST_BLOCK:
        code, err = run(bash_payload(command))
        ok = code == 2
        has_reason = "BLOCKED by" in err
        if not ok or not has_reason:
            failures.append(f"MUST_BLOCK {label!r}: exit={code} reason={has_reason}")
        print(f"  {'ok  ' if ok and has_reason else 'FAIL'} exit={code}  {label}")

    print("\n--- MUST ALLOW (expect exit 0) ---")
    for label, command in MUST_ALLOW:
        code, err = run(bash_payload(command))
        ok = code == 0
        if not ok:
            failures.append(f"MUST_ALLOW {label!r}: exit={code} stderr={err.strip()[:120]}")
        print(f"  {'ok  ' if ok else 'FAIL'} exit={code}  {label}")

    print("\n--- NON-BASH AND MALFORMED (expect exit 0) ---")
    other = [
        ("Read tool payload", {"hook_event_name": "PreToolUse", "tool_name": "Read",
                               "tool_input": {"file_path": "/etc/hostname"}}),
        ("Edit tool payload", {"hook_event_name": "PreToolUse", "tool_name": "Edit",
                               "tool_input": {"file_path": "x", "old_string": "for i in"}}),
        ("no tool_input",     {"hook_event_name": "PreToolUse", "tool_name": "Bash"}),
    ]
    for label, obj in other:
        code, _ = run(obj)
        ok = code == 0
        if not ok:
            failures.append(f"OTHER {label!r}: exit={code}")
        print(f"  {'ok  ' if ok else 'FAIL'} exit={code}  {label}")

    proc = subprocess.run([sys.executable, HOOK], input="not json at all",
                          capture_output=True, text=True)
    ok = proc.returncode == 0
    if not ok:
        failures.append(f"OTHER 'malformed json': exit={proc.returncode}")
    print(f"  {'ok  ' if ok else 'FAIL'} exit={proc.returncode}  malformed json on stdin")

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)})")
        for f in failures:
            print("  " + f)
        return 1
    total = len(MUST_BLOCK) + len(MUST_ALLOW) + len(other) + 1
    print(f"RESULT: PASS - {total} cases, discriminated in both directions "
          f"({len(MUST_BLOCK)} block / {len(MUST_ALLOW) + len(other) + 1} allow)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
