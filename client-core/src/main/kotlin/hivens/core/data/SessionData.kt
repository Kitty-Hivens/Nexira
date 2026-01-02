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
    val balance: Int = 0
)
