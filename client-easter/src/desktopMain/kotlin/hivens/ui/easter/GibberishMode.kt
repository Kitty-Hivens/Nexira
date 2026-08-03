package hivens.ui.easter

/**
 * Selector for the corruption style applied by
 * [AprilFoolsLifecycle.maybeGibberish]. Lives outside the chaos engine itself
 * (in `desktopMain/easter/` rather than the optional `desktopAprilFoolsMain/`
 * source set added in B3 sub-batch 12.2) so callers can name the mode without
 * pulling in the heavy easter-egg implementation.
 */
enum class GibberishMode {
    LOREM,
    ZALGO,
    FAKE_VER,
    JARGON,
    REVERSED,
    SCRAMBLED,
}
