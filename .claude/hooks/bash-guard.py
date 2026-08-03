#!/usr/bin/env python3
"""PreToolUse(Bash) guard for fukuii-cli.

Reads a Claude Code PreToolUse hook payload as JSON on stdin. Exits 2 to block
the tool call, with the reason on stderr; exits 0 to allow.

WHAT THIS IS. A speed bump, not a boundary. It pattern-matches command TEXT, so
it stops an absent-minded inline loop and stops nothing that phrases itself
differently. `~/.claude/protocols/shell-environment.md` states the general form:
"Read/Edit denies are enforcement; Bash(...) denies are a speed bump." A hook
inspecting a command string shares that weakness. Known gaps are listed at the
foot of this file rather than papered over.

WHY shlex AND NOT A REGEX. A regex over raw command text cannot tell a loop from
the word "for" inside a quoted argument, and `compound-command-scratch.md`'s own
Scope section excludes exactly those cases:

    - simple pipelines with no control flow
    - a single conditional guard in an already-scripted file
    - established commands that happen to contain a keyword in a quoted argument

A guard that fires on `git commit -m "fix the while loop"` gets switched off by
the people it is meant to help. shlex yields real shell tokens, so a keyword is
only a violation when it lands in COMMAND POSITION.

MECHANISM, stated precisely because the obvious guess is wrong. With
`whitespace_split=True` shlex does NOT strip quote characters -- it returns
`'"fix: while loop"'` with the quotes still attached. That is measured, not
assumed. It is why a quoted keyword is safe: the token never compares equal to
the bare keyword. The first draft of this file claimed shlex stripped quotes and
happened to pass its tests anyway, which is the more dangerous kind of wrong --
right behaviour resting on a false explanation, so the next person to edit it
reasons from the false one. The consequence is real and is handled below:
unwrapping `bash -c "..."` requires stripping the quotes by hand.

FAILURE DIRECTION IS DELIBERATE. On a parse error the guard ALLOWS. A speed bump
whose failure mode blocks legitimate work gets disabled, which costs more than
the gap it closes. A boundary would choose the opposite; this is not one, and the
docstring says so rather than letting the code imply otherwise.
"""

import json
import shlex
import sys

# Control-flow words that mean a compound command when they open one.
CONTROL_KEYWORDS = frozenset({"for", "while", "until", "if", "case", "select"})

# After any of these, the next word is a fresh command position.
#
# "\n" is deliberately ABSENT. shlex never emits it -- shlex.split('a\nb') is
# ['a', 'b'] -- so listing it here was dead config that read as coverage while
# a loop on line 2 went straight through. Newlines are handled by scanning line
# by line in find_inline_control_flow instead.
SEPARATORS = frozenset({";", "&&", "||", "|", "&", "|&", ";;", "(", ")"})
KEYWORD_SEPARATORS = frozenset({"do", "then", "else", "elif"})

# Wrappers whose -c argument is itself a command and must be scanned.
SHELL_WRAPPERS = frozenset({"bash", "sh", "zsh", "dash", "ksh"})

SCRATCH_HINT = (
    "Write it once to .local/scratch/<slug>.sh and run `bash .local/scratch/<slug>.sh`."
)


def unquote(token):
    """Strip one layer of matched surrounding quotes.

    Needed because shlex in whitespace_split mode preserves them (see module
    docstring). Without this, unwrapping `bash -c "..."` silently does nothing --
    which is exactly what the calibration suite caught on the first run.
    """
    if len(token) >= 2 and token[0] == token[-1] and token[0] in ("'", '"'):
        return token[1:-1]
    return token


def tokenize(command):
    """Shell-aware tokens, or None if the string cannot be parsed."""
    try:
        lexer = shlex.shlex(command, punctuation_chars=True)
        lexer.whitespace_split = True
        return list(lexer)
    except ValueError:
        return None


def heredoc_delimiter(line):
    """The heredoc delimiter opened on this line, or None.

    `<<<` is a herestring, not a heredoc: it has no body, so there is nothing to
    skip and returning None keeps the following lines guarded.
    """
    idx = line.find("<<")
    if idx == -1:
        return None
    rest = line[idx + 2:]
    if rest.startswith("<"):
        return None  # herestring
    rest = rest.lstrip("-").strip()
    if not rest:
        return None
    return rest.split()[0].strip("'\"")


def scan_line(line, depth):
    """Return the offending keyword on ONE line, or None."""
    tokens = tokenize(line)
    if tokens is None:
        return None  # unparseable -> allow, per the failure direction above

    prev = None
    for i, tok in enumerate(tokens):
        command_position = (
            prev is None or prev in SEPARATORS or prev in KEYWORD_SEPARATORS
        )

        if command_position and tok in CONTROL_KEYWORDS:
            return tok

        # `bash -c "for i in ...; do ...; done"` hides the loop one level down.
        if (
            command_position
            and tok in SHELL_WRAPPERS
            and depth < 2
            and i + 2 < len(tokens)
            and tokens[i + 1] == "-c"
        ):
            nested = find_inline_control_flow(unquote(tokens[i + 2]), depth + 1)
            if nested:
                return nested

        prev = tok
    return None


