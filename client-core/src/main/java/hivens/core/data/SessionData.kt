package hivens.core.data

import com.google.gson.annotations.SerializedName

data class SessionData(
    @SerializedName("status") val status: AuthStatus? = null,
    @SerializedName("playername") val playerName: String,
    @SerializedName("uuid") val uuid: String,
    @SerializedName("session") val accessToken: String,
    @SerializedName("client") val fileManifest: FileManifest? = null,

    val serverId: String? = null,
    val cachedPassword: String? = null
)
