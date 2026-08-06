#!/usr/bin/env python3
"""InstructionsLoaded hook: record which instruction files load, when, and why.

Two modes, one subject, one file:

    (no arguments)          RECORD. Reads an InstructionsLoaded payload as JSON
                            on stdin and appends one JSONL record to
                            .local/instructions-loaded.jsonl
    --report [ROOT]         RECONCILE. Differences the declared roster of this
                            repository's instruction files against what the log
                            observed, and names the ones that did NOT load.
    --selftest              Run the calibration suite (instructions-loaded.test.py).

WHY THE RECONCILER EXISTS, AND WHY THE HOOK ALONE IS NOT THE ARTIFACT.
The vendor event fires ONLY when a file IS loaded. There is no "did not load"
event, no "glob evaluated and did not match" event, and the hook has no decision
control. So a bare recorder can emit exactly one verdict -- "loaded" -- which is
a check with no reachable fail state: it would report success on a repository
whose every path-scoped rule was silently dead, because a rule that never loads
simply never appears.

The negative claim is therefore produced by DIFFERENCE, not by the event: the
roster is enumerated from disk, the log supplies what was observed, and whatever
is in the first and not the second did not load. That inference is only sound
while the recorder is known live, which is what the exit-2 rule below buys.

THE LIVENESS PRECONDITION, WHICH IS THE WHOLE CALIBRATION.
"No record for rule X" and "no records at all" are the same bytes in an empty
file and mean opposite things -- the first is a finding about X, the second is a
finding about this hook. They are separated by exit code: an absent, empty, or
unparseable log, or a log with no record for the requested session, is exit 2
(COULD NOT RUN) and never exit 0 (CLEAN). A report that cannot tell those apart
is the defect it was built to detect, wearing the detector's name.

Reports are scoped to ONE session, defaulting to the most recent in the log.
Without that scope a rule deleted last week still counts as observed, and a rule
added this morning reads as broken because older sessions could not have loaded
it. The session's own record count is printed as the liveness witness.

WHY THE EXIT CODE IS NOT THIS HOOK'S REPORTING CHANNEL.
Verified against the vendor exit-code table: for InstructionsLoaded, "Exit code
is ignored". The transcript-visible exit-2 path granted to SessionStart, Setup
and SubagentStart as of v2.1.199 does NOT extend to this event, and the event
has no decision control at all. So in RECORD mode this hook has no channel back
to the session and cannot announce its own failure: a write failure is recorded
in the log where the log is writable, and is invisible where it is not. Stated
plainly rather than papered over -- sibling hook session-roadmap.py can use exit
2 for visibility and this one genuinely cannot.

Exit codes therefore apply to --report only, in the reporting-collector
vocabulary: 0 = clean, 1 = findings, 2 = could not run. RECORD mode always
exits 0, because nothing reads it.

THE SEAM. The log lives under .local/, which is gitignored, so it does not
travel to a clone. RECORD mode writes nothing where .local/ is absent and exits
silently: a clone is not a defect and must not be given a file it did not ask
for. This is why the FILE is safe to track even though its registration is not
shipped -- see .claude/settings.local.json for that reasoning.

The log holds absolute paths for instruction files outside this repository
(user-level rules, ancestor CLAUDE.md files). Paths inside the repository are
recorded repo-relative. That is one more reason the log is gitignored.

CONCURRENCY. Records are appended with a single O_APPEND write of one line,
which POSIX makes atomic below PIPE_BUF (4096 bytes). Fields are capped and the
assembled line is truncated to stay under that bound, because this event fires
in parallel across the main session and any subagents.
"""

import json
import os
import subprocess
import sys
from datetime import datetime, timezone

LOG_REL = os.path.join(".local", "instructions-loaded.jsonl")
RULES_REL = os.path.join(".claude", "rules")

# One record must fit in a single atomic O_APPEND write. PIPE_BUF is 4096 on
# Linux; the margin absorbs the JSON envelope.
MAX_LINE = 3800
MAX_FIELD = 300

