package hivens.config

/**
 * Wire-protocol values the launcher must send to talk to SMARTYcraft.
 *
 * These constants mirror the official SMARTYcraft launcher's behavior and
 * were recovered from the decompiled (Proguard-obfuscated) sources at
 * https://github.com/Kitty-Hivens/smrt-deco -- keep that repository as the
 * source of truth when this protocol changes.
 *
 * Don't propose hiding these in `secrets.properties`: anyone who downloads
 * the upstream binary already has them. They are not project secrets.
 */
object Protocol {
    /**
     * The mimicked launcher version -- sent in the dashboard handshake,
     * the `User-Agent` header, and the child JVM's `-Dminecraft.launcher.version`.
     *
     * Resolved on every read: the JVM system property `smrt.mimic.version`
     * (see [SYSTEM_PROP_MIMIC_VERSION]) wins if set, otherwise we return
     * [DEFAULT_MIMIC_LAUNCHER_VERSION]. The runtime override exists so users
     * can react to an upstream version pin faster than our release cycle --
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

    /** What we ship as known-good -- last validated against upstream. */
    const val DEFAULT_MIMIC_LAUNCHER_VERSION = "3.6.5"

    /**
     * JVM system-property name for the experimental version override.
     * Pass on the command line: `-Dsmrt.mimic.version=3.6.6`.
     */
    @ExperimentalProtocolOverride
    const val SYSTEM_PROP_MIMIC_VERSION = "smrt.mimic.version"

    /**
     * Characters permitted in the mimic launcher version override. The value
     * propagates to the HTTP `User-Agent` header (RFC 7230 token chars only),
     * the JVM system property [SYSTEM_PROP_MIMIC_VERSION], and the child game
     * process's `-Dminecraft.launcher.version=...` argv. Any of those rejects
     * non-ASCII; OkHttp specifically throws `IllegalArgumentException:
     * "Unexpected char 0xNNN in User-Agent value"` and the user sees it as
     * "Network Error: ..." on the dashboard. Restricting the charset at the
     * boundary keeps a user accidentally typing in a Cyrillic keyboard layout
     * (or pasting a string with hidden Unicode) from breaking login.
     */
    @ExperimentalProtocolOverride
    val MIMIC_VERSION_ALLOWED_CHARS: Set<Char> =
        ('A'..'Z').toSet() + ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('.', '-', '_')

    /**
     * Programmatic override -- sets (or clears, if `version` is null/blank) the
     * runtime mimic version. Called by `Main.kt` on every startup with the
     * persisted `SettingsData.mimicVersionOverride` (so the value survives
     * launcher restart) and by `SettingsScreen` on save (so the change takes
     * effect on the very next protocol call without waiting for restart).
     *
     * Defensively rejects values containing characters outside
     * [MIMIC_VERSION_ALLOWED_CHARS] by clearing the override (falling back to
     * [DEFAULT_MIMIC_LAUNCHER_VERSION]); the UI surface filters input on the
     * way in, but a hand-edited or older-version-corrupted persistence file
     * could still feed us garbage on cold start.
     */
    @ExperimentalProtocolOverride
    fun setMimicLauncherVersion(version: String?) {
        val safe = version?.takeIf { it.isNotBlank() && it.all { c -> c in MIMIC_VERSION_ALLOWED_CHARS } }
        if (safe == null) {
            System.clearProperty(SYSTEM_PROP_MIMIC_VERSION)
        } else {
            System.setProperty(SYSTEM_PROP_MIMIC_VERSION, safe)
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

    /** Cargo-cult field in the auth payload -- server requires presence, content is ignored. */
    const val DEFAULT_JAR = "smartycraft.jar"

    /** MD5 of an empty string. Server requires the `rtCheckSum` field; it isn't actually validated. */
    const val DEFAULT_CSUM = "d41d8cd98f00b204e9800998ecf8427e"
}
