#!/usr/bin/env bash
# Plumbing shared by every scenario: the control-surface client, the waits
# built on it, and the process probes the assertions read.
#
# Sourced, never executed. `set -euo pipefail` belongs to the caller.

ITEST_HOST="${ITEST_HOST:-127.0.0.1}"
ITEST_PORT="${ITEST_PORT:-58100}"
ITEST_BASE="http://${ITEST_HOST}:${ITEST_PORT}"
# Every wait below is bounded. A scenario that hangs on a prompt nobody
# answers is worse than a failed one: it takes the whole run with it.
ITEST_WAIT="${ITEST_WAIT:-30}"

# -- control surface --------------------------------------------------------

pup() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-sS -X "$method" -H "Accept: application/json" "${ITEST_BASE}${path}")
    [[ -n "$body" ]] && args+=(-H "Content-Type: application/json" --data "$body")
    curl "${args[@]}"
}

elements() { curl -sS "${ITEST_BASE}/elements" 2>/dev/null || echo '{"elements":[]}'; }

screen() { pup GET /screen | jq -r .screen 2>/dev/null || echo "?"; }

# Present AND enabled. A disabled control is not something a user could
# have clicked, so treating it as present would let a scenario "drive" a
# button the UI was refusing.
has_element() {
    elements | jq -e --arg id "$1" \
        '.elements[] | select(.id == $id and .enabled == true)' >/dev/null 2>&1
}

click()     { pup POST /click "$(jq -nc --arg id "$1" '{id:$id}')" >/dev/null; }
set_field() { pup POST /setField "$(jq -nc --arg id "$1" --arg v "$2" '{id:$id,value:$v}')" >/dev/null; }

# -- waits ------------------------------------------------------------------

wait_element() {
    local id="$1" deadline
    deadline=$(( $(date +%s) + ITEST_WAIT ))
    while ! has_element "$id"; do
        (( $(date +%s) >= deadline )) && return 1
        sleep 0.3
    done
}

# Waits for whichever of the given ids shows up first and echoes it.
# The whole point of the split 2FA namespaces: this is where a driver
# would otherwise answer the wrong prompt and call the run green.
wait_any_element() {
    local deadline id
    deadline=$(( $(date +%s) + ITEST_WAIT ))
    while true; do
        for id in "$@"; do
            if has_element "$id"; then printf '%s' "$id"; return 0; fi
        done
        (( $(date +%s) >= deadline )) && return 1
        sleep 0.3
    done
}

wait_gone() {
    local id="$1" deadline
    deadline=$(( $(date +%s) + ITEST_WAIT ))
    while has_element "$id"; do
        (( $(date +%s) >= deadline )) && return 1
        sleep 0.3
    done
}

wait_file_gone() {
    local path="$1" timeout="${2:-$ITEST_WAIT}" deadline
    deadline=$(( $(date +%s) + timeout ))
    while [[ -e "$path" ]]; do
        (( $(date +%s) >= deadline )) && return 1
        sleep 0.5
    done
}

# -- the second factor ------------------------------------------------------

# Asks the operator for a code, at the moment the server demanded one. Codes
# are time-boxed, so this cannot be collected up front with the rest of the
# run config. Never echoed back, never stored.
ask_code() {
    local which="$1"
    gum input --password=false --placeholder "6-digit code for the $which prompt" \
        --prompt "2FA ($which) > " </dev/tty
}

answer_two_factor() {
    local prefix="$1" which="$2" code
    code="$(ask_code "$which")"
    [[ -z "$code" ]] && return 1
    set_field "$prefix.code" "$code"
    wait_element "$prefix.submit" || return 1
    click "$prefix.submit"
}

# -- process probes ---------------------------------------------------------

# The launched game's pid, matched on its instance directory so a game the
# operator started by hand elsewhere cannot be mistaken for ours.
game_pid() {
    local instance_dir="$1" pid cmd
    for pid in $(pgrep -u "$(id -u)" -f "java" 2>/dev/null || true); do
        cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
        [[ "$cmd" == *"$instance_dir"* ]] || continue
        [[ "$cmd" == *"net.minecraft"* || "$cmd" == *"cpw.mods"* || "$cmd" == *"--gameDir"* ]] || continue
        printf '%s' "$pid"
        return 0
    done
    return 1
}

wait_game() {
    local instance_dir="$1" timeout="${2:-240}" deadline pid
    deadline=$(( $(date +%s) + timeout ))
    while true; do
        if pid="$(game_pid "$instance_dir")"; then printf '%s' "$pid"; return 0; fi
        (( $(date +%s) >= deadline )) && return 1
        sleep 1
    done
}

# What the game was told the session is worth: "mojang" for a real token,
# "legacy" for the offline identity a stripped launch falls back to. Reads
# only that token out of the command line -- the access token sits two
# arguments away and must not reach a terminal, a log or a report.
game_user_type() {
    local pid="$1" cmd
    cmd="$(tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    awk '/^--userType$/ { getline; print; exit }' <<<"$cmd"
}

# -- navigation -------------------------------------------------------------

open_pack() {
    local pack_element="$1"
    click nav.library
    wait_element "$pack_element" || return 1
    click "$pack_element"
    # The detail screen marker carries the instance id, so any PackDetail.*
    # means the click landed somewhere it should have.
    local deadline
    deadline=$(( $(date +%s) + ITEST_WAIT ))
    until [[ "$(screen)" == PackDetail.* ]]; do
        (( $(date +%s) >= deadline )) && return 1
        sleep 0.3
    done
}

# -- misc -------------------------------------------------------------------

plant() {
    local path="$1"
    mkdir -p "$(dirname "$path")"
    printf 'nexira integration probe\n' > "$path"
}

log() { printf '  %s\n' "$*" >&2; }
