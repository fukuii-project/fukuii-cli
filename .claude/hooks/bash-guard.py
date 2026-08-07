#!/usr/bin/env python3
"""PreToolUse(Bash) guard for fukuii-cli.

Reads a Claude Code PreToolUse hook payload as JSON on stdin. Exits 2 to block
the tool call, with the reason on stderr; exits 0 to allow.

WHAT THIS IS. A speed bump, not a boundary. It pattern-matches command TEXT, so
it stops an absent-minded inline loop and stops nothing that phrases itself
differently. The general form: Read/Edit denies are enforcement, because they
gate a tool that names its target; Bash(...) denies are a speed bump, because
they pattern-match a string. A hook inspecting a command string shares that
weakness. Known gaps are listed at the foot of this file rather than papered
over.

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
right behavior resting on a false explanation, so the next person to edit it
reasons from the false one. The consequence is real and is handled below:
unwrapping `bash -c "..."` requires stripping the quotes by hand.

FAILURE DIRECTION IS DELIBERATE. On a parse error the guard ALLOWS. A speed bump
whose failure mode blocks legitimate work gets disabled, which costs more than
the gap it closes. A boundary would choose the opposite; this is not one, and the
docstring says so rather than letting the code imply otherwise.

THE THIRD GATE: SCRIPT CONTENTS, AND WHY IT IS A DIFFERENT CHECK
================================================================
`.claude/settings.json` pre-approves `Bash(bash .local/scratch/*)`. The
permission system matches the COMMAND STRING; a script's CONTENTS are never
inspected. So one pre-approved invocation carries arbitrary content past every
observer: the two `**/*.scala`-scoped hooks stay silent on a `.sh` file, the two
gates above see only a command line with nothing in it, and `.local/` is
gitignored so the body reaches no diff and no review. Anthropic documents that
`curl` and `wget` "are not auto-approved by default" and prompt like any other
non-read-only Bash command -- inside a pre-approved script that prompt never
fires, so a documented control is silently bypassed.

THE CONTENTS CHECK IS NOT THE COMMAND-LINE CHECK, AND MUST NOT BE. The obvious
repair -- run the scan above over the file's contents -- is wrong and would
break this repository. The scan above detects INLINE SHELL CONTROL FLOW, which
is exactly what a scratch script is supposed to contain: moving control flow
into a file is the entire point of the pattern, and the error message below
instructs people to do it. Applied to contents it would block every legitimate
scratch script, including the ones this guard itself demands. So the question is
not "does this contain control flow" but "does this contain something the
permission system would otherwise have prompted for, which a pre-approved
wrapper now suppresses."

WHAT IT LOOKS FOR, AND WHY THE NARROW SET. Measured against the 458 real scratch
scripts in this repository on 2026-08-07, because the false-positive direction is
the expensive one: a gate that fires on ordinary work gets switched off, and a
switched-off gate is worse than none.

    detector                              fires on
    FETCH-EXEC     fetcher piped into an interpreter that takes its
                   program from STDIN                                    0/458
    DECODE-EXEC    decoder piped into the same                           0/458
    SUBST-EXEC     a substituted fetch consumed AS a program by
                   eval / source / . / an interpreter                    0/458
    REMOTE-CHANNEL ssh, sftp, ftp, tftp, telnet, nc, ncat, netcat,
                   socat; scp and rsync only with a remote-looking
                   argument                                              0/458
    SECRET-UPLOAD  an upload flag whose source matches a key-material
                   path                                                  0/458

WHAT IS DELIBERATELY NOT BLOCKED, WITH THE MEASUREMENT THAT DECIDED IT. An
INBOUND fetch -- `curl -o file url`, `wget url` -- is allowed. Blocking it was
the first design and it is not viable here: `curl` appears in 84 of 458 scratch
scripts (18.4%), and `.claude/rules/scala-dependency-admissibility.md` REQUIRES
it, having measured that Maven Central returns 403 to WebFetch and 200 to
`curl`. A gate contradicting a tracked rule is a gate that gets disabled.
Two further exclusions come from real hits, not from theory:

  * `curl ... | python3 -c "<literal program>"` PARSES fetched JSON; the program
    is the `-c` string and the fetched bytes are its input. `curl ... | bash`
    EXECUTES fetched bytes, because a shell with no program argument reads its
    program from stdin. That distinction is the discriminator, and without it
    the check fires on routine JSON parsing.
  * `curl -X POST -d @query.json https://api.osv.dev/v1/querybatch` queries the
    OSV vulnerability database, in 5 of 458 scripts. Generic outbound-data flags
    are therefore NOT flagged; only an upload whose SOURCE is key-material is.

Also excluded, each for its own reason: destructive operations (`rm` is
routine); git network operations (they reach a configured remote, and pushing is
already a confirmation boundary); package and build fetches (`sbt` alone is in
54 scripts and fetches by design); credential READS (the deny list in
`settings.json` is the layer for that, and the house `secret-path-guard.sh` is
deliberately unwired for the same reason a command string cannot see through
shell expansion); and the command line itself, where the permission system
already sees the command and prompts -- double-gating there would fire on every
`curl` the operator legitimately approves.

BLOCK, NOT ADVISE, AND THE VENDOR FACT THAT DECIDED IT. A PreToolUse hook has
more than two options: `hookSpecificOutput.permissionDecision` accepts
"allow"/"deny"/"ask"/"defer", and the reference says of "ask" that it "prompts
the user to confirm" (raw fetch of the hooks reference, 2026-08-07). Since the
gap IS a suppressed prompt, "ask" is the closer semantic match and was
considered first. Exit 2 was chosen anyway, on three documented grounds:

  1. Only exit 2 has a stated precedence over an ALLOW rule, which is exactly
     the situation here. The permissions reference: "A blocking hook also takes
     precedence over allow rules. A hook that exits with code 2 stops the tool
     call before permission rules are evaluated, so the block applies even when
     an allow rule would otherwise let the call proceed." No equivalent
     statement exists for a hook's "ask" versus an allow rule -- the documented
     guarantees for "ask" concern auto mode's classifier and hook-vs-hook
     precedence. Choosing the undocumented one risks a SILENT no-op, which is
     this environment's most common defect and the worst available outcome.
  2. The same page recommends this exact shape: "To run all Bash commands
     without prompts except for a few you want blocked, add "Bash" to your allow
     list and register a PreToolUse hook that rejects those specific commands."
  3. `permissionDecisionReason` for "ask" is "shown to the user but not Claude",
     so the agent would learn nothing and could not self-correct. On exit 2
     stderr is fed back to Claude, which is how the two gates above already
     teach the correct form.

Revisit if the vendor documents "ask" as overriding an allow rule.

THE MESSAGE QUOTES NOTHING FROM THE FILE. On exit 2 stderr is fed back to Claude,
which makes it a context channel, and the scanned file is not this hook's own
text. So a finding reports the detector, the line NUMBER, and command names drawn
from this file's own frozensets -- never a snippet. That removes the injection
surface rather than escaping it, and needs no dependency on `lib_harness_text`,
whose absence would make this gate fail open.

WHAT THIS DOES NOT REACH is listed under KNOWN GAPS at the foot of the file. It
raises the cost of the accidental and the obvious path. It is not a sandbox, and
a clean run is not a guarantee.
"""

