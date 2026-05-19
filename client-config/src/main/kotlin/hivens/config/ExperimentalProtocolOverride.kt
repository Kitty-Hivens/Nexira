package hivens.config

/**
 * Opt-in marker for the override mechanism on [Protocol] wire values
 * (mainly [Protocol.MIMIC_LAUNCHER_VERSION]). Reading the resolved value
 * is opt-in-free; only the override path is guarded.
 *
 * Overrides bypass what was validated at build time, so call sites must
 * acknowledge the footgun explicitly.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Runtime override of a wire-protocol value. Bypasses what was " +
        "validated at build time -- only use when upstream has rotated the pin " +
        "and a launcher update has not shipped yet.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class ExperimentalProtocolOverride
