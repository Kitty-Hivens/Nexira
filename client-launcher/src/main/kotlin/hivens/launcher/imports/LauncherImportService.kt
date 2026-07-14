package hivens.launcher.imports

import org.slf4j.LoggerFactory

/**
 * Aggregates the per-launcher [LauncherInstanceSource]s: the single entry point
 * the import wizard calls to answer "what can I import from launchers already on
 * this machine?". A failure in one source (a corrupt config, a permission error)
 * is logged and isolated so it cannot hide the instances the others found.
 *
 * This is the discovery half. The copy/dedup import step -- reserving a Nexira
 * instance dir, copying mods/config/saves, deduping the vanilla runtime into the
 * shared roots, and registering the resulting PackInstance -- layers on top and
 * runs through the app-scoped install path so it survives navigation.
 */
class LauncherImportService(
    private val sources: List<LauncherInstanceSource>,
) {
    private val log = LoggerFactory.getLogger(LauncherImportService::class.java)

    /** Every importable instance across all supported launchers. */
    fun discoverAll(): List<DiscoveredInstance> = sources.flatMap { source ->
        runCatching { source.discover() }
            .onFailure { log.warn("import discovery failed for {}", source.launcher, it) }
            .getOrDefault(emptyList())
    }

    /** Importable instances from a single launcher. */
    fun discover(launcher: ForeignLauncher): List<DiscoveredInstance> =
        sources.firstOrNull { it.launcher == launcher }
            ?.let { runCatching { it.discover() }.getOrDefault(emptyList()) }
            ?: emptyList()
}