import json
import os
import re
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

# Wrappers whose -c argument is itself a command and must be scanned. Also the
# set whose FILE operand is a script this guard reads and scans; see
# collect_shell_targets. Deliberately shells only -- the scanner below is a shell
# tokenizer, so pointing it at a .py or .js file would apply the wrong grammar.
SHELL_WRAPPERS = frozenset({"bash", "sh", "zsh", "dash", "ksh"})

SCRATCH_HINT = (
    "Write it once to .local/scratch/<slug>.sh and run `bash .local/scratch/<slug>.sh`."
)

# ---------------------------------------------------------------------------
# Vocabulary for the contents check. Every name here is one this file authored,
# which is what lets a finding name it without quoting the scanned file.
# ---------------------------------------------------------------------------

FETCHERS = frozenset({"curl", "wget", "aria2c", "xh", "httpie"})

# Decoders whose output being executed is the obfuscated form of the same thing.
DECODERS = frozenset({"base64", "xxd", "uudecode"})

# interpreter -> the flags that supply its program from somewhere OTHER than
# stdin. An interpreter with none of these and no file operand reads its PROGRAM
# from standard input, which is what makes `curl ... | bash` execution and
# `curl ... | python3 -c "..."` merely parsing.
PROGRAM_FLAGS = {
    "bash": ("-c",), "sh": ("-c",), "zsh": ("-c",), "dash": ("-c",), "ksh": ("-c",),
    "python": ("-c", "-m"), "python2": ("-c", "-m"), "python3": ("-c", "-m"),
    "node": ("-e", "-p", "--eval", "--print"),
    "deno": ("-e", "--eval"), "bun": ("-e", "--eval"),
    "perl": ("-e", "-E"), "ruby": ("-e",), "php": ("-r",),
}
INTERPRETERS = frozenset(PROGRAM_FLAGS)

