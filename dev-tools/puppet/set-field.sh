#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"
if [[ $# -lt 2 ]]; then
    echo "usage: $0 <element-id> <value>" >&2
    exit 64
fi
# jq-build the request body so quotes / special chars in value are escaped properly
body="$(jq -nc --arg id "$1" --arg value "$2" '{id:$id, value:$value}')"
puppet_curl POST /setField "$body"
