package hivens.core.data

import java.util.UUID

/**
 * Offline-play identity, matching vanilla Minecraft's offline UUID derivation so
 * a world or whitelist created here lines up with the same name played in any
 * other launcher's offline mode.
 */
object OfflineIdentity {

    /**
     * The offline UUID Minecraft derives for [name]:
     * `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")` (a version-3 name UUID over
     * the UTF-8 bytes). This is the vanilla `Util.createOfflineUUID` scheme.
     */
    fun uuidFor(name: String): UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

    /** [uuidFor] as the dash-free 32-hex form the game args and SessionData use. */
    fun dashlessUuidFor(name: String): String =
        uuidFor(name).toString().replace("-", "")
}