# Consumers that treat their argument as a PROGRAM rather than as data.
PROGRAM_CONSUMERS = frozenset({"eval", "source", "."}) | INTERPRETERS

# Reaching a network peer is the whole purpose of these, so a name alone decides.
ALWAYS_REMOTE = frozenset({
    "ssh", "sftp", "ftp", "tftp", "telnet", "nc", "ncat", "netcat", "socat",
})
# These two are ordinary LOCAL copy tools until an argument names a host, so the
# name alone must not decide -- `rsync -a src/ dst/` is not egress.
REMOTE_IF_HOST = frozenset({"scp", "rsync"})

UPLOAD_FLAGS = frozenset({
    "-d", "--data", "--data-raw", "--data-binary", "--data-urlencode",
    "--data-ascii", "-F", "--form", "--form-string", "-T", "--upload-file",
    "--json", "--post-file", "--post-data", "--body-file", "--body-data",
})
# Bundled short flags: `-sSd @file`. Case matters -- lowercase d is --data and
# uppercase D is --dump-header, which is not an upload.
UPLOAD_SHORT_CHARS = ("d", "F", "T")

SECRET_PATH = re.compile(
    r"(^|/|\.)("
    r"env(\.[A-Za-z0-9_-]+)?|"
    r"[^/]*\.(pem|key|keystore|jks|p12|pfx|nodekey)|"
    r"id_(rsa|dsa|ecdsa|ed25519)|"
    r"credentials\.json|service-account\.json|wallet\.json|mnemonic\.txt|"
    r"npmrc|netrc|htpasswd"
    r")$",
    re.IGNORECASE,
)

# `user@host:path`, `host.domain:path`, or any scheme://
REMOTE_ARG = re.compile(
    r"^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:|^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)+:|://"
)

# A substitution whose ENTIRE value is a fetch. Anchored deliberately: an
# unanchored form also matches `bash -c "echo $(curl x)"`, where the fetched
# bytes become arguments to echo rather than a program.
WHOLE_SUBST_FETCH = re.compile(
    r"^[$<]\(\s*(" + "|".join(sorted(FETCHERS)) + r")\b.*\)$", re.S
)

# A token carrying any of these cannot be resolved to a path by reading it.
NON_LITERAL = ("$", "`", "*", "?")

# ~70x the largest script in this repository (14,597 bytes, measured 2026-08-07).
MAX_SCRIPT_BYTES = 1024 * 1024


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


def quoted_region_end(lines, start):
    """Index of the line that closes a quote opened at `start`, or None.

    `lines[start]` did not tokenize. If some later line makes the accumulated
    text tokenize again, the run was one quoted string spanning lines -- an
    embedded program, a multi-line message -- and its body is data. If nothing
    closes it, the input is malformed and None keeps every later line guarded.
    """
    for j in range(start + 1, len(lines)):
        if tokenize("\n".join(lines[start:j + 1])) is not None:
            return j
    return None


