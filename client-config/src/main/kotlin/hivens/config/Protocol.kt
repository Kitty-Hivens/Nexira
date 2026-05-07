package hivens.config

/**
 * Wire-protocol values the launcher must send to talk to SMARTYcraft.
 *
 * These constants mirror the official SMARTYcraft launcher's behaviour and
 * were recovered from the decompiled (Proguard-obfuscated) sources at
 * https://github.com/Kitty-Hivens/smrt-deco — keep that repository as the
 * source of truth when this protocol changes. The repo is archived (April
 * 2026), so any future drift requires a fresh decompile.
 *
 * Don't propose hiding these in `secrets.properties`: anyone who downloads
 * the upstream binary already has them. They are not project secrets.
 */
object Protocol {
    /**
     * The version we *claim to be* in the dashboard request and User-Agent.
     * This is the official launcher's version we impersonate, not [Branding.VERSION].
     */
    const val MIMIC_LAUNCHER_VERSION = "3.6.3"

    /** Default server id for cold-start logins (when no profile.lastServerId is set). */
    const val DEFAULT_SERVER_ID = "Industrial"

    /**
     * MD5 of the official `smartycraft.jar` at the time this constant was
     * baked. Used as the initial value of the launcher-hash cache; the
     * server may reject it with `status: "UPDATE"` and force a refresh.
     */
    const val DEFAULT_LAUNCHER_HASH = "0714d6ea824454d0af31a02373eef703"

    /** AES key derivation salt for the session-token round-trip in [hivens.core.api.AuthService]. */
    const val AUTH_SALT = "sdgsdfhgosd8dfrg"

    /** Cargo-cult field in the auth payload — server requires presence, content is ignored. */
    const val DEFAULT_JAR = "smartycraft.jar"

    /** MD5 of an empty string. Server requires the `rtCheckSum` field; it isn't actually validated. */
    const val DEFAULT_CSUM = "d41d8cd98f00b204e9800998ecf8427e"
}
