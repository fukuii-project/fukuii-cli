#!/usr/bin/env python3
"""One implementation of context-channel escaping, imported by every hook.

WHY THIS IS A MODULE AND NOT A HELPER IN EACH HOOK. Both advisory hooks echo
text they did not author into `additionalContext`, which Claude Code wraps in a
system reminder -- the highest-trust position in the window. That is an audit
channel, and an audit channel needs escaping. Two hooks each carrying their own
copy of that escaping is not a style question: it is a documented failure. The
house collector standard records one correct escaper hand-copied into two new
collectors, where BOTH copies silently lost the Bidi range and used an escape
form wrong above 0xff. One implementation, imported, cannot drift from itself.

Imported by name rather than by path: Python puts a script's own directory on
sys.path, and these hooks are invoked as `python3 .claude/hooks/<hook>.py`, so
`import lib_harness_text` resolves. The underscore in the filename is what makes
that possible -- the hooks themselves are hyphenated and are not importable.

WHAT IS ESCAPED, AND WHY EACH RANGE. All of it is text that renders as something
other than what it is, which is the whole threat: a reader of the reminder, and
the model reading it, must see the same characters.

  * C0 and C1 controls, and DEL. A newline or a carriage return inside a quoted
    snippet breaks out of the one-line-per-finding shape and lets crafted text
    forge additional findings, or a closing line for the whole block.
  * U+2028 and U+2029, the Unicode line and paragraph separators: the same
    break as a newline, past any check that only looked for a newline.
  * The Bidi formatting characters -- U+061C, U+200E, U+200F, U+202A-U+202E,
    U+2066-U+2069. Trojan Source, CVE-2021-42574: these reorder how a run of
    text DISPLAYS without changing what it is, so a snippet can be made to read
    as its own opposite.

THE RANGES ARE DECLARED AS INTEGERS, AND THAT IS LOAD-BEARING. Writing the
character class with literal characters puts a real U+202E in this file, which
reverses the display of everything after it in every editor and every diff -- a
module defending against Trojan Source would then be carrying the first thing it
should have caught. Even the escape-sequence spelling is a trap here, because an
editor or a tool that normalises the file can turn `\\u202e` back into the
character. Integers cannot be normalised into anything.

Escaped rather than dropped, deliberately. Dropping a character makes the
tampering invisible, which is the outcome an attacker wanted; a visible
`\\u202e` in the output says plainly that something was there.
"""

import re

# (first, last) inclusive codepoint ranges. Checkable against the docstring
# above by reading numbers, with nothing to decode and nothing to render.
UNSAFE_RANGES = (
    (0x0000, 0x001F),   # C0 controls, including newline, carriage return, tab
    (0x007F, 0x009F),   # DEL and the C1 controls
    (0x061C, 0x061C),   # ARABIC LETTER MARK
    (0x200E, 0x200F),   # LEFT-TO-RIGHT MARK, RIGHT-TO-LEFT MARK
    (0x2028, 0x2029),   # LINE SEPARATOR, PARAGRAPH SEPARATOR
    (0x202A, 0x202E),   # LRE, RLE, PDF, LRO, RLO
    (0x2066, 0x2069),   # LRI, RLI, FSI, PDI
)

_UNSAFE = re.compile(
    "[" + "".join(f"\\u{lo:04x}-\\u{hi:04x}" for lo, hi in UNSAFE_RANGES) + "]"
)


def _escape(match):
    """`\\uXXXX` for the BMP, `\\UXXXXXXXX` above it.

    The width matters: a fixed four-digit form is wrong above 0xffff, which is
    the second half of the defect this module exists to prevent. Nothing in the
    ranges above is astral today, and the form is correct anyway so that
    widening a range later cannot quietly reintroduce it.
    """
    code = ord(match.group(0))
    return f"\\u{code:04x}" if code <= 0xFFFF else f"\\U{code:08x}"


def sanitize(text, limit=None):
    """Make one run of untrusted text safe to place in a system reminder.

    `limit` caps the result AFTER escaping, because escaping is what can grow
    it: a run of control characters expands sixfold, so a cap applied first
    would not bound what is actually emitted. Truncation is marked, never
    silent -- a reader must be able to tell a short line from a cut one.
    """
    if text is None:
        return ""
    out = _UNSAFE.sub(_escape, str(text))
    if limit is not None and len(out) > limit:
        return out[: max(0, limit - 3)] + "..."
    return out
