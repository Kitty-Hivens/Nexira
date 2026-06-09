package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * UX-layer auth status. Derived from wire `ProtocolStatus` by the
 * SmartyCraft auth provider's status mapping; unknown wire values collapse
 * into [INTERNAL_ERROR] (mirrors upstream `bf.java`; see
 * `docs/dev/smartycraft-v1-protocol.md`).
 *
 * The server also emits `LOGIN` on the wire which is not modeled here;
 * UI routes it through the generic error path until a concrete
 * user-not-found screen lands.
 */
@Serializable
enum class AuthStatus {
    @SerialName("OK") OK,
    @SerialName("ACTIVE") ACTIVE,
    @SerialName("BAD_LOGIN") BAD_LOGIN,
    @SerialName("NEED_2FA") NEED_2FA,
    /** Wrong 2FA code on `twoauth` follow-up; UI re-prompts. */
    @SerialName("WRONG_CODE") WRONG_CODE,
    /** TWOAUTH session expired before code arrived; UI must restart full login. */
    @SerialName("TWO_FACTOR_EXPIRED") TWO_FACTOR_EXPIRED,
    @SerialName("INTERNAL_ERROR") INTERNAL_ERROR,
    @SerialName("PASSWORD") PASSWORD,
}