# Roster members that are not rules files but are still instruction files this
# repository declares. AGENTS.md arrives as an `include` load, because the
# tracked CLAUDE.md is a one-line `@AGENTS.md` import.
EXTRA_ROSTER = ("CLAUDE.md", "AGENTS.md")


def project_dir(payload=None):
    """CLAUDE_PROJECT_DIR if Claude Code set it, else the repo root from cwd."""
    env = os.environ.get("CLAUDE_PROJECT_DIR")
    if env and os.path.isdir(env):
        return env
    cwd = (payload or {}).get("cwd") or os.getcwd()
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


def relativize(path, root):
    """Repo-internal paths become repo-relative; anything else stays absolute."""
    if not path:
        return path
    try:
        real_root = os.path.realpath(root)
        real_path = os.path.realpath(path)
    except OSError:
        return path
    prefix = real_root + os.sep
    if real_path.startswith(prefix):
        return real_path[len(prefix):]
    return path


def cap(value):
    """Bound one field. Truncation is marked, never silent."""
    if value is None:
        return None
    text = value if isinstance(value, str) else str(value)
    if len(text) > MAX_FIELD:
        return text[:MAX_FIELD] + "...TRUNCATED"
    return text


# --------------------------------------------------------------------------
# RECORD mode
# --------------------------------------------------------------------------

def build_record(payload, root):
    """One log record from one InstructionsLoaded payload."""
    globs = payload.get("globs")
    if isinstance(globs, list):
        globs = [cap(g) for g in globs[:20]]
    elif globs is not None:
        globs = [cap(globs)]

    rec = {
        "ts": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "session_id": cap(payload.get("session_id")),
        "file": cap(relativize(payload.get("file_path"), root)),
        "memory_type": cap(payload.get("memory_type")),
        "load_reason": cap(payload.get("load_reason")),
    }
    # Optional fields are omitted when absent rather than written as null, so a
    # reader can tell "the event did not carry this" from "it carried nothing".
    if globs:
        rec["globs"] = globs
    trigger = payload.get("trigger_file_path")
    if trigger:
        rec["trigger"] = cap(relativize(trigger, root))
    parent = payload.get("parent_file_path")
    if parent:
        rec["parent"] = cap(relativize(parent, root))
    # agent_id is present ONLY inside a subagent. Recording it is what makes this
    # log able to answer whether the event fires for subagent dispatches -- a
    # question the vendor docs state for tool events and leave open for this one.
    if payload.get("agent_id"):
        rec["agent_id"] = cap(payload.get("agent_id"))
    if payload.get("agent_type"):
        rec["agent_type"] = cap(payload.get("agent_type"))
    return rec


