package hivens.ui.screens.library.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.launcher.instance.ModInstaller
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
    private val install: suspend (ModrinthSearchHit) -> ModInstaller.Outcome,
    private val presentProjects: suspend () -> Set<String>,
) {
    var query by mutableStateOf("")

    /**
     * The query the search actually ran on. Kept beside [query] rather than in
     * the composable: split across two lifetimes, a rebuilt holder would reset
     * one and not the other, and the browser would search for text no longer in
     * its own box.
     */
    var submitted by mutableStateOf("")

    /** Null while a search is in flight, so the browser can show its spinner. */
    var results by mutableStateOf<List<ModrinthSearchHit>?>(null)
        private set

    /**
     * Projects the instance already carries, whether this browser put them there
     * or not.
     *
     * Seeded from the folder rather than accumulated from clicks. Growing it only
     * from installs made here meant a pack of ninety mods opened the browser and
     * offered to install every one of them again, and a dependency dragged in
     * behind the mod that was clicked stayed on Install until the tab was
     * reopened -- the browser was reporting its own session, not the instance.
     */
    var installed by mutableStateOf(emptySet<String>())
        private set

    var working by mutableStateOf(emptySet<String>())
        private set

    /** Projects whose install failed; the row offers the action again. */
    var failed by mutableStateOf(emptySet<String>())
        private set

    /**
     * True when the last search could not be run at all. Distinct from an empty
     * result, which is an answer: offline, both read as "no content" and the
     * reader had nothing to tell them the query never left the machine.
     */
    var searchFailed by mutableStateOf(false)
        private set

    /**
     * Ask the folder what it holds. Runs alongside the first search rather than
     * before it: both are one request, and holding the results back until this
     * lands would trade a row that corrects itself for a screen that stays empty.
     */
    suspend fun loadInstalled() {
        installed = installed + runCatching { presentProjects() }
            .onFailure {
                if (it is CancellationException) throw it
                // A row reading Install for something already installed is a
                // wasted click; a browser that refuses to open is worse.
                log.warn("reading installed projects failed", it)
            }
            .getOrDefault(emptySet())
    }

    suspend fun runSearch(settled: String) {
        results = null
        searchFailed = false
        results = runCatching { search(settled) }
            .onFailure {
                if (it is CancellationException) throw it
                log.warn("Modrinth search failed", it)
                searchFailed = true
            }
            .getOrDefault(emptyList())
    }

    /**
     * Installs one result. Marks the project installed only when the download
     * actually landed -- the previous version reported success unconditionally,
     * which is the same thing as not checking.
     *
     * What the install reports back is the whole set the instance now holds, so
     * the dependencies it pulled in behind this one stop offering themselves.
     */
    suspend fun installMod(hit: ModrinthSearchHit) {
        val id = hit.projectId
        working = working + id
        failed = failed - id
        val outcome = try {
            install(hit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Installing {} from Modrinth failed", id, e)
            ModInstaller.Outcome()
        } finally {
            working = working - id
        }
        if (outcome.ok) installed = installed + outcome.present + id else failed = failed + id
    }
}

@Composable
internal fun rememberModBrowserState(mcVersion: String, loader: String, modsDir: Path): ModBrowserState {
    val modrinth: ModrinthClient = koinInject()
    val installer: ModInstaller = koinInject()
    // The installer works on the instance, the browser was handed its mods
    // folder; one is the parent of the other and this is where that is known.
    val instanceDir = modsDir.parent
    return remember(modrinth, installer, mcVersion, loader, modsDir) {
        ModBrowserState(
            search = { q -> withContext(Dispatchers.IO) { modrinth.searchMods(q, mcVersion, loader).hits } },
            install = { hit ->
                withContext(Dispatchers.IO) {
                    val version = modrinth.newestMatchingVersion(hit.projectId, mcVersion, loader)
                    if (version == null) {
                        // No build for this MC/loader pair is a real answer, not an
                        // error: the project exists but does not support this pack.
                        log.info("Modrinth project {} has no build for {} / {}", hit.projectId, mcVersion, loader)
                        ModInstaller.Outcome()
                    } else {
                        val outcome = installer.install(instanceDir, version, mcVersion, loader)
                        if (outcome.missing.isNotEmpty()) {
                            log.warn("{} installed without required {}", hit.title, outcome.missing)
                        }
                        outcome
                    }
                }
            },
            presentProjects = { withContext(Dispatchers.IO) { installer.presentProjects(instanceDir) } },
        )
    }
}
