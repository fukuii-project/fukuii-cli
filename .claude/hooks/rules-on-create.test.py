#!/usr/bin/env python3
"""Calibration fixture for rules-on-create.py.

Run: python3 .claude/hooks/rules-on-create.test.py

WHAT THIS HAS TO PROVE, AND WHY A NAIVE SUITE WOULD NOT. This hook's ordinary
result is silence, and its whole value rests on ONE discriminator: a file that
does not exist yet versus one that does. A suite that only fed it a new .scala
file and confirmed something came out would pass unchanged on a hook that fired
on every write ever made -- which is a check with no reachable QUIET state, and
those get switched off within a week. So the arms below are built in pairs:
every fire has a matching silence that differs in exactly one property.

Five requirements, from scripts/README.md, and where each lands:

  1. fail on known-bad, naming which case fired   -> arms 1, 2; each asserts the
                                                     RULE FILE and GLOB named,
                                                     and which were left out
  2. pass on known-good                           -> arms 3-7
  3. "could not run" distinct from "clean"        -> arms 9, 10 (exit 1 with
                                                     stderr, against arm 3-7's
                                                     exit 0 and silence)
  4. catch a plausible seeded regression          -> arms 12-15
  5. touch nothing in this repository             -> every arm builds a
                                                     throwaway tree; no arm
                                                     reads this repository's
                                                     own rules

ARM 12 IS THE SAFETY ARM AND IS NOT OPTIONAL. On PreToolUse, exit 2 BLOCKS the
tool call. An advisory that acquires a blocking exit path stops writes on a
malformed payload, which is worse than the gap it was built to cover. That arm
seeds exactly that regression and requires it to be caught, and arm 11 asserts
the same invariant over every other arm's exit code rather than arguing for it.

ARM 16 IS A DIFFERENT GRADE. Arms 1-15 are input a careless author produces.
This hook puts two things it did not write into a system reminder -- a path from
the payload and a rule FILENAME from a directory a fork controls -- so it also
needs input a hostile one constructs. The escaping behind that arm lives in a
shared library, and the mutant proving it can still fail is carried by
comment-policy.test.py, which drives the same library through the same call.

WHERE THE KNOWN-BAD REFERENCE RESOLVES FROM. The rule fixtures are literals in
this tracked file, written into a temporary tree. Nothing reads this
repository's own .claude/rules/, nothing resolves through `git show HEAD:`, and
no arm consults the live file the hook would scan -- so rescoping a real rule
cannot move what these arms compare against. Changing a fixture means editing
this file, visibly, in the diff.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile

HOOK = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "rules-on-create.py")

# Two scoped rules that differ ONLY in anchoring, plus one unscoped. The pair is
# what makes root-anchoring testable: a rule scoped `*.scala` must reach a file
# at the root and must not reach one in a subdirectory.
DEEP_RULE = '---\npaths:\n  - "**/*.scala"\n---\n\n# reaches any depth\n'
ROOT_RULE = '---\npaths:\n  - "*.scala"\n---\n\n# root-anchored\n'
UNSCOPED_RULE = '# unscoped\n\nNo frontmatter, so it already loads every session.\n'

NESTED = "src/main/scala/org/fukuii/Codec.scala"
AT_ROOT = "Codec.scala"


def build_tree(rules=True, agents=True):
    root = tempfile.mkdtemp()
    if rules:
        rules_dir = os.path.join(root, ".claude", "rules")
        os.makedirs(rules_dir)
        with open(os.path.join(rules_dir, "deep.md"), "w") as fh:
            fh.write(DEEP_RULE)
        with open(os.path.join(rules_dir, "rooted.md"), "w") as fh:
            fh.write(ROOT_RULE)
        with open(os.path.join(rules_dir, "unscoped.md"), "w") as fh:
            fh.write(UNSCOPED_RULE)
    if agents:
        with open(os.path.join(root, "AGENTS.md"), "w") as fh:
            fh.write("# fixture\n\n## Code style\n")
    return root


def touch(root, rel):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write("package org.fukuii\n")
    return path


def run(root, rel, use_env=True, hook=HOOK):
    """Drive the hook as the dispatcher does: JSON on stdin, nothing else."""
    payload = {"hook_event_name": "PreToolUse", "tool_name": "Write",
               "cwd": root,
               "tool_input": {"file_path": os.path.join(root, rel),
                              "content": "package org.fukuii\n"}}
    env = dict(os.environ)
    if use_env:
        env["CLAUDE_PROJECT_DIR"] = root
    else:
        env.pop("CLAUDE_PROJECT_DIR", None)
    return subprocess.run([sys.executable, hook], input=json.dumps(payload),
                          capture_output=True, text=True, env=env)


def run_payload(payload, hook=HOOK):
    env = dict(os.environ)
    env.pop("CLAUDE_PROJECT_DIR", None)
    return subprocess.run([sys.executable, hook], input=json.dumps(payload),
                          capture_output=True, text=True, env=env)


def run_raw(text, hook=HOOK):
    env = dict(os.environ)
    env.pop("CLAUDE_PROJECT_DIR", None)
    return subprocess.run([sys.executable, hook], input=text,
                          capture_output=True, text=True, env=env)


def context(proc):
    if not proc.stdout.strip():
        return None
    try:
        return json.loads(proc.stdout)["hookSpecificOutput"]["additionalContext"]
    except (json.JSONDecodeError, KeyError, TypeError):
        return None


LIB = "lib_harness_text.py"


def mutate(substitution, target="hook"):
    """Copy hook AND library into a temp dir, applying one edit to `target`.

    BOTH files are always copied, and that is not tidiness. Copying the hook
    alone to a bare temp path breaks the sibling import, so every mutant dies of
    ModuleNotFoundError rather than of the seeded regression -- and an arm whose
    assertion is satisfied by ANY failure then reads as CAUGHT while proving
    nothing about the behaviour it names. Returns (hook path in the temp dir,
    applied).
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