def append_record(log_path, rec):
    """Append one line atomically. Returns True on success."""
    line = json.dumps(rec, separators=(",", ":"), sort_keys=True) + "\n"
    data = line.encode("utf-8")
    if len(data) > MAX_LINE:
        rec = {k: rec[k] for k in ("ts", "session_id", "file", "load_reason")
               if k in rec}
        rec["oversize"] = True
        line = json.dumps(rec, separators=(",", ":"), sort_keys=True) + "\n"
        data = line.encode("utf-8")[:MAX_LINE]
    try:
        fd = os.open(log_path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        try:
            os.write(fd, data)
        finally:
            os.close(fd)
        return True
    except OSError:
        return False


def record_mode():
    """Read one payload, write one record. Always exit 0 -- nothing reads it."""
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        payload = {}
    if not isinstance(payload, dict):
        payload = {}

    root = project_dir(payload)

    # A clone has no .local/. Write nothing and say nothing: correct silence,
    # not a degraded path. Never create the directory -- that would hand a clone
    # a file it did not ask for.
    if not os.path.isdir(os.path.join(root, ".local")):
        return 0

    append_record(os.path.join(root, LOG_REL), build_record(payload, root))
    return 0


# --------------------------------------------------------------------------
# REPORT mode
# --------------------------------------------------------------------------

def parse_paths_frontmatter(path):
    """Return the paths: globs of a rules file, or [] when it is unscoped.

    Deliberately line-based: no YAML dependency, and the only shape that matters
    is a `paths:` key inside leading `---` fences followed by `- "glob"` items.
    """
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError:
        return []
    if not lines or lines[0].strip() != "---":
        return []
    globs = []
    in_paths = False
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


def roster(root):
    """Every instruction file this repository declares, with its scope."""
    members = []
    for name in EXTRA_ROSTER:
        if os.path.isfile(os.path.join(root, name)):
            members.append({"file": name, "globs": []})
    rules_dir = os.path.join(root, RULES_REL)
    if os.path.isdir(rules_dir):
        for name in sorted(os.listdir(rules_dir)):
            if not name.endswith(".md"):
                continue
            rel = os.path.join(RULES_REL, name)
            members.append({
                "file": rel,
                "globs": parse_paths_frontmatter(os.path.join(rules_dir, name)),
            })
    return members


def read_log(log_path):
    """Return (records, malformed_count). Unreadable log returns (None, 0)."""
    if not os.path.isfile(log_path):
        return None, 0
    records, malformed = [], 0
    try:
        with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except (json.JSONDecodeError, ValueError):
                    malformed += 1
                    continue
                if isinstance(obj, dict):
                    records.append(obj)
                else:
                    malformed += 1
    except OSError:
        return None, 0
    return records, malformed


def report_mode(argv):
    root = None
    session = None
    rest = list(argv)
    while rest:
        arg = rest.pop(0)
        if arg == "--session":
            session = rest.pop(0) if rest else None
        elif root is None:
            root = arg
    root = root or project_dir()
    log_path = os.path.join(root, LOG_REL)

    print("# InstructionsLoaded — roster reconciliation")
    print()
    print(f"Root: {os.path.basename(os.path.realpath(root))}")
    print(f"Log:  {LOG_REL}")
    print()

    records, malformed = read_log(log_path)
    if records is None:
        print("RESULT: COULD NOT RUN — no log at that path.")
        print()
        print("The hook has not run, or is not registered. This is NOT a clean")
        print("result: nothing was observed, so nothing can be concluded about")
        print("which rules loaded. Register the hook and start a new session.")
        return 2
    if not records:
        print(f"RESULT: COULD NOT RUN — log present but holds no records "
              f"({malformed} malformed line(s)).")
        print()
        print("An empty log and a rule that never loaded are indistinguishable")
        print("here, which is exactly why this is exit 2 rather than exit 0.")
        return 2

    sessions = []
    for rec in records:
        sid = rec.get("session_id")
        if sid and sid not in sessions:
            sessions.append(sid)
    target = session or (sessions[-1] if sessions else None)
    window = [r for r in records if r.get("session_id") == target]
    if not window:
        print(f"RESULT: COULD NOT RUN — no records for session {target!r}.")
        print(f"Sessions present in the log: {len(sessions)}")
        return 2

    print(f"Session: {target}   (of {len(sessions)} in the log)")
    print(f"Records in this session: {len(window)}   <- recorder liveness witness")
    if malformed:
        print(f"Malformed lines skipped: {malformed}")
    print()

    observed = {}
    for rec in window:
        observed.setdefault(rec.get("file"), []).append(rec)

    members = roster(root)
    unscoped_missing, scoped_missing = [], []

    print("## Declared roster")
    print()
    print("| Instruction file | Scope | Observed | Load reason | Trigger |")
    print("|---|---|---|---|---|")
    for member in members:
        hits = observed.get(member["file"], [])
        scope = "path-scoped" if member["globs"] else "always"
        if hits:
            reasons = sorted({h.get("load_reason") or "?" for h in hits})
            triggers = sorted({h.get("trigger") for h in hits if h.get("trigger")})
            trig = triggers[0] if triggers else "—"
            if len(triggers) > 1:
                trig += f" (+{len(triggers) - 1})"
            mark = f"yes ({len(hits)})"
            print(f"| `{member['file']}` | {scope} | {mark} | "
                  f"{', '.join(reasons)} | {trig} |")
        else:
            print(f"| `{member['file']}` | {scope} | **NO** | — | — |")
            if member["globs"]:
                scoped_missing.append(member)
            else:
                unscoped_missing.append(member)
    print()

    extra = [f for f in observed if f and not any(m["file"] == f for m in members)]
    if extra:
        print("## Observed, outside this repository's roster")
        print()
        print("Ancestor and user-level instruction files. Listed because they")
        print("cost context here and are invisible to a roster built from this")
        print("repository alone.")
        print()
        for f in sorted(extra):
            reasons = sorted({r.get("load_reason") or "?" for r in observed[f]})
            print(f"- `{f}` — {len(observed[f])} load(s), {', '.join(reasons)}")
        print()

    sub = [r for r in window if r.get("agent_id")]
    print("## Subagent dispatches")
    print()
    if sub:
        types = sorted({r.get("agent_type") or "?" for r in sub})
        print(f"OBSERVED: {len(sub)} record(s) carried `agent_id`, "
              f"agent_type(s): {', '.join(types)}.")
        print("The event fires inside subagents on this build.")
    else:
        print("No record in this session carried `agent_id`.")
        print("That is NOT evidence the event skips subagents — it is equally")
        print("consistent with no subagent having been dispatched. Dispatch one")
        print("that reads a matching file, then re-run this report.")
    print()

    print("## Result")
    print()
    if scoped_missing:
        print("Path-scoped rules with no observed load:")
        for member in scoped_missing:
            print(f"  - `{member['file']}`  globs: {member['globs']}")
        print()
        print("  Not counted as a failure on its own: a path-scoped rule is")
        print("  SUPPOSED to stay absent until a matching file is read. It is a")
        print("  finding only relative to what this session actually opened —")
        print("  read a matching file, then re-run. If it is still absent after")
        print("  that, the glob is dead.")
        print()

    if unscoped_missing:
        # A session whose window holds no session_start record at all is one
        # where the recorder was not live when the eager loads happened -- the
        # hook was registered mid-session, or turned on after launch. Every
        # unscoped member is then trivially "not observed", and calling that
        # FINDINGS would be the instrument's own blind spot reported as a defect
        # in the thing it watches. That is COULD NOT RUN, not a finding.
        if not any(r.get("load_reason") == "session_start" for r in window):
            print("RESULT: COULD NOT RUN — no `session_start` record in this")
            print("session, so the recorder was not live when the eager loads")
            print("happened. The hook was registered mid-session.")
            print()
            print("Unscoped members cannot be adjudicated from this window:")
            for member in unscoped_missing:
                print(f"  - `{member['file']}`")
            print()
            print("Lazy loads recorded after registration ARE valid — read the")
            print("table above for those. For the eager half, start a fresh")
            print("session with the hook already registered and re-run.")
            return 2

        print("RESULT: FINDINGS — an unscoped instruction file did not load.")
        for member in unscoped_missing:
            print(f"  - `{member['file']}` has no `paths:` frontmatter, so it")
            print("    must load at session start. It did not.")
        return 1

    print("RESULT: CLEAN — every unscoped instruction file in the roster was")
    print(f"observed loading in this session ({len(window)} records).")
    return 0


def main():
    argv = sys.argv[1:]
    if argv and argv[0] == "--report":
        return report_mode(argv[1:])
    if argv and argv[0] == "--selftest":
        test = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "instructions-loaded.test.py")
        return subprocess.call([sys.executable, test])
    if argv and argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    return record_mode()


if __name__ == "__main__":
    sys.exit(main())
