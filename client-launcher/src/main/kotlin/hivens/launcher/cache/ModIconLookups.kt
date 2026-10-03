package hivens.launcher.cache

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.cache.Cache
import hivens.core.smrt.fileSha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The three lookups behind a mod's icon, each remembered on disk.
 *
 * Every one of them used to be asked again on every launch: the jar hashed in full,
 * the catalogue asked which version owns the hash, the project asked for its icon.
 * None of the answers had changed. A hash names one published file forever, and a
 * file is the same file for as long as its path, size and modification time are, so
 * those two answers are kept for a long time. A project's icon can change, rarely,
 * so it is kept for a week and refreshed behind the one already shown.
 *
 * An answer of "no icon" is kept as an empty string, so a file the catalogue does
 * not know is not asked about on every start either. A lookup that FAILS is not an
 * answer: it throws, the cache stores nothing, and the next start asks again.
 */
class ModIconLookups(
    private val caches: ModIconCaches,
    private val versionByHash: suspend (sha1: String) -> ModrinthVersion?,
    private val projectIcon: suspend (projectId: String) -> String?,
) {
    suspend fun iconForProject(projectId: String): String? =
        caches.byProject.get(projectId) { projectIcon(projectId).orEmpty() }.ifEmpty { null }

    suspend fun iconForHash(sha1: String): String? =
        caches.byHash.get(sha1) {
            versionByHash(sha1)?.let { projectIcon(it.projectId) }.orEmpty()
        }.ifEmpty { null }

    /** The file's sha1, read from disk only when the file is new or has changed. Null when it cannot be read. */
    suspend fun sha1(file: Path): String? = withContext(Dispatchers.IO) {
        val attrs = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull()
            ?: return@withContext null
        val key = "${file.toAbsolutePath().normalize()}|${attrs.size()}|${attrs.lastModifiedTime().toMillis()}"
        runCatching { caches.fileHash.get(key) { fileSha1(file) } }.getOrNull()
    }
}

/** Where [ModIconLookups] keeps its answers. */
class ModIconCaches(
    val byHash: Cache<String>,
    val byProject: Cache<String>,
    val fileHash: Cache<String>,
)
