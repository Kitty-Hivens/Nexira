#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"
if [[ $# -lt 1 ]]; then
    echo "usage: $0 <assetDir>   # e.g. SkyBlock, Industrial, Create" >&2
    exit 64
fi
echo "[puppet] select $1"
"$(dirname "$0")/click.sh" "server.select.$1" >/dev/null
sleep 0.2
echo "[puppet] click dashboard.launch"
"$(dirname "$0")/click.sh" dashboard.launch
