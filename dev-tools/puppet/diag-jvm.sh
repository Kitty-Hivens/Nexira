#!/usr/bin/env bash
# JVM runtime + memory + GC + OS-load snapshot. Useful for distinguishing
# "render thread is stuck" from "GC is thrashing" / "heap is exhausted".
set -euo pipefail
source "$(dirname "$0")/_common.sh"
puppet_curl GET /diag/jvm
