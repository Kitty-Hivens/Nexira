package hivens.config

/**
 * Wire-protocol values the launcher must send to talk to SMARTYcraft.
 *
 * These constants mirror the official SMARTYcraft launcher's behaviour and
 * were recovered from the decompiled (Proguard-obfuscated) sources at
 * https://github.com/Kitty-Hivens/smrt-deco — keep that repository as the
 * source of truth when this protocol changes.
 *
 * Don't propose hiding these in `secrets.properties`: anyone who downloads
 * the upstream binary already has them. They are not project secrets.
 */
object Protocol {
    /**
     * The mimicked launcher version — sent in the dashboard handshake,
     * the `User-Agent` header, and the child JVM's `-Dminecraft.launcher.version`.
     *
     * Resolved on every read: the JVM system property `smrt.mimic.version`
     * (see [SYSTEM_PROP_MIMIC_VERSION]) wins if set, otherwise we return
     * [DEFAULT_MIMIC_LAUNCHER_VERSION]. The runtime override exists so users
     * can react to an upstream version pin faster than our release cycle —
     * pass `-Dsmrt.mimic.version=X.Y.Z` on the JVM command line and restart.
     *
     * Reading this property is safe and opt-in-free; *setting* the override is
     * marked [ExperimentalProtocolOverride] because it bypasses what was
     * validated at build time.
     */
    val MIMIC_LAUNCHER_VERSION: String
        @OptIn(ExperimentalProtocolOverride::class)
        get() = System.getProperty(SYSTEM_PROP_MIMIC_VERSION)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MIMIC_LAUNCHER_VERSION

    /** What we ship as known-good — last validated against upstream. */
    const val DEFAULT_MIMIC_LAUNCHER_VERSION = "3.6.5"

    /**
     * JVM system-property name for the experimental version override.
     * Pass on the command line: `-Dsmrt.mimic.version=3.6.6`.
     */
    @ExperimentalProtocolOverride
    const val SYSTEM_PROP_MIMIC_VERSION = "smrt.mimic.version"

    /**
     * Programmatic override — sets (or clears, if `version` is null/blank) the
     * runtime mimic version. Settings UI hooks into this once the Settings-move
     * chunk lands; for now it's reachable from tests and ad-hoc Kotlin scripts.
     */
    @ExperimentalProtocolOverride
    fun setMimicLauncherVersion(version: String?) {
        if (version.isNullOrBlank()) {
            System.clearProperty(SYSTEM_PROP_MIMIC_VERSION)
        } else {
            System.setProperty(SYSTEM_PROP_MIMIC_VERSION, version)
        }
    }

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
