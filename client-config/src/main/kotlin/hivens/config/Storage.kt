package hivens.config

/** Leaf file names inside the platform data directory (directory resolved by [hivens.launcher.platform.PlatformPaths]). */
object Storage {
    const val SETTINGS_FILE         = "settings.json"
    const val PROFILES_FILE         = "profiles.json"
    const val HASH_CACHE_FILE       = "smarty_hash.cache"
    const val PROTECTED_PATHS_FILE  = "protected-paths.json"
    const val PACKS_FILE            = "packs.json"
    const val SERVERS_CACHE_FILE    = "servers-cache.json"
    const val LAYOUT_GRAPH_FILE     = "layout-graph.json"
}