def heredoc_delimiter(line):
    """The heredoc delimiter opened on this line, or None.

    Decided from TOKENS, never from a raw substring search, and that distinction
    is the whole function. An earlier version ran `line.find("<<")`, so any `<<`
    anywhere on the line opened a heredoc that never closed -- a shift operator
    in `python3 -c "print(1 << 3)"`, a git format string, or the words of a
    commit message. `pending_delimiter` was then set to a token that appears on
    no later line, and every subsequent line was skipped as heredoc body.

    Measured, three cases, all previously ALLOWED with a loop on the next line:
        python3 -c "print(1 << 3)"
        git log --format="%h << %s"
        git commit -m "a << b"

    That is exactly the hole this function's own comment claimed to have closed:
    a FALSE heredoc has no delimiter line, so its effect was identical to the
    `break` it replaced. The narrowing was real and the claim of closure was not.

    `<<<` is a herestring, not a heredoc: no body, nothing to skip, so returning
    None keeps the following lines guarded.
    """
    tokens = tokenize(line)
    if not tokens:
        # Unparseable or empty. Return None rather than guessing: scan_line
        # already ALLOWS an unparseable line, and claiming a heredoc here would
        # extend that allowance to every line after it.
        return None

    for i, tok in enumerate(tokens):
        if tok == "<<<" or not tok.startswith("<<"):
            continue
        # `<<EOF` / `<<-EOF` carry the delimiter; a bare `<<` takes the next
        # token. The `-` can land on EITHER side of the token split -- shlex
        # tokenizes `<<-'END'` as `['<<', "-'END'"]`, so stripping it only from
        # the operator token yielded a delimiter of `-'END` and the real `END`
        # line never matched. Normalize after choosing the source, not before.
        rest = tok[2:]
        if not rest.lstrip("-") and i + 1 < len(tokens):
            rest = tokens[i + 1]
        rest = rest.lstrip("-").strip("'\"")
        return rest or None

    return None


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


