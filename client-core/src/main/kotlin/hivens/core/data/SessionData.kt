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
     * Clan tag from the SmartyCraft login response; null = not in a clan.
     * [clanResolved] separates "known to have no clan" from "session predates
     * this field" (old persisted credentials decode to the defaults) -- the
     * cape capability gate must fail open on the latter.
     */
    val clan: String? = null,
    val clanResolved: Boolean = false,

    /**
     * True for an offline-play identity (no provider auth). Drives the offline
     * launch fork in `GameCommandBuilder.addSessionAuthArgs` (real offline UUID +
     * `--userType legacy`). Runtime-only: an offline session carries a blank
     * accessToken, so it is never persisted by `CredentialsManager`.
     */
    val offline: Boolean = false,

    /**
     * MSA refresh token for silent re-auth (the `offline_access` scope). Transient
     * secret like [cachedPassword]: persisted to the vault by the credential store,
     * never written to credentials.json. Null for non-Microsoft sessions.
     */
    val refreshToken: String? = null,
) {
    // Redact the three secrets (accessToken / cachedPassword / refreshToken) so a
    // stray log line or an exception carrying the session never prints them. The
    // serializer and equals/hashCode still use every field -- only the
    // human-readable form masks them.
    override fun toString(): String =
        "SessionData(status=$status, playerName='$playerName', uid='$uid', uuid='$uuid', " +
        "accessToken=${accessToken.redactedSecret()}, fileManifest=$fileManifest, serverId=$serverId, " +
        "cachedPassword=${cachedPassword.redactedSecret()}, balance=$balance, clan=$clan, " +
        "clanResolved=$clanResolved, offline=$offline, refreshToken=${refreshToken.redactedSecret()})"
}

// Masks a secret's value while still telling apart null / empty / set-with-length,
// which is all a log needs and never the secret itself.
private fun String?.redactedSecret(): String = when {
    this == null -> "null"
    isEmpty()    -> "\"\""
    else         -> "<redacted:$length>"
}
