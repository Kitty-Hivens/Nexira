#!/usr/bin/env bash
# Last 64 entries from Nexira's ActionRing -- the in-process breadcrumb log
# every launch / auth / sync milestone records. Pair with /diag/threads
# to correlate "what was the launcher doing" with "what does the stack
# say it's blocked on".
set -euo pipefail
source "$(dirname "$0")/_common.sh"
puppet_curl GET /diag/actions
