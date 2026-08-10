package hivens.ui.screens.library.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger("ModBrowser")

/**
 * State holder for the "Find projects" browser: the query, the search, and
 * installing a result into the instance's `mods/`.
 *
 * The search and the download used to run in the composable -- a `LaunchedEffect`
 * per keystroke and a `runCatching` inside the row's click lambda. The catch
 * swallowed the failure and the line after it marked the mod installed anyway,
 * so a download that never happened left a row reading Installed until the tab
 * was reopened. Failure is a state here instead.
 */
@Stable
internal class ModBrowserState(
    private val search: suspend (String) -> List<ModrinthSearchHit>,
    private val install: suspend (ModrinthSearchHit) -> Boolean,
) {
    var query by mutableStateOf("")

    /** Null while a search is in flight, so the browser can show its spinner. */
    var results by mutableStateOf<List<ModrinthSearchHit>?>(null)
        private set

    var installed by mutableStateOf(emptySet<String>())
        private set

    var working by mutableStateOf(emptySet<String>())
        private set

    /** Projects whose install failed; the row offers the action again. */
    var failed by mutableStateOf(emptySet<String>())
        private set

    suspend fun runSearch(settled: String) {
        results = null
        results = runCatching { search(settled) }
            .onFailure { if (it is CancellationException) throw it else log.warn("Modrinth search failed", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Installs one result. Marks the project installed only when the download
     * actually landed -- the previous version reported success unconditionally,
     * which is the same thing as not checking.
     */
    suspend fun installMod(hit: ModrinthSearchHit) {
        val id = hit.projectId
        working = working + id
        failed = failed - id
        val ok = try {
            install(hit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Installing {} from Modrinth failed", id, e)
            false
        } finally {
            working = working - id
        }
        if (ok) installed = installed + id else failed = failed + id
    }
}

@Composable
internal fun rememberModBrowserState(mcVersion: String, loader: String, modsDir: Path): ModBrowserState {
    val modrinth: ModrinthClient = koinInject()
    return remember(modrinth, mcVersion, loader, modsDir) {
        ModBrowserState(
            search = { q -> withContext(Dispatchers.IO) { modrinth.searchMods(q, mcVersion, loader).hits } },
            install = { hit ->
                withContext(Dispatchers.IO) {
                    val version = modrinth.bestModVersion(hit.projectId, mcVersion, loader)
                    val file = version?.primaryFile()
                    if (file == null) {
                        // No file for this MC/loader pair is a real answer, not an
                        // error: the project exists but does not support this pack.
                        log.info("Modrinth project {} has no build for {} / {}", hit.projectId, mcVersion, loader)
                        false
                    } else {
                        modrinth.downloadTo(file.url, modsDir.resolve(file.filename))
                        true
                    }
                }
            },
        )
    }
}
