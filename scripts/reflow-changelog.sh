#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# reflow-changelog.sh
#
# Reads a CHANGELOG section (markdown) on stdin and joins soft-wrapped
# continuation lines within a paragraph or list item into one physical line,
# writing the result to stdout.
#
# Why: a CHANGELOG entry is authored with ~72-column hard wraps, which is
# invisible in a rendered `.md` file (CommonMark: a single newline is a space).
# But the release pipeline feeds the same text to two GFM-with-breaks-on
# surfaces -- the GitHub Release body and the in-app updater's "What's new"
# panel -- where every wrap newline becomes a literal <br>, so each bullet
# renders as a narrow multi-line staircase. Reflowing at this boundary keeps the
# `.md` source wrapped however the author likes while the derived text stays
# one-line-per-bullet and wraps to the viewport.
#
# Block boundaries (flushed, emitted as-is, never joined into):
#   - blank line                 -> paragraph separator
#   - ATX header (#...)          -> standalone
#   - table row (|) / quote (>)  -> standalone
#   - list item (-, *, +)        -> starts a NEW logical line that following
#                                   continuation lines append to
# Any other non-blank line is a continuation and is appended with one space.
#
# The list-marker heuristic fits this CHANGELOG: continuation lines indent to
# align under the marker's text (no marker), so only a genuine nested bullet
# starts with `- ` after indent. Safe for the project CHANGELOG because it has
# no fenced code blocks -- a fence would need verbatim passthrough, which this
# does not implement.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

awk '
function flush() { if (have) { print buf; have = 0; buf = "" } }
{
  line = $0
  if (line ~ /^[ \t]*$/) { flush(); print ""; next }

  stripped = line
  sub(/^[ \t]+/, "", stripped)

  # Header / table row / blockquote: standalone, never joined.
  if (line ~ /^#/ || stripped ~ /^[|>]/) { flush(); print line; next }

  # List item start: flush the previous logical line, begin a new one (keeping
  # the marker and any nesting indent) that continuation lines append to.
  if (stripped ~ /^[-*+][ \t]/) { flush(); buf = line; have = 1; next }

  # Continuation line: append, trimmed, with a single space.
  if (have) buf = buf " " stripped
  else { buf = stripped; have = 1 }
}
END { flush() }
'
