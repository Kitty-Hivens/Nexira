package hivens.config

/**
 * File names inside the platform data directory.
 *
 * The directory itself is resolved at runtime by
 * [hivens.launcher.platform.PlatformPaths]; this object only owns the
 * leaf names so callers don't sprinkle "settings.json" string literals.
 */
object Storage {
    const val SETTINGS_FILE   = "settings.json"
    const val PROFILES_FILE   = "profiles.json"
    const val HASH_CACHE_FILE = "smarty_hash.cache"
}
