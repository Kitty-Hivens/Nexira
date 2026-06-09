package hivens.config

/**
 * Wire-protocol constants for SMARTYcraft. Recovered from the decompiled
 * official launcher at https://github.com/Kitty-Hivens/smrt-deco -- treat
 * that repository as the source of truth on protocol drift.
 *
 * Not project secrets: anyone with the upstream binary already has them.
 */
object Protocol {
    /**
     * Mimicked launcher version. Sent in the dashboard handshake, the
     * HTTP `User-Agent`, and the child game JVM's
     * `-Dminecraft.launcher.version`. Resolved on every read:
     * [SYSTEM_PROP_MIMIC_VERSION] wins if set, else
     * [DEFAULT_MIMIC_LAUNCHER_VERSION]. Direct `System.setProperty` /
     * CLI `-Dsmrt.mimic.version=...` bypasses [MIMIC_VERSION_ALLOWED_CHARS]
     * validation; [setMimicLauncherVersion] is the sanitizing path for
     * persisted overrides.
     */
    val MIMIC_LAUNCHER_VERSION: String
        @OptIn(ExperimentalProtocolOverride::class)
        get() = System.getProperty(SYSTEM_PROP_MIMIC_VERSION)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MIMIC_LAUNCHER_VERSION

    /** Shipped known-good mimic version, last validated against upstream. */
    const val DEFAULT_MIMIC_LAUNCHER_VERSION = "3.6.5"

    /** JVM property name for the runtime mimic override (`-Dsmrt.mimic.version=X.Y.Z`). */
    @ExperimentalProtocolOverride
    const val SYSTEM_PROP_MIMIC_VERSION = "smrt.mimic.version"

    /**
     * ASCII charset permitted in the mimic-version override. The value
     * propagates to RFC 7230 `User-Agent` token chars + the JVM property
     * + child argv; non-ASCII (Cyrillic input, hidden Unicode) breaks
     * OkHttp's header validation with `IllegalArgumentException:
     * Unexpected char 0xNNN`. Filter at the boundary.
     */
    @ExperimentalProtocolOverride
    val MIMIC_VERSION_ALLOWED_CHARS: Set<Char> =
        ('A'..'Z').toSet() + ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('.', '-', '_')

    /**
     * Sets or clears the runtime mimic-version override. Values containing
     * characters outside [MIMIC_VERSION_ALLOWED_CHARS] clear the override
     * (fall back to shipped default) -- defends against hand-edited
     * persistence files. UI input is filtered separately.
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

    /** Default server id for cold-start logins (no `profile.lastServerId`). */
    const val DEFAULT_SERVER_ID = "Industrial"

    /**
     * Initial value for the launcher-hash cache; MD5 of `smartycraft.jar`
     * at the time this constant was last validated. Upstream may reject
     * with `status: "UPDATE"` and force a refresh.
     */
    const val DEFAULT_LAUNCHER_HASH = "0714d6ea824454d0af31a02373eef703"

    /** AES key derivation salt for the SmartyCraft session-token round-trip. */
    const val AUTH_SALT = "sdgsdfhgosd8dfrg"

    /** Cargo-cult field in auth payload -- server requires presence, content ignored. */
    const val DEFAULT_JAR = "smartycraft.jar"

    /** MD5 of empty string. Server requires `rtCheckSum`, doesn't validate. */
    const val DEFAULT_CSUM = "d41d8cd98f00b204e9800998ecf8427e"
}
