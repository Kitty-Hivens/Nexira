#!/usr/bin/env bash
# Integration runs against the real GUI, configured from a terminal.
#
# The launcher is started on a data directory of its own, driven through the
# puppet control surface, and checked against the filesystem and the process
# it spawns. What cannot be automated -- which account, which password, which
# code, which pack -- is asked here, at the moment it is needed.
#
#   ./dev-tools/itest/run.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/dev-tools/itest"

for tool in gum jq curl; do
    command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done

source "$HERE/lib/common.sh"
source "$HERE/lib/assert.sh"

# -- run configuration ------------------------------------------------------

gum style --bold "Nexira UI integration run"

DEFAULT_DATA="${XDG_CACHE_HOME:-$HOME/.cache}/nexira-itest/data"
REAL_DATA="${XDG_DATA_HOME:-$HOME/.local/share}/nexira"

ITEST_DATA_DIR="$(gum input --value "$DEFAULT_DATA" --prompt "data dir > ")"
[[ -z "$ITEST_DATA_DIR" ]] && exit 1
if [[ "$(readlink -m "$ITEST_DATA_DIR")" == "$(readlink -m "$REAL_DATA")" ]]; then
    # The run plants files, locks directories and launches games. None of that
    # belongs in the directory the operator actually plays out of.
    gum style --foreground 1 "that is the real data directory; pick another"
    exit 1
fi
mkdir -p "$ITEST_DATA_DIR"

ITEST_ACCOUNT="$(gum choose --header "account under test" \
    "sc-2fa" "sc-plain" "offline")"
[[ -z "$ITEST_ACCOUNT" ]] && exit 1

ITEST_USER="$(gum input --prompt "username > ")"
ITEST_PASS=""
if [[ "$ITEST_ACCOUNT" != "offline" ]]; then
    ITEST_PASS="$(gum input --password --prompt "password > ")"
    gum style --foreground 3 \
        "A real sign-in follows. It overwrites the saved account in the system keyring" \
        "and invalidates whatever SmartyCraft session is currently live."
    gum confirm "continue?" || exit 1
fi

mapfile -t AVAILABLE < <(find "$HERE/scenarios" -name '*.sh' -printf '%f\n' | sort)
mapfile -t CHOSEN < <(gum choose --no-limit --header "scenarios" "${AVAILABLE[@]}")
[[ ${#CHOSEN[@]} -eq 0 ]] && exit 1

DISPLAY_MODE="$(gum choose --header "display" "Xvfb (isolated)" "current session")"
ITEST_PORT="$(gum input --value "58100" --prompt "puppet port > ")"

export ITEST_PORT ITEST_BASE="http://${ITEST_HOST}:${ITEST_PORT}"

# -- boot -------------------------------------------------------------------

XVFB_PID=""
APP_PID=""

teardown() {
    [[ -n "$APP_PID" ]] && kill -- "-$APP_PID" 2>/dev/null || true
    [[ -n "$XVFB_PID" ]] && kill "$XVFB_PID" 2>/dev/null || true
}
trap teardown EXIT

if [[ "$DISPLAY_MODE" == Xvfb* ]]; then
    command -v Xvfb >/dev/null || { echo "missing: Xvfb" >&2; exit 1; }
    XV_DISPLAY=":$(( 90 + RANDOM % 9 ))"
    Xvfb "$XV_DISPLAY" -screen 0 1600x1000x24 >/dev/null 2>&1 &
    XVFB_PID=$!
    export DISPLAY="$XV_DISPLAY"
    unset WAYLAND_DISPLAY
    log "Xvfb on $XV_DISPLAY (pid $XVFB_PID)"
fi

APP_LOG="$ITEST_DATA_DIR/itest-run.log"
log "starting the launcher (log: $APP_LOG)"
# Own process group: the run has to take the whole tree down, and killing
# Gradle alone leaves the application JVM behind.
setsid "$ROOT/gradlew" -p "$ROOT" :client-ui:run \
    -PnexiraPuppetPort="$ITEST_PORT" \
    -PnexiraDataDir="$ITEST_DATA_DIR" \
    >"$APP_LOG" 2>&1 &
APP_PID=$!

boot_deadline=$(( $(date +%s) + 420 ))
until curl -sS "${ITEST_BASE}/screen" >/dev/null 2>&1; do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        gum style --foreground 1 "the launcher exited before the control surface came up"
        tail -n 30 "$APP_LOG"
        exit 1
    fi
    (( $(date +%s) >= boot_deadline )) && { echo "boot timed out" >&2; exit 1; }
    sleep 2
done
log "control surface up on port $ITEST_PORT"

# -- run --------------------------------------------------------------------

export ITEST_DATA_DIR ITEST_ACCOUNT ITEST_USER ITEST_PASS

needs_pack=false
for s in "${CHOSEN[@]}"; do
    [[ "$s" == 10-* ]] || needs_pack=true
done

run_scenario() {
    local file="$1"
    unset -f run 2>/dev/null || true
    NAME=""
    source "$HERE/scenarios/$file"
    ITEST_CURRENT="${NAME:-$file}"
    gum style --bold "» $ITEST_CURRENT"
    run
}

# The login scenario has to go first when it was chosen at all: everything
# else needs a session, and the pack pickers below need a populated Library.
for s in "${CHOSEN[@]}"; do
    [[ "$s" == 10-* ]] && run_scenario "$s"
done

if $needs_pack; then
    click nav.profile
    sleep 1
    if ! has_element account.logout; then
        gum style --foreground 3 "not signed in -- sign in in the window, then continue"
        gum confirm "signed in?" || exit 1
    fi
    click nav.library
    sleep 1
    mapfile -t PACKS < <(elements | jq -r '.elements[].id | select(startswith("library.pack."))')
    if [[ ${#PACKS[@]} -eq 0 ]]; then
        gum style --foreground 1 "no installed packs in this data directory; install one, then rerun"
        exit 1
    fi
    mapfile -t DIRS < <(find "$ITEST_DATA_DIR/instances" -maxdepth 1 -mindepth 1 -type d -printf '%p\n' | sort)

    ITEST_BOUND_PACK="$(gum choose --header "pack bound to a server" "${PACKS[@]}")"
    ITEST_BOUND_DIR="$(gum choose --header "its instance directory" "${DIRS[@]}")"
    export ITEST_BOUND_PACK ITEST_BOUND_DIR

    if printf '%s\n' "${CHOSEN[@]}" | grep -q '^30-'; then
        ITEST_UNBOUND_PACK="$(gum choose --header "pack with no server" "${PACKS[@]}")"
        ITEST_UNBOUND_DIR="$(gum choose --header "its instance directory" "${DIRS[@]}")"
        export ITEST_UNBOUND_PACK ITEST_UNBOUND_DIR
    fi
fi

for s in "${CHOSEN[@]}"; do
    [[ "$s" == 10-* ]] && continue
    if [[ "$s" == 40-* && "$(uname -s)" != Linux && "$(uname -s)" != Darwin ]]; then
        log "skipping $s: it needs POSIX permissions"
        continue
    fi
    run_scenario "$s"
done

report
