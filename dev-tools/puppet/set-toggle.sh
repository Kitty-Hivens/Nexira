#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"
if [[ $# -lt 2 ]]; then
    echo "usage: $0 <element-id> <true|false>" >&2
    exit 64
fi
val="$2"
case "$val" in
    true|false) ;;
    *) echo "value must be 'true' or 'false', got '$val'" >&2; exit 64 ;;
esac
body="$(jq -nc --arg id "$1" --argjson value "$val" '{id:$id, value:$value}')"
puppet_curl POST /setToggle "$body"
