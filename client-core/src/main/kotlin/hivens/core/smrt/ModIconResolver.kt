package hivens.core.smrt

import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
 *  3. Any other source: the manifest's own sha1 through the same
 *     hash lookup [resolveByFile] uses, so a mod published on
 *     CurseForge and on Modrinth alike is found without holding a
 *     CurseForge key.
 *  4. Returns null. UI layer renders the letter-avatar fallback.
 *
 * The lambda indirection (vs. holding a [SmrtPackClient] directly)
 * keeps tests free of HTTP mocking -- tests pass a stub lambda that
 * returns whatever URLs they want.
 *
 * [resolveByFile] is the origin-agnostic entry point used by the Library
 * Content tab: an installed jar carries no manifest project_id, so the icon
 * is found by hashing the file (SHA1) and asking Modrinth which version owns
 * that hash -> project -> icon. [resolveIconByHash] is the injected step
 * (hash -> icon URL); results are cached per hash the same way project icons
 * are cached per project_id, so a 50-mod list resolves each unique file once.
 */
class ModIconResolver(
    // Defaulted + listed first so the bare-lambda form `ModIconResolver { id -> ... }`
    // still binds to [resolveProjectIcon] (Kotlin's trailing lambda maps to the last
    // parameter). Both real call sites use named arguments.
    private val resolveIconByHash: suspend (sha1: String) -> String? = { null },
    private val resolveProjectIcon: suspend (projectId: String) -> String?,
) {

    private val log = LoggerFactory.getLogger(ModIconResolver::class.java)
    private val cache = mutableMapOf<String, String?>()
    private val hashCache = mutableMapOf<String, String?>()
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

        // 3. Every other source, by the file's own hash.
        //
        // Asking CurseForge what a project looks like needs a CurseForge key, which
        // this launcher does not have and does not want: the mirror holds the one
        // key in the system and resolves links with it at build time. But the
        // manifest already carries the file's sha1, and Modrinth answers "which
        // version owns this hash" for anyone. A mod published in both places
        // therefore keeps its icon, and one published in neither falls through to
        // the letter avatar exactly as before.
        //
        // Reached only for an entry the mirror gave no icon_url, and cached per
        // hash with nulls included, so a list resolves each unknown file once.
        return iconByHash(mod.sha1, mod.filename)
    }

    /**
     * Resolve an icon URL for an installed file with no manifest metadata:
     * hash it, ask Modrinth which version owns the hash, take that project's
     * icon. Returns null for files Modrinth doesn't know (local mods) or when
     * the file can't be read -- the UI then renders its letter avatar. Cached
     * per hash, including nulls, so re-renders and unknown files don't re-hit.
     */
    suspend fun resolveByFile(file: Path): String? {
        val sha1 = withContext(Dispatchers.IO) { runCatching { sha1Of(file) }.getOrNull() } ?: return null
        return iconByHash(sha1, file.fileName.toString())
    }

    /**
     * The hash half, shared by the installed-file path and by a manifest entry
     * whose source carries no icon of its own. [label] names the subject in a log
     * line and nothing else.
     *
     * Nulls are cached like answers: a file Modrinth does not know is not going to
     * become known on the next re-render, and a lookup that failed is not worth
     * repeating per frame either.
     */
    private suspend fun iconByHash(sha1: String, label: String): String? {
        if (sha1.isBlank()) return null

        mutex.withLock {
            if (hashCache.containsKey(sha1)) return hashCache[sha1]
        }

        val resolved = try {
            resolveIconByHash(sha1)
        } catch (e: Exception) {
            log.warn("Modrinth icon-by-hash lookup failed for {}: {}", label, e.message)
            null
        }
        mutex.withLock { hashCache[sha1] = resolved }
        return resolved
    }

    private fun sha1Of(file: Path): String {
        val md = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(file).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