def main():
    failures = []
    codes = []

    def check(label, ok, detail=""):
        print(f"  {'ok  ' if ok else 'FAIL'} {label}" + (f"  [{detail}]" if detail else ""))
        if not ok:
            failures.append(f"{label} {detail}")

    # ---- ARM 1: FIRES on a new nested .scala, and names WHICH rules --------
    # Four properties in one arm, each asserted separately: it fires; `**/`
    # reaches depth; `*.scala` stays root-anchored and is correctly absent; an
    # unscoped rule is correctly absent because it already loads.
    root = build_tree()
    proc = run(root, NESTED)
    codes.append(proc.returncode)
    ctx = context(proc) or ""
    check("FIRES on a new nested .scala file", proc.returncode == 0 and bool(ctx),
          f"exit={proc.returncode}")
    check("  names the depth-reaching rule and its glob",
          ".claude/rules/deep.md" in ctx and "**/*.scala" in ctx)
    check("  does NOT name the root-anchored rule (nested path is outside it)",
          "rooted.md" not in ctx)
    check("  does NOT name the unscoped rule (it already loads at session start)",
          "unscoped.md" not in ctx)
    check("  points at AGENTS.md Code style", "AGENTS.md" in ctx)
    shutil.rmtree(root)

    # ---- ARM 2: root-anchored rule DOES reach a file at the root ----------
    # The other half of arm 1's anchoring claim. Without this, a matcher that
    # never matched `*.scala` at all would pass arm 1.
    root = build_tree()
    ctx = context(run(root, AT_ROOT)) or ""
    check("root-anchored `*.scala` reaches a file AT the root", "rooted.md" in ctx)
    check("  and the depth-reaching rule matches there too", "deep.md" in ctx)
    shutil.rmtree(root)

    # ---- ARM 3: SILENT when the file already exists -----------------------
    # The discriminator. An existing file was read before it could be written,
    # so its path-scoped rules were already delivered for it.
    root = build_tree()
    touch(root, NESTED)
    proc = run(root, NESTED)
    codes.append(proc.returncode)
    check("SILENT when the target already exists -- the discriminator",
          proc.returncode == 0 and context(proc) is None, f"exit={proc.returncode}")
    shutil.rmtree(root)

    # ---- ARM 4: SILENT for a path no glob matches -------------------------
    root = build_tree()
    proc = run(root, "docs/notes.md")
    codes.append(proc.returncode)
    check("SILENT for a new file no glob matches",
          proc.returncode == 0 and context(proc) is None, f"exit={proc.returncode}")
    shutil.rmtree(root)

    # ---- ARM 5: SILENT in a tree with no .claude/rules/ at all ------------
    root = build_tree(rules=False)
    proc = run(root, NESTED)
    codes.append(proc.returncode)
    check("SILENT in a clone with no .claude/rules/",
          proc.returncode == 0 and context(proc) is None, f"exit={proc.returncode}")
    shutil.rmtree(root)

    # ---- ARM 6: SILENT for a path outside the repository ------------------
    root = build_tree()
    outside = tempfile.mkdtemp()
    proc = run_payload({"hook_event_name": "PreToolUse", "tool_name": "Write",
                        "cwd": root,
                        "tool_input": {"file_path": os.path.join(outside, "X.scala"),
                                       "content": ""}})
    codes.append(proc.returncode)
    check("SILENT for a write outside the repository root",
          proc.returncode == 0 and context(proc) is None, f"exit={proc.returncode}")
    shutil.rmtree(outside)
    shutil.rmtree(root)

    # ---- ARM 7: the AGENTS.md pointer is conditional, not hardcoded -------
    root = build_tree(agents=False)
    ctx = context(run(root, NESTED)) or ""
    check("no AGENTS.md in the tree -> the pointer is omitted, rules still named",
          "deep.md" in ctx and "AGENTS.md" not in ctx)
    shutil.rmtree(root)

    # ---- ARM 8: root resolution works without CLAUDE_PROJECT_DIR ----------
    # The production fallback path. If this were broken the hook would resolve
    # some other root, find no rules, and go silently dead everywhere -- which
    # looks exactly like a repository with nothing to say.
    root = build_tree()
    ctx = context(run(root, NESTED, use_env=False)) or ""
    check("resolves the root from the payload cwd when the env var is unset",
          "deep.md" in ctx)
    shutil.rmtree(root)

    # ---- ARM 9: COULD NOT RUN -- stdin is not JSON ------------------------
    # Requirement 3. Silence-and-0 is this hook's CLEAN result, so a payload it
    # could not read must not produce silence-and-0 as well.
    proc = run_raw("this is not json")
    codes.append(proc.returncode)
    check("COULD NOT RUN: unparseable stdin -> exit 1 + stderr, never a silent 0",
          proc.returncode == 1 and proc.stderr.strip() != "" and not proc.stdout.strip(),
          f"exit={proc.returncode}")

    # ---- ARM 10: COULD NOT RUN -- no file_path ----------------------------
    proc = run_payload({"hook_event_name": "PreToolUse", "tool_name": "Write",
                        "tool_input": {"content": ""}})
    codes.append(proc.returncode)
    check("COULD NOT RUN: payload with no file_path -> exit 1",
          proc.returncode == 1 and proc.stderr.strip() != "",
          f"exit={proc.returncode}")

    # ---- ARM 11: NO ARM EVER EXITED 2 -------------------------------------
    # On PreToolUse exit 2 blocks the tool call. This is the invariant that
    # separates an advisory from a gate, asserted over every arm above rather
    # than argued for in a comment.
    check("no arm exited 2 -- this hook can never block a write",
          2 not in codes, f"codes={sorted(set(codes))}")

    # ---- ARM 12: MUTANT -- could-not-run made blocking --------------------
    # The plausible edit: "bash-guard.py exits 2 on a bad payload, match it."
    # It converts an advisory into a gate that stops writes on malformed input.
    mutant, applied = mutate(('    sys.stderr.write(f"rules-on-create hook could '
                              'not run: {reason}\\n")\n    return 1',
                              '    sys.stderr.write(f"rules-on-create hook could '
                              'not run: {reason}\\n")\n    return 2'))
    if not applied:
        check("MUTANT 1 applied (could-not-run exit code)", False, "anchor moved")
    else:
        mproc = run_raw("this is not json", hook=mutant)
        check("MUTANT 1 (could-not-run exits 2) is CAUGHT -> was 1, now blocks",
              mproc.returncode == 2, f"mutant exit={mproc.returncode}")
        discard(mutant)

    # ---- ARM 13: MUTANT -- the existence discriminator dropped ------------
    # The plausible edit: "advise on every write to a governed path."
    # It removes the only quiet state the hook has for a governed file.
    root = build_tree()
    touch(root, NESTED)
    mutant, applied = mutate(("    if os.path.exists(path):\n        return 0",
                              "    if False:\n        return 0"))
    if not applied:
        check("MUTANT 2 applied (existence check)", False, "anchor moved")
    else:
        check("MUTANT 2 (fires on existing files too) is CAUGHT -> arm 3 now fires",
              context(run(root, NESTED, hook=mutant)) is not None)
        discard(mutant)
    shutil.rmtree(root)

    # ---- ARM 14: MUTANT -- `*` allowed to cross a directory boundary ------
    # The plausible edit: "why is a single star [^/]* and not just .*".
    # It silently un-anchors every root-scoped rule.
    root = build_tree()
    mutant, applied = mutate(('            out.append("[^/]*")', '            out.append(".*")'))
    if not applied:
        check("MUTANT 3 applied (single-star class)", False, "anchor moved")
    else:
        mctx = context(run(root, NESTED, hook=mutant)) or ""
        check("MUTANT 3 (`*` crosses directories) is CAUGHT -> arm 1 now names rooted.md",
              "rooted.md" in mctx)
        discard(mutant)
    shutil.rmtree(root)

    # ---- ARM 15: MUTANT -- unscoped rules treated as governing everything --
    # The plausible edit: "a rule with no paths applies to all files."
    # It reports rules that already loaded, which is noise that reads as signal.
    root = build_tree()
    mutant, applied = mutate((
        "        for glob in parse_paths_frontmatter(os.path.join(rules_dir, name)):",
        "        for glob in (parse_paths_frontmatter(os.path.join(rules_dir, name))\n"
        "                     or [\"**\"]):"))
    if not applied:
        check("MUTANT 4 applied (unscoped rules)", False, "anchor moved")
    else:
        mctx = context(run(root, NESTED, hook=mutant)) or ""
        check("MUTANT 4 (unscoped rules reported) is CAUGHT -> arm 1 now names unscoped.md",
              "unscoped.md" in mctx)
        discard(mutant)
    shutil.rmtree(root)

    # ---- ARM 16: ADVERSARIAL -- two untrusted channels into the reminder --
    # Everything above is input a careless author produces. This hook reads two
    # things it did not write and puts both into a system reminder: a path from
    # the payload, and a RULE FILENAME from a directory a fork controls. A Bidi
    # override in either reverses how the finding block reads. The escape
    # sequence is used rather than the character, so this file is not itself
    # the attack it tests for.
    rlo = "\u202E"
    root = build_tree()
    with open(os.path.join(root, ".claude", "rules", f"x{rlo}y.md"), "w") as fh:
        fh.write(DEEP_RULE)
    ctx = context(run(root, f"src/a{rlo}b.scala")) or ""
    check("ADVERSARIAL: fires with a Bidi override in both path and rule name",
          bool(ctx))
    check("  the override is ESCAPED in both, not passed through",
          ctx.count("\\u202e") >= 2 and rlo not in ctx,
          f"escaped={ctx.count('\\u202e')}")
    shutil.rmtree(root)

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)})")
        for f in failures:
            print("  " + f)
        return 1
    print("RESULT: PASS — fires only on a file that does not exist yet, names")
    print("which rules govern it and which it deliberately leaves out, keeps")
    print("root-anchored and depth-reaching globs apart in both directions,")
    print("stays silent in a clone and outside the repository, resolves its root")
    print("without the env var, separates 'could not run' from 'clean' by exit")
    print("code, never exits 2, and catches all four seeded regressions.")
    print("No arm touched this repository.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
