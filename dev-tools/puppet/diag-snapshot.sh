#!/usr/bin/env bash
# Composite dump: /diag/threads + /diag/jvm + /diag/actions + /elements
# (UI snapshot) in a single round-trip. The convenient "AI hit one URL,
# get everything" call -- the freeze diagnostic workflow is essentially
# `diag-snapshot.sh > baseline.json` then `diag-snapshot.sh > frozen.json`
# and diff.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
puppet_curl GET /diag/snapshot
