#!/usr/bin/env bash
# A foreign mod that refuses to be deleted costs the launch its session.
#
# Making the file read-only is exactly how the rule was bypassed once: the
# delete failed, the launcher shrugged, and the launch went out with a real
# token and a foreign jar loaded. The pass condition is not "the file is
# gone" -- it cannot be -- but "the game was handed the offline identity".
#
# POSIX only. Skipped elsewhere by the runner.

NAME="undeletable foreign mod: launch drops to offline"

run() {
    local dir="$ITEST_BOUND_DIR"
    local cheat="$dir/mods/zz-itest-locked.jar"

    plant "$cheat"
    chmod a-w "$dir/mods"
    # Restore even if a check below returns early -- an instance left with a
    # read-only mods/ breaks every later sync in a way that is hard to trace.
    trap 'chmod u+w "$dir/mods" 2>/dev/null; rm -f "$cheat" 2>/dev/null' RETURN
    log "planted a locked probe and made mods/ read-only"

    open_pack "$ITEST_BOUND_PACK" || { fail "could not open the pack"; return; }
    wait_element packDetail.play || { fail "play is not available"; return; }
    click packDetail.play

    local reached
    reached="$(ITEST_WAIT=60 wait_any_element launch.twoFactor.code 2>/dev/null || true)"
    if [[ "$reached" == "launch.twoFactor.code" ]]; then
        answer_two_factor launch.twoFactor "launch" || { fail "the launch code was not accepted"; return; }
    fi

    local pid
    if pid="$(wait_game "$dir" 300)"; then
        assert_file_exists "$cheat" "the locked probe is still there (it could not be removed)"
        assert_equals "$(game_user_type "$pid")" "legacy" \
            "an instance that refused the sweep launched without a session"
    else
        fail "the game never started, so the session it got is unknown"
    fi

    click packDetail.abort || true
}
