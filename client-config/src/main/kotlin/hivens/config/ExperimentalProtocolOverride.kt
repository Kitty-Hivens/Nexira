package hivens.config

/**
 * Marks knobs that override the wire-protocol values [Protocol] ships with --
 * mainly the mimicked SMARTYlauncher version. Reading the resolved value via
 * [Protocol.MIMIC_LAUNCHER_VERSION] is unaffected and stays opt-in-free; the
 * marker only guards the *override mechanism* itself.
 *
 * ── Why opt-in ────────────────────────────────────────────────────────────────
 * The default values in [Protocol] are validated against a specific upstream
 * release. Overriding them at runtime means we send something on the wire
 * that nobody tested. This is intentional -- it lets users react to upstream
 * version pinning faster than our release cycle -- but it's a footgun, so
 * every call site that flips the knob has to acknowledge it explicitly.
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
