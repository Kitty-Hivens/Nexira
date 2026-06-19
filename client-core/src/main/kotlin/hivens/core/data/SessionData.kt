package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class SessionData(
    @SerialName("status") val status: AuthStatus? = null,
    @SerialName("playername") val playerName: String = "",
    @SerialName("uid") val uid: String = "",
    @SerialName("uuid") val uuid: String = "",
    @SerialName("session") val accessToken: String = "",
    @SerialName("client") val fileManifest: FileManifest? = null,

    val serverId: String? = null,
    val cachedPassword: String? = null,
    val balance: Int = 0,

    /**
     * True for an offline-play identity (no provider auth). Drives the offline
     * launch fork in `GameCommandBuilder.addSessionAuthArgs` (real offline UUID +
     * `--userType legacy`). Runtime-only: an offline session carries a blank
     * accessToken, so it is never persisted by `CredentialsManager`.
     */
    val offline: Boolean = false,
)