def find_inline_control_flow(command, depth=0):
    """Return the offending keyword, or None.

    Scans LINE BY LINE, and that is the whole point rather than an implementation
    detail. shlex never emits a newline token, so a single-pass scan only ever
    sees the FIRST line's opening word in command position. Measured, not
    theorised: this guard blocked a one-line `for` loop and allowed the identical
    loop placed after an `echo` -- and a multi-line command is the shape the Bash
    tool most often carries, so the miss was the common case, not an edge one.

    Heredoc bodies are DATA and are skipped to their delimiter. That is not an
    edge case to tolerate, it is the protocol's own prescribed remedy: `cat > f
    <<'EOF' ... for x in ... EOF` writes the loop into a scratch file and must
    not be blocked by the guard that demands it. Skipping to the delimiter rather
    than abandoning the scan is deliberate -- an earlier `break` here stopped the
    entire scan, leaving everything after the first `<<` unguarded.
    """
    pending_delimiter = None

    for line in command.split("\n"):
        if pending_delimiter is not None:
            if line.strip() == pending_delimiter:
                pending_delimiter = None
            continue  # inside a heredoc body: data, not commands

        found = scan_line(line, depth)
        if found:
            return found

        pending_delimiter = heredoc_delimiter(line)

    return None


def find_check_ignore_dash_v_as_decision(command):
    """`git check-ignore -v` whose exit code drives a decision.

    -v exits 0 when ANY pattern matches, INCLUDING a negation, so the very rule
    that un-ignores a file reads as proof that it is ignored. That is not a style
    nit: it produced a false security finding on this machine.

    Narrow deliberately. Displaying which line matched is legitimate and
    documented, so a bare `git check-ignore -v <path>` is ALLOWED. Only the
    decision form -- exit code consumed by && or || -- is blocked.
    """
    tokens = tokenize(command)
    if tokens is None:
        return False

    for i, tok in enumerate(tokens):
        if tok != "check-ignore":
            continue
        # Walk back past git's own global options to find the `git` itself.
        # Requiring tokens[i-1] == "git" missed `git -C . check-ignore -v`,
        # which is the form this repo's own tooling uses -- so the bypass was
        # a shape already modelled next door, not an exotic one.
        j = i - 1
        while j >= 0 and tokens[j] != "git" and tokens[j] not in SEPARATORS:
            j -= 1
        if j < 0 or tokens[j] != "git":
            continue
        # Walk this command's own tokens up to the next separator.
        has_v = False
        drives_decision = False
        for nxt in tokens[i + 1:]:
            if nxt in ("&&", "||"):
                drives_decision = True
                break
            if nxt in (";", "|", "\n"):
                break
            if nxt.startswith("-") and not nxt.startswith("--") and "v" in nxt[1:]:
                has_v = True
            elif nxt == "--verbose":
                has_v = True
        if has_v and drives_decision:
            return True
    return False


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0  # not a payload we understand -> allow

    if payload.get("tool_name") != "Bash":
        return 0

    command = payload.get("tool_input", {}).get("command", "")
    if not isinstance(command, str) or not command.strip():
        return 0

    keyword = find_inline_control_flow(command)
    if keyword:
        sys.stderr.write(
            "BLOCKED by .claude/hooks/bash-guard.py\n"
            f"Inline shell control flow: `{keyword}` in command position.\n\n"
            f"{SCRATCH_HINT}\n\n"
            "This is a permission-system constraint, not style: the Bash allow-list "
            "matches command prefixes and cannot pre-approve bare shell keywords, so "
            "an inline loop prompts every time while `bash .local/scratch/x.sh` is one "
            "stable allow-listable shape. This shell is also zsh, where an unquoted "
            "$VAR does NOT word-split -- the same loop in a scratch file runs under "
            "bash and behaves as written.\n"
        )
        return 2

    if find_check_ignore_dash_v_as_decision(command):
        sys.stderr.write(
            "BLOCKED by .claude/hooks/bash-guard.py\n"
            "`git check-ignore -v` used as a decision.\n\n"
            "Use -q for the decision, -v only to DISPLAY which line matched:\n"
            "    git check-ignore --no-index -q -- <path>   # exit 0 = ignored\n\n"
            "With -v, git exits 0 when any pattern matches INCLUDING a negation, so a "
            "!.env.example carve-out reads as proof the file is ignored. This produced "
            "a false security finding on this machine.\n"
        )
        return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())

# KNOWN GAPS -- stated, not hidden. Each is a spelling this guard does not match:
#   * `eval "for i in ...; do ...; done"`  -- the loop is a string until eval runs
#   * `xargs -I{} sh -c '...'`             -- only `bash|sh|zsh -c` is unwrapped
#   * a loop written into a file by other means, then executed
#   * anything at nesting depth > 2
# The space of spellings is unbounded and each added pattern widens the false
# positives that train people to work around the guard. This catches the
# accidental case, which is the case that actually occurs.
