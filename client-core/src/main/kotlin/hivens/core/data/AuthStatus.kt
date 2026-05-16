package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Auth statuses the SmartyCraft server can send back on `action=login`
 * (and a couple of follow-ups). All values are wire-protocol -- even the
 * ones with no Kotlin reference must stay declared so kotlinx.serialization
 * can deserialize them. Removing one because "it's not used in Kotlin code"
 * means the launcher crashes with `SerializationException` the moment the
 * server returns that value.
 *
 * Coverage status (audit pending -- see issue tracker):
 *   * Wired into UI:   OK, NEED_2FA, WRONG_CODE, TWO_FACTOR_EXPIRED,
 *                      BAD_LOGIN, INTERNAL_ERROR, PASSWORD, ACTIVE.
 *   * Wire-only:       LOGIN, BANNED. Server emits them; UI swallows them
 *                      into a generic error path. Concrete UX (e.g. a
 *                      "you are banned, contact moderation" screen for
 *                      BANNED) is the work tracked separately.
 */
@Serializable
enum class AuthStatus {
    @SerialName("OK") OK,
    @SerialName("ACTIVE") ACTIVE,
    @SerialName("LOGIN") LOGIN,
    @SerialName("BAD_LOGIN") BAD_LOGIN,
    @SerialName("NEED_2FA") NEED_2FA,
    /** Wrong 2FA code on `action=twoauth` follow-up. UI re-prompts for code (#159). */
    @SerialName("WRONG_CODE") WRONG_CODE,
    /** TWOAUTH session expired before code arrived -- UI must restart full login (#159). */
    @SerialName("TWO_FACTOR_EXPIRED") TWO_FACTOR_EXPIRED,
    @SerialName("BANNED") BANNED,
    @SerialName("INTERNAL_ERROR") INTERNAL_ERROR,
    @SerialName("PASSWORD") PASSWORD
}
