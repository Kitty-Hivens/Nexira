#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"
if [[ $# -lt 1 ]]; then
    echo "usage: $0 <assetDir>   # e.g. SkyBlock, Industrial, Create" >&2
    exit 64
fi
"$(dirname "$0")/click.sh" "server.select.$1"
