#!/usr/bin/env bash
# Shared helpers for puppet scripts.

PUPPET_HOST="${PUPPET_HOST:-127.0.0.1}"
PUPPET_PORT="${PUPPET_PORT:-58000}"
PUPPET_BASE="http://${PUPPET_HOST}:${PUPPET_PORT}"

# curl wrapper that surfaces non-2xx as a non-zero exit code AND prints
# the JSON body so callers can pipe to jq even on errors.
puppet_curl() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-sS -X "$method" -H "Accept: application/json" "${PUPPET_BASE}${path}")
    if [[ -n "$body" ]]; then
        args+=(-H "Content-Type: application/json" --data "$body")
    fi
    local response status
    response="$(curl "${args[@]}" -w '\n__HTTP_STATUS__:%{http_code}' 2>&1)" || {
        echo "curl failed talking to ${PUPPET_BASE}" >&2
        return 2
    }
    status="${response##*__HTTP_STATUS__:}"
    response="${response%$'\n'__HTTP_STATUS__:*}"
    printf '%s\n' "$response"
    if (( status >= 400 )); then
        echo "(http $status)" >&2
        return 1
    fi
}
