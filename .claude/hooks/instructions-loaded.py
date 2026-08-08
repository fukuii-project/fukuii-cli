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

HOW THE SUBAGENT QUESTION IS ANSWERED, AND WHY NOT BY A FIELD.
No payload carries an agent identifier on this build. That is measured, not
assumed: build_record() writes agent_id and agent_type whenever the event
supplies them, and across the whole log on 2026-08-07 -- 86 records, four
sessions -- neither key appears on any record. A subagent's loads are recorded
under the PARENT session id, so no single record says which context produced it.

The event DOES fire inside a subagent, and the discriminator is arithmetic
rather than a field. A path-scoped rule injects ONCE PER RULE PER SESSION, so a
second `path_glob_match` record for one file under one session id cannot have
landed in the context that produced the first -- it landed in a context that did
not hold that rule yet. repeat_loads() counts those, and the report's subagent
section keys on the count.

The field NAMES are right and the event is the outlier. Anthropic documents
agent_id ("present only when the hook fires inside a subagent call") and
agent_type as arriving on hook events that fire in a subagent context; this one
does not deliver them. So the two are kept, and a build that starts sending them
lights up immediately -- but nothing depends on them.

The competing reading of a repeat is a whole-context re-injection rather than a
dispatch, and the vendor labels that case instead of leaving it to inference:
`compact` is a load reason of its own, alongside session_start,
nested_traversal, path_glob_match and include. report_subagents() checks for it
directly and says which way the window points.
(https://code.claude.com/docs/en/hooks, read 2026-08-07; the matcher table calls
those "example" values, so treat the list as documented rather than exhaustive.)

Stated at this length because that section previously keyed on `agent_id` alone.
No payload has ever supplied it, so the check had no reachable positive state:
it could only ever print its own negative branch, and did so in a session whose
own log already held the repeat records of a dispatch.

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

from lib_harness_text import sanitize

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


def safe(value):
    """Escape and bound one untrusted value before it is PRINTED.

    `--report` renders a markdown document out of values this hook did not
    author: a `file`, a `trigger` and a `load_reason` come from the event
    payload by way of the log, and a roster entry comes from a filename on
    disk. json.dumps escapes a newline on the way INTO the log, and json.load
    hands the real newline back on the way out -- so the log line stayed
    intact while the report did not.

    A newline is legal in a Linux filename and this repository is public, so a
    pull request can add one; one crafted line appended to the gitignored log
    does it too. Either lets a forged `## Result` block render ABOVE the
    genuine one, and a report is read as an audit answer. Demonstrated
    2026-08-07: a fake `RESULT: CLEAN` plus an instruction to disable another
    hook, rendered by the real --report path, exit 0.

    `cap` below is a length bound and nothing more; it never escaped anything.
    The two are separate concerns and this one delegates to the single
    implementation every other hook on this channel imports, rather than
    hand-rolling a second escaper -- the failure the house standard records is
    exactly a correct escaper copied twice and losing the Bidi range both
    times.
    """
    return sanitize(value, MAX_FIELD)


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
    # Recorded when present, depended on never: no payload has carried either
    # field on this build, so a report that keyed on one could not fire. See the
    # docstring -- repeat_loads() is what answers the subagent question, and
    # these two exist so a later build that does supply them is picked up.
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


def repeat_loads(window):
    """Instruction files this session loaded more than once.

    A path-scoped rule injects once per rule per session, so a SECOND
    `path_glob_match` record for one file under one session id cannot have
    landed in the context that produced the first: it landed in a context that
    did not hold the rule yet. A subagent dispatch is what creates one, and its
    loads are recorded under the parent session id.

    Returns (glob_repeats, other_repeats), each mapping file -> the records
    after the first. The two are kept apart because they mean different things.
    A repeated glob match is an additional context reading a matching file. A
    repeated session_start or include is the always-on hierarchy arriving twice,
    which is a context reset rather than a dispatch -- pooling them would let
    one inflate the count that answers for the other.
    """
    seen = set()
    glob_repeats, other_repeats = {}, {}
    for rec in window:
        name = rec.get("file")
        if not name:
            continue
        reason = rec.get("load_reason")
        key = (name, reason)
        if key in seen:
            bucket = glob_repeats if reason == "path_glob_match" else other_repeats
            bucket.setdefault(name, []).append(rec)
        else:
            seen.add(key)
    return glob_repeats, other_repeats


