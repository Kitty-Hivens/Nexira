package hivens.config

/** Leaf file names inside the platform data directory (directory resolved by [hivens.launcher.platform.PlatformPaths]). */
object Storage {
    const val SETTINGS_FILE         = "settings.json"

    /** The modules' settings, one file per settings domain. Beside [SETTINGS_FILE], which they were split out of. */
    const val SETTINGS_DIR          = "settings"

    /** A person's own translations: `<tag>/<namespace>.properties` and `<tag>/language.properties`. */
    const val TRANSLATIONS_DIR      = "translations"
    const val HASH_CACHE_FILE       = "smarty_hash.cache"
    const val PACKS_FILE            = "packs.json"
    const val LAYOUT_GRAPH_FILE     = "layout-graph.json"

    /** A person's own document of the interface, there only once they have edited it. */
    const val INTERFACE_FILE        = "interface.json"

    /** One-shot marker: "boot into recovery once", written by the in-app restart action. */
    const val RECOVERY_REQUEST_FILE = "recovery.request"
    const val PRESETS_DIR           = "presets"

    /** Widget modules dropped in by the user. Scanned at boot; no install step. */
    const val WIDGETS_DIR           = "widgets"

    /** Extracted runtime helpers under the data dir (e.g. the profiler agent jar). */
    const val RUNTIME_DIR           = "runtime"
    /** Per-instance: the profiler agent's last-session metrics (the agent writes it). */
    const val PROFILER_METRICS_FILE = "profiler-metrics.json"
    /** Per-instance: the launcher's persisted derived-heap profile. */
    const val HEAP_PROFILE_FILE     = "profiler.json"
}
