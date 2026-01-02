package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AuthStatus {
    @SerialName("OK") OK,
    @SerialName("LOGIN") LOGIN,
    @SerialName("BAD_LOGIN") BAD_LOGIN,
    @SerialName("NEED_2FA") NEED_2FA,
    @SerialName("BANNED") BANNED,
    @SerialName("INTERNAL_ERROR") INTERNAL_ERROR,
    @SerialName("PASSWORD") PASSWORD
}