def report_subagents(window):
    """Print the subagent section. Observational: it sets no exit code.

    Whether a dispatch happened is a fact about what the session did, not a
    defect in the roster, so this reports and never votes on the result.
    """
    tagged = [r for r in window if r.get("agent_id")]
    glob_repeats, other_repeats = repeat_loads(window)
    extra = sum(len(v) for v in glob_repeats.values())

    print("## Subagent dispatches")
    print()

    if tagged:
        types = sorted({safe(r.get("agent_type")) or "?" for r in tagged})
        print(f"OBSERVED — {len(tagged)} record(s) carried `agent_id`, "
              f"agent_type(s): {', '.join(types)}.")
        print("This build names the agent in the payload, which is the direct")
        print("answer. Every build measured so far did not; see the docstring.")
        print()

    if glob_repeats:
        print(f"OBSERVED — {extra} repeat load(s) across "
              f"{len(glob_repeats)} rule(s).")
        print()
        print("A path-scoped rule injects once per rule per session, so a second")
        print("`path_glob_match` for one file under one session id landed in a")
        print("context that did not already hold that rule. A subagent dispatch")
        print("is what creates one; its loads are recorded under the PARENT")
        print("session id, which is why no new session appears in the log.")
        print()
        for name in sorted(glob_repeats):
            hits = glob_repeats[name]
            when = ", ".join(safe(h.get("ts")) or "?" for h in hits)
            trig = sorted({safe(h.get("trigger")) for h in hits
                           if h.get("trigger")})
            line = f"  - `{safe(name)}` — {len(hits)} repeat(s) at {when}"
            if trig:
                line += f", triggered by {', '.join(trig)}"
            print(line)
        print()
        print(f"  {extra} is a LOWER bound on the additional contexts. One")
        print("  context reading two matching files still injects each rule")
        print("  once, and a context whose rules had not yet loaded this")
        print("  session leaves no repeat at all.")
        print()
        always_on = len({r.get("file") for r in window
                         if r.get("load_reason") != "path_glob_match"})
        compacted = [r for r in window if r.get("load_reason") == "compact"]
        print("  The competing reading is a whole-context re-injection rather")
        print("  than a dispatch. The vendor labels that case rather than")
        print("  leaving it to be inferred: `compact` is a load reason of its")
        print("  own, alongside session_start, nested_traversal,")
        print("  path_glob_match and include.")
        if compacted:
            print(f"  {len(compacted)} record(s) here carry it, so a re-injection")
            print("  DID happen in this session and the repeats above cannot be")
            print("  read as a dispatch on this evidence alone.")
        elif always_on:
            print("  No record here carries it, and none of the")
            print(f"  {always_on} always-on instruction file(s) that loaded")
            print("  repeated -- a re-injected context would carry those too.")
            print("  Both point away from a re-injection.")
        else:
            print("  No record here carries it. But no always-on instruction")
            print("  file loaded in this window either, so the corroborating")
            print("  half is absent; read the finding as an additional context.")
        print()

    if other_repeats:
        n = sum(len(v) for v in other_repeats.values())
        print(f"SEPARATELY — {n} repeat load(s) that were NOT glob matches, "
              f"across {len(other_repeats)} file(s):")
        for name in sorted(other_repeats):
            reasons = sorted({safe(r.get("load_reason")) or "?"
                              for r in other_repeats[name]})
            print(f"  - `{safe(name)}` — {len(other_repeats[name])} repeat(s), "
                  f"{', '.join(reasons)}")
        print("The always-on hierarchy arriving more than once is a context")
        print("reset, not a dispatch. Counted apart so it cannot inflate the")
        print("figure above.")
        print()

    if not tagged and not glob_repeats:
        print("NOT OBSERVED — no repeat load in this session, and no record")
        print("carried `agent_id`.")
        print()
        print("That is NOT evidence the event skips subagents. It is equally")
        print("consistent with no subagent having been dispatched, or with one")
        print("that read no file matching a rule this session had not already")
        print("loaded. Dispatch one that reads a matching file, then re-run.")
        print()


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
    print(f"Root: {safe(os.path.basename(os.path.realpath(root)))}")
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

    print(f"Session: {safe(target)}   (of {len(sessions)} in the log)")
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
            reasons = sorted({safe(h.get("load_reason")) or "?" for h in hits})
            triggers = sorted({safe(h.get("trigger")) for h in hits
                               if h.get("trigger")})
            trig = triggers[0] if triggers else "—"
            if len(triggers) > 1:
                trig += f" (+{len(triggers) - 1})"
            mark = f"yes ({len(hits)})"
            print(f"| `{safe(member['file'])}` | {scope} | {mark} | "
                  f"{', '.join(reasons)} | {trig} |")
        else:
            print(f"| `{safe(member['file'])}` | {scope} | **NO** | — | — |")
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
            reasons = sorted({safe(r.get("load_reason")) or "?"
                              for r in observed[f]})
            print(f"- `{safe(f)}` — {len(observed[f])} load(s), "
                  f"{', '.join(reasons)}")
        print()

    report_subagents(window)

    print("## Result")
    print()
    if scoped_missing:
        print("Path-scoped rules with no observed load:")
        for member in scoped_missing:
            globs = ", ".join(safe(g) for g in member["globs"])
            print(f"  - `{safe(member['file'])}`  globs: [{globs}]")
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
                print(f"  - `{safe(member['file'])}`")
            print()
            print("Lazy loads recorded after registration ARE valid — read the")
            print("table above for those. For the eager half, start a fresh")
            print("session with the hook already registered and re-run.")
            return 2

        print("RESULT: FINDINGS — an unscoped instruction file did not load.")
        for member in unscoped_missing:
            print(f"  - `{safe(member['file'])}` has no `paths:` frontmatter, "
                  f"so it")
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
