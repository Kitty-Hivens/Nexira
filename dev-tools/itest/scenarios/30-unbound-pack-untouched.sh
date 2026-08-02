#!/usr/bin/env bash
# A pack that declares no server keeps whatever the user put in it.
#
# This is the half that shipped broken: the strict rule went out applying to
# every pack, so a solo pack lost the mods its owner added on purpose. The
# check has to wait until the launch is demonstrably past the point where a
# sweep would have run -- the game process starting is that point.

NAME="unbound pack: nothing is swept"

run() {
    local dir="$ITEST_UNBOUND_DIR"
    local own="$dir/mods/zz-itest-own.jar"

    plant "$own"
    log "planted a user mod in $dir"

    open_pack "$ITEST_UNBOUND_PACK" || { fail "could not open the pack"; return; }
    wait_element packDetail.play || { fail "play is not available"; return; }
    click packDetail.play

    local pid
    if pid="$(wait_game "$dir" 300)"; then
        assert_file_exists "$own" "a user mod survived an unbound launch"
    else
        fail "the game never started, so nothing can be concluded about the sweep"
    fi

    click packDetail.abort || true
    rm -f "$own"
}
