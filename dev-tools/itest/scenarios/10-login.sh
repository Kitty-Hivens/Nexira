#!/usr/bin/env bash
# Sign in through the login form and confirm the account landed.
#
# The second factor is an assertion here, not an accident: an account the
# operator declared as gated must be asked for a code, and one declared as
# plain must not be. Both directions have broken before.

NAME="login"

run() {
    # The form is not a screen of its own any more: it lives in the profile's
    # per-provider section, and the app opens on Home whether signed in or not.
    click nav.profile
    wait_element login.submit || { fail "the login form never came up (screen: $(screen))"; return; }

    if [[ "$ITEST_ACCOUNT" == "offline" ]]; then
        set_field login.username "$ITEST_USER"
        click login.playOffline
        wait_element account.logout && ok "offline session started" || fail "offline session never started"
        return
    fi

    set_field login.username "$ITEST_USER"
    set_field login.password "$ITEST_PASS"
    click login.submit

    local reached
    reached="$(wait_any_element login.twoFactor.code account.logout)" || {
        fail "neither a code prompt nor a session after submit (screen: $(screen))"
        return
    }

    if [[ "$reached" == "login.twoFactor.code" ]]; then
        if [[ "$ITEST_ACCOUNT" != "sc-2fa" ]]; then
            fail "a code was demanded for an account declared without a second factor"
        else
            ok "the login form asked for a code"
        fi
        answer_two_factor login.twoFactor "login" || { fail "the code was not accepted"; return; }
        wait_element account.logout && ok "signed in after the code" || fail "no session after the code"
    else
        if [[ "$ITEST_ACCOUNT" == "sc-2fa" ]]; then
            fail "an account with a second factor signed in without being asked"
        else
            ok "signed in without a code"
        fi
    fi
}
