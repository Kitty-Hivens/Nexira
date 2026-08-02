#!/usr/bin/env bash
# A server-bound pack loses foreign mods and keeps everything else.
#
# The two halves are one scenario on purpose. "Foreign jar removed" alone
# passes just as well for a launcher that wipes the instance, which is the
# regression this exists to catch: loader caches, configs, resource packs
# and plain files under mods/ are none of the roster's business.

NAME="bound pack: foreign mods swept, the rest untouched"

run() {
    local dir="$ITEST_BOUND_DIR"
    local cheat="$dir/mods/zz-itest-cheat.jar"
    local note="$dir/mods/zz-itest-note.txt"
    local cache="$dir/mods/.connector/zz-itest-cache.jar"
    local rp="$dir/resourcepacks/zz-itest.zip"
    local cfg="$dir/config/zz-itest.cfg"

    plant "$cheat"; plant "$note"; plant "$cache"; plant "$rp"; plant "$cfg"
    log "planted 5 probes in $dir"

    open_pack "$ITEST_BOUND_PACK" || { fail "could not open the pack"; return; }
    wait_element packDetail.play || { fail "play is not available (not signed in?)"; return; }
    click packDetail.play

    # The prompt and the sweep race: a gated account is asked for a code
    # before the launch gets far enough to touch the instance, but an
    # already-minted session goes straight through.
    local reached
    reached="$(ITEST_WAIT=60 wait_any_element launch.twoFactor.code 2>/dev/null || true)"
    if [[ "$reached" == "launch.twoFactor.code" ]]; then
        ok "the launch asked for a code of its own"
        answer_two_factor launch.twoFactor "launch" || { fail "the launch code was not accepted"; return; }
    fi

    if wait_file_gone "$cheat" 180; then
        ok "the foreign jar was removed"
    else
        fail "the foreign jar survived a bound-pack launch"
    fi

    assert_file_exists "$note"  "a non-archive file under mods/ was left alone"
    assert_file_exists "$cache" "the loader cache under mods/.connector was left alone"
    assert_file_exists "$rp"    "resourcepacks/ was left alone"
    assert_file_exists "$cfg"   "config/ was left alone"

    if [[ "$ITEST_ACCOUNT" != "offline" ]]; then
        local pid
        if pid="$(wait_game "$dir" 300)"; then
            assert_equals "$(game_user_type "$pid")" "mojang" \
                "a verified bound pack launched with a real session"
        else
            fail "the game never started, so the session it got is unknown"
        fi
    fi

    click packDetail.abort || true
    rm -f "$cheat" "$note" "$cache" "$rp" "$cfg"
    rmdir "$dir/mods/.connector" 2>/dev/null || true
}
