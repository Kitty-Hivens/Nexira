package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AuthStatus {
    @SerialName("OK") OK,
    @SerialName("ACTIVE") ACTIVE,
    @SerialName("LOGIN") LOGIN,
    @SerialName("BAD_LOGIN") BAD_LOGIN,
    @SerialName("NEED_2FA") NEED_2FA,
    /** Wrong 2FA code on `action=twoauth` follow-up. UI re-prompts for code (#159). */
    @SerialName("WRONG_CODE") WRONG_CODE,
    /** TWOAUTH session expired before code arrived — UI must restart full login (#159). */
    @SerialName("TWO_FACTOR_EXPIRED") TWO_FACTOR_EXPIRED,
    @SerialName("BANNED") BANNED,
    @SerialName("INTERNAL_ERROR") INTERNAL_ERROR,
    @SerialName("PASSWORD") PASSWORD
}
