#!/usr/bin/env python3
"""PreToolUse(Write) advisory: which path-scoped rules govern a file being created.

Reads a Claude Code PreToolUse payload as JSON on stdin. When the write creates
a file that does not exist yet, and some rule in .claude/rules/ declares a
`paths:` glob matching it, emits `hookSpecificOutput.additionalContext` naming
those rules. Otherwise silent. It NEVER blocks and never denies.

THE GAP THIS COVERS, WHICH WAS MEASURED RATHER THAN ASSUMED. A rules file
carrying `paths:` frontmatter loads when Claude READS a matching file. Probed on
this repository: reading a matching .scala file loaded the rule, reported with
`load_reason: path_glob_match`; WRITING a new matching .scala file loaded no
path-scoped rule at all. Creating a file is not reading one, so at the moment a
file's names, comments and idiom are first chosen, the three rules that govern
exactly those things have not been delivered by the mechanism that scopes them.

WHY IT IS NOT REDUNDANT WITH THE OTHER TWO THIRDS, AND WHERE IT IS THINNER THAN
IT LOOKS. The rules keep their `paths:` frontmatter and bind at review time, and
AGENTS.md carries the naming rule itself in text that loads every session. So
the CONTENT is already reachable; what this adds is delivery at one moment
neither reaches, and nothing more. That is a modest claim and is stated as one:
the vendor's own guidance is that instructions which never change belong in a
memory file rather than a hook, and the naming rule is exactly such an
instruction. This hook does not restate it. It names which files govern the path
being created, which is a fact about the current operation rather than a static
convention.

WHAT THE MESSAGE MUST NOT CLAIM. "These rules did not load" would be false
whenever an earlier read in the same session already loaded them, and this hook
cannot see session history. So it states the mechanism and the operation --
path-scoped rules load on read, and this operation creates rather than reads --
which is true regardless of what happened earlier in the session.

THE ROSTER IS DERIVED FROM DISK, NEVER LISTED HERE. Naming the three current
.scala rules in this file would be a live count baked into an artifact: it goes
stale the day a rule is added, removed, or rescoped, and silently, because
nothing re-reads a hook looking for a roster. The globs are read from the rules'
own frontmatter at fire time, so the hook is correct by construction and is not
specific to Scala -- a rule scoped to any other path is covered the day it
lands, with no edit here.

ADVISORY, NOT A GATE, AND THE EXIT CODES SAY SO. Sibling hook bash-guard.py
exits 2 to BLOCK. On PreToolUse exit 2 blocks the tool call outright, so this
hook must never use it: a broken advisory that stops writes is far worse than
the gap it covers. Could-not-run therefore exits 1, which the vendor documents
as "the action proceeds", surfacing in the transcript as a `<hook name> hook
error` notice with the first line of stderr. That is the whole reason a
non-blocking failure channel exists, and why silence is never used for it:
silence is this hook's ordinary result, and a payload it could not read must not
be indistinguishable from a path nothing governs.

WHY THIS EVENT AND NOT ITS NEIGHBOR. PostToolUse is the wrong event for a
reason that is structural rather than stylistic: by the time it fires the file
EXISTS, so the one fact that discriminates a creation from an ordinary
overwrite is no longer observable. PreToolUse is the only tool event that can
still see the absence.

WHY IT DOES NOT CHECK THE TOOL NAME. The invariant is "this operation brings a
file into existence", not "this tool is Write". The existence test expresses
that directly, and expresses it for any future tool that creates a file, while a
tool-name test would go silently dead the day one arrives. Registration narrows
the event; the code checks the property.

WHAT THE GLOB MATCHER IS, AND WHERE IT IS APPROXIMATE. `**/`, `**`, `*` and `?`
are translated to a regex anchored at the repository root, matching Claude
Code's documented behavior that an unprefixed pattern anchors at the project
root and `**/` matches a directory at any depth within it. Character classes and
brace groups are treated as literal text rather than as metacharacters. That is
a deliberate simplification with a stated direction: such a glob under-matches,
so the hook stays quiet rather than firing wrongly. No rule in this repository
uses either form.

SEAM. Nothing is written, nothing outside the repository is read, and a clone
missing .claude/rules/ produces silence rather than a complaint.
"""

import json
import os
import re
import subprocess
import sys

# Python puts this script's own directory on sys.path, so a sibling module
# imports by name. A missing module raises and exits non-zero, which on
# PreToolUse lets the action proceed with a visible error -- the right failure.
# A try/except fallback would be worse than the crash: it would ship
# unsanitized text into a system reminder.
from lib_harness_text import sanitize

RULES_REL = os.path.join(".claude", "rules")
AGENTS_REL = "AGENTS.md"
MAX_RULES = 12


def project_root(payload):
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


