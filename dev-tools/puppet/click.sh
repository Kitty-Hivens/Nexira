#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"
if [[ $# -lt 1 ]]; then
    echo "usage: $0 <element-id>" >&2
    exit 64
fi
puppet_curl POST /click "{\"id\":\"$1\"}"
