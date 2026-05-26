package hivens.core.data

/**
 * One entry from `<instanceDir>/servers.dat`. Mirrors the vanilla
 * "Multiplayer -> Add Server" list the player built up across
 * sessions. Surfaced in Library PackDetail's Worlds tab as a
 * "Server history" companion list to the local worlds.
 *
 * Vanilla servers.dat is **not** GZIP-compressed (unlike level.dat);
 * the reader chooses the right path based on which file it sees.
 */
data class MultiplayerServerEntry(
    /** Display name the player set. Falls back to [ip] when blank. */
    val name: String,
    /** `host` or `host:port`. Vanilla shows it as-typed in the multiplayer list. */
    val ip: String,
    /**
     * Server icon. Base64-encoded PNG when the server returned one
     * on first connect AND the client decided to cache it. Null on
     * never-joined entries.
     */
    val iconBase64: String?,
    /**
     * `acceptTextures` byte: 0 = "Prompt", 1 = "Enabled", 2 = "Disabled".
     * Vanilla 3-state toggle for server-driven resource packs.
     */
    val acceptTexturesMode: Byte?,
    /**
     * `hidden` byte: true when the entry was hidden via the vanilla
     * multiplayer UI's hide flow. The launcher still surfaces these
     * in Library (they're real history) but renders them de-emphasised.
     */
    val hidden: Boolean,
)
