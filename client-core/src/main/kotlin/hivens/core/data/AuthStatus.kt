package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * UX-layer auth statuses the launcher exposes to the UI. Derived from the
 * wire `ProtocolStatus` via `AuthService.mapStatus`; unknown wire statuses
 * fold into [INTERNAL_ERROR] (matching the upstream `bf.java` enum surface
 * documented in `docs/dev/smartycraft-v1-protocol.md`).
 *
 * @Serializable retained for compat with any consumer that may serialise
 * `SessionData`; the launcher itself constructs `SessionData` with
 * `status = null` in `CredentialsManager.load` so this enum is never read
 * back from disk JSON in the normal flow.
 *
 * Coverage:
 *   * Wired into UI:   OK, NEED_2FA, WRONG_CODE, TWO_FACTOR_EXPIRED,
 *                      BAD_LOGIN, INTERNAL_ERROR, PASSWORD, ACTIVE.
 *   * Wire-only echo:  LOGIN. Server emits it; UI funnels into a generic
 *                      error path until a concrete "user not found" screen
 *                      is in scope.
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
    @SerialName("INTERNAL_ERROR") INTERNAL_ERROR,
    @SerialName("PASSWORD") PASSWORD
}
