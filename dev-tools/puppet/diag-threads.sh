#!/usr/bin/env bash
# Full ThreadMXBean dump with locks held + deadlock detection.
# Output is structured JSON aimed at AI / automated profiling; for human
# reading pipe through `jq` or feed straight into an LLM.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
puppet_curl GET /diag/threads
