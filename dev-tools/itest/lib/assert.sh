#!/usr/bin/env bash
# Assertions and the per-run tally.
#
# A scenario reports through these and never exits on its own: one failed
# check should not cost the remaining checks of the same scenario, because
# the combination is usually what explains the failure ("the cheat jar
# survived AND the launch still got a token" is a different bug from either
# half alone).

# Assertion counters. ITEST_PASS is this file's, and nothing else may take it:
# run.sh once stored the operator's password here, which made the first passing
# assertion do arithmetic on a string and abort the run under `set -u` -- with
# the scenario teardowns unrun, so a chmod-ed mods/ stayed read-only.
ITEST_PASS=0
ITEST_FAIL=0
ITEST_LINES=()
ITEST_CURRENT="run"

_record() {
    local mark="$1" text="$2"
    ITEST_LINES+=("$mark|$ITEST_CURRENT|$text")
}

ok() {
    ITEST_PASS=$(( ITEST_PASS + 1 ))
    _record "ok" "$1"
    log "ok   $1"
}

fail() {
    ITEST_FAIL=$(( ITEST_FAIL + 1 ))
    _record "FAIL" "$1"
    log "FAIL $1"
}

assert_file_exists() {
    if [[ -e "$1" ]]; then ok "${2:-$1 is present}"; else fail "${2:-$1 is missing}"; fi
}

assert_file_absent() {
    if [[ -e "$1" ]]; then fail "${2:-$1 survived}"; else ok "${2:-$1 is gone}"; fi
}

assert_equals() {
    if [[ "$1" == "$2" ]]; then ok "${3:-got $1}"; else fail "${3:-expected '$2', got '$1'}"; fi
}

assert_element() {
    if has_element "$1"; then ok "${2:-$1 is on screen}"; else fail "${2:-$1 never appeared}"; fi
}

assert_no_element() {
    if has_element "$1"; then fail "${2:-$1 appeared}"; else ok "${2:-$1 stayed away}"; fi
}

report() {
    local mark scenario text last=""
    printf '\n'
    if [[ ${#ITEST_LINES[@]} -eq 0 ]]; then
        printf 'nothing ran\n'
        return 1
    fi
    for line in "${ITEST_LINES[@]}"; do
        mark="${line%%|*}"; line="${line#*|}"
        scenario="${line%%|*}"; text="${line#*|}"
        if [[ "$scenario" != "$last" ]]; then
            printf '\n%s\n' "$scenario"
            last="$scenario"
        fi
        printf '  %-4s %s\n' "$mark" "$text"
    done
    printf '\n%d passed, %d failed\n' "$ITEST_PASS" "$ITEST_FAIL"
    (( ITEST_FAIL == 0 ))
}
