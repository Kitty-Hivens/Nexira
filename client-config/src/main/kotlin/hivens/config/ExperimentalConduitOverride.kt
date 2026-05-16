package hivens.config

/**
 * Marks API surface that lets the user override the resolved network
 * configuration of the SmartyCraft channel -- base URL, proxy host/port,
 * launcher hash, etc. -- at runtime via system properties or config file.
 *
 * Wraps the runtime override path so that bypassing the build-time-baked
 * defaults is **opt-in only**. Legitimate use cases:
 *
 *   - Mirror development -- point at a local mirror server while testing
 *     `aura.conduit.baseurl=http://localhost:8080`
 *   - Censored regions where users have configured a local SOCKS proxy
 *     different from the upstream's (advanced operator territory)
 *   - Test / staging environments
 *
 * Anything not marked this annotation reads through to the value validated
 * at build time. Pass `-Daura.conduit.baseurl=https://mirror.example.com`
 * on the JVM command line -- Aura honors it; if the override is wrong,
 * launcher fails fast at first network call instead of silently going to
 * the wrong server.
 *
 * Same pattern as [ExperimentalProtocolOverride] (which gates the
 * MIMIC_LAUNCHER_VERSION override) -- kept consistent so users wiring
 * Aura into a Mirror setup learn one annotation and apply it twice.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Conduit network configuration override -- bypasses build-time defaults. " +
            "Use only for Mirror development, censored-region operator workarounds, " +
            "or test/staging. See ServerProtocolConfig.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class ExperimentalConduitOverride