def relative_to_root(path, root):
    """The path as repo-relative POSIX, or None when it is outside the repo.

    Outside is not an error: a write to /tmp is legitimate and no rule in this
    repository governs it. It is the same silence as a path nothing matches.
    """
    try:
        real_root = os.path.realpath(root)
        real_path = os.path.realpath(path)
    except OSError:
        return None
    prefix = real_root + os.sep
    if not real_path.startswith(prefix):
        return None
    return real_path[len(prefix):].replace(os.sep, "/")


def glob_to_regex(glob):
    """Translate one `paths:` glob into an anchored regex.

    `**/` is the only form that may cross a directory boundary in the middle of
    a pattern, which is what keeps `*.scala` root-anchored while `**/*.scala`
    reaches any depth. Everything not a metacharacter is escaped, so a bracket
    or brace matches itself and the pattern under-matches rather than throwing.
    """
    out = []
    i, n = 0, len(glob)
    while i < n:
        if glob.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif glob.startswith("**", i):
            out.append(".*")
            i += 2
        elif glob[i] == "*":
            out.append("[^/]*")
            i += 1
        elif glob[i] == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(glob[i]))
            i += 1
    return re.compile("".join(out) + r"\Z")


def parse_paths_frontmatter(path):
    """The `paths:` globs of a rules file, or [] when it is unscoped.

    Line-based on purpose: no YAML dependency, and the only shape that matters
    is a `paths:` key inside leading `---` fences followed by `- "glob"` items.
    An unscoped rule returns [] and is correctly never reported -- it already
    loads at session start, so it has no gap to cover.
    """
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError:
        return []
    if not lines or lines[0].strip() != "---":
        return []
    globs, in_paths = [], False
    for line in lines[1:]:
        if line.strip() == "---":
            break
        if line.startswith("paths:"):
            in_paths = True
            continue
        if in_paths:
            stripped = line.strip()
            if stripped.startswith("- "):
                globs.append(stripped[2:].strip().strip('"').strip("'"))
                continue
            if stripped and not line.startswith((" ", "\t")):
                in_paths = False
    return globs


def governing_rules(root, rel_path):
    """(rule path, matching glob) for every scoped rule this path falls under."""
    rules_dir = os.path.join(root, RULES_REL)
    if not os.path.isdir(rules_dir):
        return []
    found = []
    for name in sorted(os.listdir(rules_dir)):
        if not name.endswith(".md"):
            continue
        for glob in parse_paths_frontmatter(os.path.join(rules_dir, name)):
            if glob_to_regex(glob).match(rel_path):
                found.append((f"{RULES_REL.replace(os.sep, '/')}/{name}", glob))
                break
    return found


def message(root, rel_path, rules):
    """Factual statements about this operation, not a restatement of any rule.

    The vendor's guidance for additionalContext: text framed as out-of-band
    system instructions can trigger Claude's prompt-injection defenses and be
    surfaced to the operator instead of read as context. So this says what the
    loading mechanism is, what this operation does, and which files apply --
    and leaves every instruction to the files it names.
    """
    # Every interpolated value below is text this hook did not author -- a path
    # from the payload, and a filename and glob read from a tracked rules file
    # a fork controls. All three go into a system reminder, so all three are
    # escaped. See lib_harness_text.
    out = [
        f"This operation creates {sanitize(rel_path, 300)}, which does not "
        "exist yet. Rules under .claude/rules/ that carry `paths:` frontmatter "
        "load when Claude reads a file matching their globs, so creating a "
        "file is not an operation that loads them.",
        "",
        "These rules declare a glob matching that path:",
    ]
    for rule, glob in rules[:MAX_RULES]:
        out.append(f"  - {sanitize(rule, 200)}  ({sanitize(glob, 200)})")
    if len(rules) > MAX_RULES:
        out.append(f"  ... and {len(rules) - MAX_RULES} more")
    if os.path.isfile(os.path.join(root, AGENTS_REL)):
        out.append("")
        out.append(
            "AGENTS.md, under Code style, states the naming rule that binds "
            "before any file exists, and loads every session."
        )
    return "\n".join(out)


def could_not_run(reason):
    """Exit 1: the vendor documents any code other than 0 or 2 as letting the
    action proceed. Exit 2 would BLOCK the write, which an advisory must never
    do, and exit 0 would be indistinguishable from having nothing to say."""
    sys.stderr.write(f"rules-on-create hook could not run: {reason}\n")
    return 1


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

    root = project_root(payload)
    if not os.path.isabs(path):
        path = os.path.join(root, path)

    # The whole discriminator. An existing file was read before it could be
    # written, so its path-scoped rules have already been delivered for it.
    if os.path.exists(path):
        return 0

    rel_path = relative_to_root(path, root)
    if rel_path is None:
        return 0

    rules = governing_rules(root, rel_path)
    if not rules:
        return 0

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": message(root, rel_path, rules),
        }
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