def walk_command_lines(text, visit):
    """Call `visit(line, lineno)` on each line that is real command text.

    Returns the first truthy result, or None. ONE implementation, because both
    scanners need identical heredoc and quoted-region handling and two copies of
    this logic would drift -- the same reasoning that made `lib_harness_text` a
    module rather than a helper per hook.

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
    lines = text.split("\n")
    pending_delimiter = None
    skip_until = -1  # last line index belonging to a multi-line quoted string

    for i, line in enumerate(lines):
        if i <= skip_until:
            continue  # inside a quoted string that spans lines: data, not commands

        if pending_delimiter is not None:
            if line.strip() == pending_delimiter:
                pending_delimiter = None
            continue  # inside a heredoc body: data, not commands

        # A line that does not tokenize may be opening a quote that closes on a
        # LATER line -- `python3 -c "` followed by a Python loop is the shape
        # that matters, and splitting on newlines turns that program's body into
        # apparent shell commands. Scanning it produced a false block on
        # `python3 -c "\nfor i in range(3): ...\n"`, found by review 2026-08-03.
        #
        # The look-ahead is what keeps this from failing OPEN. Only skip lines
        # when the quote demonstrably closes; an unparseable line whose quote
        # never closes is malformed, and its following lines stay guarded. Same
        # discipline as the heredoc delimiter below, and for the same reason: a
        # region that never ends is not a region.
        if tokenize(line) is None:
            close = quoted_region_end(lines, i)
            if close is not None:
                skip_until = close
                continue

        found = visit(line, i + 1)
        if found:
            return found

        candidate = heredoc_delimiter(line)
        # A heredoc whose delimiter never appears on a later line is not a
        # heredoc. This is the test, rather than another guess at what `<<`
        # meant, because token analysis alone cannot decide it: shlex genuinely
        # emits `<<` as a standalone token in `git log --format="%h << %s"`,
        # since the quote opens mid-word and never closes the token. Requiring
        # the closing delimiter to actually exist rules that out by construction
        # -- and rules out every other false opener, including ones nobody has
        # thought of yet, which a per-shape heuristic cannot.
        #
        # Failure direction: a genuine heredoc whose delimiter is missing (a
        # truncated command) gets its body SCANNED rather than skipped. That is
        # the safe direction -- it can only over-block malformed input, never
        # under-guard well-formed input.
        if candidate and any(later.strip() == candidate for later in lines[i + 1:]):
            pending_delimiter = candidate

    return None


def find_inline_control_flow(command, depth=0):
    """Return the offending control-flow keyword, or None."""
    return walk_command_lines(command, lambda line, _lineno: scan_line(line, depth))


# ---------------------------------------------------------------------------
# The contents check. Everything below inspects a FILE a command would execute,
# never the command line itself -- see the module docstring for why the two are
# different checks and why running the control-flow scan over contents would
# block every legitimate scratch script.
# ---------------------------------------------------------------------------


def command_positions(tokens):
    """[(index, token)] for every token that OPENS a command.

    Same rule the control-flow scanner uses: a token is in command position when
    it is first or follows a separator. Sharing the rule is what keeps a keyword
    in a quoted argument from being read as a command in either scanner.
    """
    out = []
    prev = None
    for i, tok in enumerate(tokens):
        if prev is None or prev in SEPARATORS or prev in KEYWORD_SEPARATORS:
            out.append((i, tok))
        prev = tok
    return out


def args_until_separator(tokens, start):
    """The tokens belonging to ONE command, stopping at the next separator."""
    out = []
    for tok in tokens[start:]:
        if tok in SEPARATORS:
            break
        out.append(tok)
    return out


def reads_program_from_stdin(tokens, idx):
    """True when the interpreter at `idx` takes its PROGRAM from standard input.

    This is the discriminator the whole check rests on. `curl ... | bash`
    EXECUTES fetched bytes, because a shell given no program argument reads its
    program from stdin. `curl ... | python3 -c "<program>"` does NOT: the program
    is the -c string and the fetched bytes are merely its input. Without this
    distinction the check fires on routine JSON parsing, which is how it was
    found -- one real scratch script in this repository does exactly that.
    """
    flags = PROGRAM_FLAGS.get(tokens[idx], ())
    for arg in args_until_separator(tokens, idx + 1):
        if arg in flags or (arg.startswith("--") and arg.split("=", 1)[0] in flags):
            return False            # the program is supplied inline
        if arg in ("-", "-s", "--"):
            continue                # explicit stdin markers, and end-of-options
        if arg.startswith("-"):
            continue                # an ordinary option
        return False                # a script FILE operand supplies the program
    return True


def upload_source_is_secret(tokens, idx):
    """True when a fetcher at `idx` uploads something key-material shaped.

    Narrow on purpose. Flagging outbound data generally fired on 5 of this
    repository's 458 scratch scripts, every one of them a legitimate
    `curl -X POST -d @query.json https://api.osv.dev/...` CVE lookup. What those
    have in common is that the uploaded file is a generated query; what an
    exfiltration has instead is a source that is a key, a keystore, or a .env.
    """
    args = args_until_separator(tokens, idx + 1)
    for j, arg in enumerate(args):
        following = args[j + 1] if j + 1 < len(args) else ""
        if arg.startswith("--") and "=" in arg:
            if arg.split("=", 1)[0] not in UPLOAD_FLAGS:
                continue
            value = arg.split("=", 1)[1]
        elif arg in UPLOAD_FLAGS:
            value = following
        elif (arg.startswith("-") and not arg.startswith("--") and len(arg) > 1
              and any(c in arg[1:] for c in UPLOAD_SHORT_CHARS)):
            value = following       # a bundled short flag such as -sSd
        else:
            continue
        if SECRET_PATH.search(unquote(value).lstrip("@")):
            return True
    return False


def find_substituted_fetch(tokens, cmds):
    """(label, detail) when a fetch is consumed AS a program, else None.

    Two spellings, because shlex treats them differently and only measuring
    showed it. Quoted, the whole substitution survives as ONE token:
    `eval "$(curl ...)"`. Unquoted, it is split: `source <(curl ...)` tokenizes
    as ['source', '<(', 'curl', ...] and `eval $(curl ...)` as
    ['eval', '$', '(', 'curl', ...]. The first spelling alone left
    `source <(curl ...)` undetected, which the calibration caught.
    """
    for i, tok in cmds:
        if tok not in PROGRAM_CONSUMERS:
            continue
        for arg in args_until_separator(tokens, i + 1):
            if WHOLE_SUBST_FETCH.match(unquote(arg)):
                return ("SUBST-EXEC",
                        "`%s` is given a substituted fetch as its program" % tok)

    command_indexes = [i for i, _ in cmds]
    for k, tok in enumerate(tokens):
        opener = tok == "<(" or (
            tok == "(" and k > 0 and tokens[k - 1].endswith("$")
        )
        if not opener or k + 1 >= len(tokens) or tokens[k + 1] not in FETCHERS:
            continue
        # The CONSUMER decides, not the substitution. `diff <(curl a) <(curl b)`
        # compares two fetched documents and is legitimate; `code=$(curl x)`
        # captures output into a variable and is the commonest shape in this
        # repository's scratch scripts. Only a consumer that treats its argument
        # as a program is a finding.
        preceding = [i for i in command_indexes if i <= k]
        if not preceding:
            continue
        consumer = tokens[max(preceding)]
        if consumer in PROGRAM_CONSUMERS:
            return ("SUBST-EXEC",
                    "`%s` is given a substituted fetch as its program" % consumer)
    return None


def scan_line_egress(line, lineno):
    """(label, detail, lineno) for the first laundered-egress shape on ONE line."""
    tokens = tokenize(line)
    if tokens is None:
        return None                 # unparseable -> allow, per the failure direction
    cmds = command_positions(tokens)

    fetchers = [i for i, t in cmds if t in FETCHERS]
    decoders = [i for i, t in cmds if t in DECODERS]

    for i, tok in cmds:
        if tok not in INTERPRETERS or not reads_program_from_stdin(tokens, i):
            continue
        for j in fetchers:
            if j < i and "|" in tokens[j:i]:
                return ("FETCH-EXEC",
                        "`%s` output is piped into `%s`, which takes its program "
                        "from standard input" % (tokens[j], tok), lineno)
        for j in decoders:
            if j < i and "|" in tokens[j:i]:
                return ("DECODE-EXEC",
                        "`%s` output is piped into `%s`, which takes its program "
                        "from standard input" % (tokens[j], tok), lineno)

    substituted = find_substituted_fetch(tokens, cmds)
    if substituted:
        return substituted + (lineno,)

    for i, tok in cmds:
        if tok in ALWAYS_REMOTE:
            return ("REMOTE-CHANNEL",
                    "`%s` opens a channel to a network peer" % tok, lineno)
        if tok in REMOTE_IF_HOST:
            for arg in args_until_separator(tokens, i + 1):
                if REMOTE_ARG.search(unquote(arg)):
                    return ("REMOTE-CHANNEL",
                            "`%s` is given a remote destination" % tok, lineno)

    for i, tok in cmds:
        if tok in FETCHERS and upload_source_is_secret(tokens, i):
            return ("SECRET-UPLOAD",
                    "`%s` is given an upload flag whose source matches a "
                    "key-material path" % tok, lineno)
    return None


def find_laundered_egress(text):
    """(label, detail, lineno), or None. Scans a SCRIPT BODY, not a command."""
    return walk_command_lines(text, scan_line_egress)


def shell_file_operand(tokens, idx):
    """The script file a shell at `idx` will execute, or None.

    None where the shell's program comes from somewhere this guard cannot read:
    `-c` supplies it inline (already handled by the control-flow scanner's own
    unwrap), `-s` and a bare `-` name stdin, and a shell with no operand at all
    is reading stdin too. None means "no file to check", never "checked".
    """
    args = args_until_separator(tokens, idx + 1)
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "-c" or arg in ("-s", "-"):
            return None
        if arg == "--":
            return args[i + 1] if i + 1 < len(args) else None
        if arg.startswith("-") or arg.startswith("+"):
            if arg in ("-o", "+o", "-O", "+O"):
                i += 2              # these take a value of their own
                continue
            i += 1
            continue
        return arg
    return None


def collect_shell_targets(command):
    """Every script file this command line would hand to a shell."""
    targets = []

    def visit(line, _lineno):
        tokens = tokenize(line)
        if tokens is None:
            return None
        for i, tok in command_positions(tokens):
            if tok in SHELL_WRAPPERS:
                operand = shell_file_operand(tokens, i)
                if operand is not None:
                    targets.append(operand)
        return None                 # never truthy: visit every line

    walk_command_lines(command, visit)
    return targets


def resolve_script_path(token, cwd):
    """(absolute path, None), or (None, reason) when it cannot be pinned."""
    raw = unquote(token)
    if not raw or any(ch in raw for ch in NON_LITERAL):
        # `bash "$SCRIPT"` names a file only at run time. Something will execute
        # and this guard cannot say what, which is a gap rather than a pass --
        # and it is also the obvious way to route around the check.
        return None, "the path is not a literal, so it names a file only at run time"
    path = os.path.expanduser(raw)
    if not os.path.isabs(path):
        path = os.path.join(cwd, path)
    return os.path.normpath(path), None


def read_script(path):
    """(text, None) to scan, (None, None) for nothing-to-run, (None, reason) else.

    THREE states, not two, and the middle one is the reason why. A target that
    does not exist means NOTHING WILL EXECUTE -- the shell errors on its own and
    there is no content to launder, so it is not a gap. A target that exists and
    cannot be read is different in kind: something will run that this guard could
    not inspect. Collapsing the two would either block every `bash` of a
    not-yet-written file or pass an unreadable one as clean.
    """
    try:
        if os.path.isdir(path):
            return None, "the target is a directory, not a script"
        size = os.path.getsize(path)
    except FileNotFoundError:
        return None, None
    except OSError as exc:
        return None, "the target could not be examined (%s)" % type(exc).__name__

    if size > MAX_SCRIPT_BYTES:
        return None, "the target is larger than %d bytes" % MAX_SCRIPT_BYTES
    try:
        with open(path, "rb") as handle:
            raw = handle.read(MAX_SCRIPT_BYTES + 1)
    except OSError as exc:
        return None, "the target could not be read (%s)" % type(exc).__name__
    try:
        return raw.decode("utf-8"), None
    except UnicodeDecodeError:
        return None, "the target is not valid UTF-8 text"


def check_script_contents(command, payload):
    """The stderr message for a blocked script, or None to allow."""
    targets = collect_shell_targets(command)
    if not targets:
        return None                 # nothing to check, which is not a finding

    cwd = payload.get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    for token in targets:
        path, why = resolve_script_path(token, cwd)
        if why:
            return unreadable_message(why)
        text, why = read_script(path)
        if why:
            return unreadable_message(why)
        if text is None:
            continue                # nothing will execute; the shell says so itself
        hit = find_laundered_egress(text)
        if hit:
            return egress_message(*hit)
    return None


def egress_message(label, detail, lineno):
    return (
        "BLOCKED by .claude/hooks/bash-guard.py\n"
        "%s at line %d of a script this command runs.\n"
        "%s.\n\n"
        "The permission system matches the COMMAND STRING and never inspects a "
        "script's contents, so a pre-approved `bash .local/scratch/...` "
        "invocation runs this without the prompt the same step earns when typed "
        "directly. Anthropic documents that `curl` and `wget` are not "
        "auto-approved and prompt like any other non-read-only Bash command.\n\n"
        "Run that step as its own Bash command, where the permission system can "
        "see it and ask. An INBOUND fetch that only reads -- `curl -o file url`, "
        "`wget url` -- is not blocked and needs no change; this fires on "
        "executing fetched bytes, opening a remote channel, or uploading key "
        "material.\n\n"
        "Only the detector and the line number are reported, never a quote from "
        "the file: on a block this text is fed back as context, and the scanned "
        "file is not this hook's own.\n" % (label, lineno, detail)
    )


def unreadable_message(why):
    return (
        "BLOCKED by .claude/hooks/bash-guard.py\n"
        "A script this command would run could not be examined: %s.\n\n"
        "This is NOT a clean result. The contents check reads the script a "
        "pre-approved invocation would execute; when it cannot read that script "
        "it says so rather than passing, because \"checked and clean\" and "
        "\"could not check\" are different claims and only one of them is "
        "evidence.\n\n"
        "Name the script by a literal path that exists and is readable UTF-8 "
        "text. A target that does not exist is NOT this message -- that case is "
        "allowed through, because nothing executes and the shell reports it.\n"
        % why
    )


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

    # LAST, deliberately. The two gates above decide on the command line alone
    # and are load-bearing; running them first means every case they already
    # cover keeps the exit code and the message it had before this check existed.
    contents = check_script_contents(command, payload)
    if contents:
        sys.stderr.write(contents)
        return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())

# KNOWN GAPS -- stated, not hidden. Each is a spelling this guard does not match.
#
# CONTROL FLOW (the command-line scan):
#   * `eval "for i in ...; do ...; done"`  -- the loop is a string until eval runs
#   * `xargs -I{} sh -c '...'`             -- only `bash|sh|zsh -c` is unwrapped
#   * a loop written into a file by other means, then executed
#   * anything at nesting depth > 2
#
# SCRIPT CONTENTS (the egress scan). This raises the cost of the accidental and
# the obvious path. It is NOT a sandbox, and a clean run is not a guarantee --
# read the list below before treating one as evidence of anything wider:
#   * OBFUSCATION. A payload assembled at run time -- string concatenation, a
#     variable holding the command name, a decode step this scan does not model
#     -- defeats a token scan by construction. `$(printf 'cu''rl')` is not
#     `curl` to any tokenizer.
#   * INDIRECTION THROUGH A SECOND FILE. Only the file named on the command line
#     is read. A script that writes, or invokes, another script launders the
#     contents one level down. Not followed deliberately: recursion needs cycle
#     handling and a depth bound, adds a new failure surface, and buys nothing
#     against an adversary who already has the obfuscation route above.
#   * HEREDOC BODIES ARE SKIPPED, as they are on the command line, so a script
#     that writes a payload via `cat > f <<'EOF'` and later runs `f` is not seen.
#     Skipping is what keeps report-generating scripts from false-positiving.
#   * ONLY SHELLS. `python3 x.py` and `node x.js` are not scanned; the scanner is
#     a shell tokenizer and the wrong grammar would misread them. Neither shape
#     is pre-approved here, so neither currently launders a prompt.
#   * A TRANSPARENT PREFIX. `sudo bash x.sh`, `env bash x.sh`, `nohup bash x.sh`
#     put the shell out of command position, so no target is collected. None of
#     them matches the pre-approved prefix either, so all three prompt today.
#   * TOCTOU. The file is read before the tool runs; nothing stops it changing
#     in between.
#   * INBOUND FETCH IS ALLOWED BY DESIGN, not by oversight -- see the module
#     docstring for the measurement and the tracked rule that requires it.
#
# The space of spellings is unbounded and each added pattern widens the false
# positives that train people to work around the guard. Every detector above was
# measured at 0 hits across this repository's 458 real scratch scripts before it
# shipped, and the naive version of the same idea fired on 16.8% of them.
