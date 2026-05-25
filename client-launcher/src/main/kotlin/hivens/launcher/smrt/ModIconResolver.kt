package hivens.launcher.smrt

import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Picks the best icon URL for a mod entry, with fallbacks for missing
 * display metadata.
 *
 * Order:
 *  1. `display.iconUrl` if present (mirror-served direct URL).
 *  2. Modrinth source: one API lookup per project_id via the injected
 *     [resolveProjectIcon] lambda. Results cached in memory for the
 *     life of the resolver -- a Library PackDetail rendering 50 rows
 *     of mods from 10 distinct Modrinth projects fires at most 10
 *     network calls.
 *  3. Returns null. UI layer renders the letter-avatar fallback.
 *
 * The lambda indirection (vs. holding a [SmrtPackClient] directly)
 * keeps tests free of HTTP mocking -- tests pass a stub lambda that
 * returns whatever URLs they want.
 */
class ModIconResolver(
    private val resolveProjectIcon: suspend (projectId: String) -> String?,
) {

    private val log = LoggerFactory.getLogger(ModIconResolver::class.java)
    private val cache = mutableMapOf<String, String?>()
    private val mutex = Mutex()

    suspend fun resolve(mod: SmrtModEntry): String? {
        // 1. Direct URL on the manifest entry.
        mod.display?.iconUrl?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. Modrinth source -> API lookup, cached per project_id.
        val source = mod.source
        if (source is SmrtSource.Modrinth) {
            val projectId = source.projectId

            // First read under the mutex. The key-presence test is
            // separate from the value check because we cache nulls --
            // a project genuinely has no icon AND a failed lookup are
            // both "no icon"; we don't retry either case on re-render.
            mutex.withLock {
                if (cache.containsKey(projectId)) return cache[projectId]
            }

            val resolved = try {
                resolveProjectIcon(projectId)
            } catch (e: Exception) {
                log.warn("Modrinth project icon lookup failed for {}: {}", projectId, e.message)
                null
            }
            mutex.withLock { cache[projectId] = resolved }
            return resolved
        }

        // 3. smrt_cache / smrt_static without an explicit iconUrl: nothing to do.
        return null
    }
}
