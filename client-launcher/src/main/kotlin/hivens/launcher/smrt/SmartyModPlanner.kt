package hivens.launcher.smrt

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.FileManifest
import hivens.core.data.SettingsData
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Turns the two Smarty settings + a server's manifest into a concrete sync
 * plan: which Smarty jars to strip, which open-smrt-network jar to inject, and
 * whether to run strict mod verification. Shared by both sync entry points
 * ([hivens.launcher.launch.LauncherController] and
 * [hivens.launcher.AutoSyncService]) so foreground launch and background sync
 * apply the same swap.
 *
 * Swap ON is authoritative: the proprietary Smarty jar is stripped from the
 * sync set whether or not the helper resolves this launch, so a transient
 * resolver failure can never re-admit the surveillance mod. Fetching the
 * open-smrt-network jar is best-effort on top -- a launch that can't refresh it
 * relies on the on-disk copy (protected from strict verification by
 * [Plan.helperKeepGlobs]). Strict verification is independent of the helper.
 */
class SmartyModPlanner(
    /**
     * Resolves the open-smrt-network helper for an MC version, or null when none
     * is available. A function rather than the concrete [OpenSmrtHelperResolver]
     * so the planner is unit-testable without a live network (the resolver is a
     * final class and this codebase runs mockk without the inline-mock agent).
     */
    private val resolveHelper: suspend (mcVersion: String) -> OpenSmrtHelperResolver.Resolved?,
    private val manifestProcessor: IManifestProcessorService,
) {
    private val log = LoggerFactory.getLogger(SmartyModPlanner::class.java)

    /**
     * @param ignoredAddon Smarty jar basenames to remove from the sync set
     *        (composed with the optional-mod ignore set by the caller).
     * @param injectJar local open-smrt-network jar to copy into `mods/`, or null
     *        when the helper couldn't be fetched this launch.
     * @param strict whether to delete every jar in `mods/` absent from the manifest.
     * @param helperKeepGlobs jar-name globs strict verification must always keep
     *        (the open-smrt helper), even on a launch where [injectJar] is null.
     *        Non-empty only while the swap is on, so turning the swap off lets the
     *        leftover helper be pruned and the upstream mod return.
     */
    data class Plan(
        val ignoredAddon: Set<String>,
        val injectJar: Path?,
        val strict: Boolean,
        val helperKeepGlobs: List<String>,
    )

    suspend fun plan(server: ServerProfile, manifest: FileManifest?, settings: SettingsData): Plan {
        val strict = settings.strictModVerification
        if (!settings.useOpenSmrtHelper) {
            return Plan(emptySet(), null, strict, emptyList())
        }

        // Best-effort: may be null on a network hiccup, an empty descriptor, or a
        // hash mismatch. We strip Smarty regardless -- never re-admit it.
        val resolved = resolveHelper(server.version)
        val smartyGlobs = resolved?.smartyNames?.takeIf { it.isNotEmpty() } ?: DEFAULT_SMARTY_GLOBS
        val ignored = manifest?.let { matchingSmartyNames(it, smartyGlobs) } ?: emptySet()

        // No proprietary Smarty in this manifest -> nothing to swap, so stay fully
        // inert. The swap targets a raw SmartyCraft server, whose sync ships the
        // Smarty jar this matches. Mirror/Hivens packs already bundle
        // open-smrt-network and carry no Smarty; injecting the helper on top of
        // their own copy would load the same coremod twice.
        if (ignored.isEmpty()) {
            log.info("open-smrt helper: no Smarty jar in {} manifest -- swap inert", server.name)
            return Plan(emptySet(), null, strict, emptyList())
        }

        if (resolved == null) {
            log.warn(
                "open-smrt helper not fetched for {} (MC {}); Smarty stripped anyway, " +
                    "relying on the on-disk helper if present",
                server.name, server.version,
            )
        }
        // Keep ONLY this version's exact helper filename -- a wildcard would also
        // shield a stale sibling-version jar (two coremods loaded) or an unrelated
        // open-smrt-network-*.jar a hostile manifest dropped in.
        return Plan(ignored, resolved?.jar, strict, listOf(OpenSmrtHelperResolver.helperFileName(server.version)))
    }

    /** Manifest jar basenames matching any of the [smartyNames] globs. */
    private fun matchingSmartyNames(manifest: FileManifest, smartyNames: List<String>): Set<String> {
        val patterns = smartyNames.map { globToRegex(it) }
        return manifestProcessor.flattenManifest(manifest).keys
            .map { it.substringAfterLast('/') }
            .filter { name -> patterns.any { it.matches(name) } }
            .toSet()
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    companion object {
        /**
         * Fallback Smarty matcher when the descriptor is empty/unreachable. A
         * broad prefix catch (not `Smarty-*`) on purpose: missing the surveillance
         * jar (it keeps spying) is worse than stripping a same-prefixed third-party
         * mod. Per-version exact names can override via the descriptor's
         * `smarty_names` once the real upstream filenames are verified.
         */
        private val DEFAULT_SMARTY_GLOBS = listOf("Smarty*.jar")
    }
}
