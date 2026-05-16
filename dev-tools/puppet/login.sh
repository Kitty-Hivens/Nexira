#!/usr/bin/env bash
# Drive the login form, optionally complete a 2FA prompt if it appears.
#
# Polls /screen between steps because submit is async — the API call
# returns 200 OK as soon as the click is dispatched, NOT after the
# server-side login completes. We need to wait for either:
#   * screen -> "Dashboard" (login succeeded directly)
#   * screen -> "Login_2FA" (server asked for a code)
#
# Usage:
#   ./login.sh <username> <password>           # no 2FA expected
#   ./login.sh <username> <password> <code>    # supply TOTP code in advance
#
# A timeout of 15 s on each wait — adjust via PUPPET_WAIT_TIMEOUT.

set -euo pipefail
source "$(dirname "$0")/_common.sh"

if [[ $# -lt 2 ]]; then
    echo "usage: $0 <username> <password> [totp-code]" >&2
    exit 64
fi

USERNAME="$1"
PASSWORD="$2"
TOTP_CODE="${3:-}"
WAIT_TIMEOUT="${PUPPET_WAIT_TIMEOUT:-15}"

wait_screen() {
    local want="$1" deadline now
    deadline=$(( $(date +%s) + WAIT_TIMEOUT ))
    while true; do
        local current
        current="$(puppet_curl GET /screen | jq -r .screen)"
        if [[ "$current" == "$want" ]]; then
            echo "[puppet] screen=$current"
            return 0
        fi
        now=$(date +%s)
        if (( now >= deadline )); then
            echo "[puppet] timeout waiting for screen=$want, current=$current" >&2
            return 1
        fi
        sleep 0.3
    done
}

wait_screen_either() {
    local want_a="$1" want_b="$2" deadline now
    deadline=$(( $(date +%s) + WAIT_TIMEOUT ))
    while true; do
        local current
        current="$(puppet_curl GET /screen | jq -r .screen)"
        if [[ "$current" == "$want_a" || "$current" == "$want_b" ]]; then
            echo "[puppet] screen=$current"
            printf '%s' "$current"
            return 0
        fi
        now=$(date +%s)
        if (( now >= deadline )); then
            echo "[puppet] timeout waiting for screen in {$want_a,$want_b}, current=$current" >&2
            return 1
        fi
        sleep 0.3
    done
}

echo "[puppet] waiting for Login screen"
wait_screen Login >/dev/null

echo "[puppet] filling username"
"$(dirname "$0")/set-field.sh" login.username "$USERNAME" >/dev/null

echo "[puppet] filling password"
"$(dirname "$0")/set-field.sh" login.password "$PASSWORD" >/dev/null

echo "[puppet] click login.submit"
"$(dirname "$0")/click.sh" login.submit >/dev/null

reached="$(wait_screen_either Dashboard Login_2FA | tail -n 1)"

if [[ "$reached" == "Login_2FA" ]]; then
    if [[ -z "$TOTP_CODE" ]]; then
        echo "[puppet] 2FA required but no code supplied; pass as 3rd arg" >&2
        exit 1
    fi
    echo "[puppet] filling 2FA code"
    "$(dirname "$0")/set-field.sh" login.twoFactor.code "$TOTP_CODE" >/dev/null
    echo "[puppet] click login.twoFactor.submit"
    "$(dirname "$0")/click.sh" login.twoFactor.submit >/dev/null
    wait_screen Dashboard >/dev/null
fi

echo "[puppet] logged in OK"
