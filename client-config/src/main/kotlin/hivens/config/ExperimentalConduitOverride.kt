package hivens.config

/**
 * Opt-in marker for runtime overrides of the SmartyCraft channel
 * configuration (base URL, proxy host/port, launcher hash) via system
 * properties or config file. Bypasses build-time defaults; misconfigured
 * overrides fail fast at the first network call.
 *
 * Legitimate uses: Mirror development (`-Daura.conduit.baseurl=...`),
 * censored-region operator workarounds, test / staging environments.
 *
 * @see ExperimentalProtocolOverride for the parallel knob over wire-protocol values.
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
