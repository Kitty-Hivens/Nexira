package hivens.launcher

import hivens.core.api.TwoFactorRequiredException
import hivens.auth.AuthProvider
import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.core.diag.ActionRing
import hivens.launcher.platform.ServerNameValidator
import hivens.launcher.smrt.ClientSyncCoordinator
import hivens.launcher.smrt.OpenSmrtHelperResolver
import hivens.launcher.smrt.SmartyModPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Background sync of all installed server packs on launcher startup.
 *
 * Opt-in via `SettingsData.autoSyncAllPacks`. Walks installed servers
 * sequentially (one at a time) -- parallel sync fights for bandwidth on
 * slow connections and competes for disk IO during MD5 verification.
 * Power users with all 7 SMARTYcraft packs trade ~2 minutes of background
 * traffic for "click any server, it's ready".
 *
 * Skipped servers:
 * - User has no cached credentials (never logged in / opted not to save)
 * - Pack directory missing or empty (would mean a fresh many-GB download
 *   which the user clearly hasn't asked for -- they've never launched this
 *   server before)
 *
 * Per-server failures don't abort the rest of the queue; they're logged
 * and surfaced via `serverStates` for the dashboard badges.
 */
class AutoSyncService(
    private val authService: AuthProvider,
    private val downloadService: IFileDownloadService,
    private val manifestProcessor: IManifestProcessorService,
    private val manifestCache: ManifestCache,
    private val dataDirectory: Path,
    /**
     * Loads cached credentials. Lambda-injected (rather than holding a
     * CredentialsManager directly) so the service is testable without
     * the real CredentialsManager -- it's a `final class` and our test
     * setup doesn't include mockk's inline-mock agent. Returning null
     * means "no creds available" -> sync is skipped.
     */
    private val credentialsProvider: () -> SessionData?,
    /**
     * Looks up the user's optional-mods enable/disable state for a given
     * server. Lambda-injected for the same testability reason as
     * [credentialsProvider].
     */
    private val optionalModsStateProvider: (serverId: String) -> Map<String, Boolean>,
    /**
     * Builds the Smarty swap + strict-verification plan so background sync
     * applies the same mod handling as a foreground launch.
     */
    private val smartyPlanner: SmartyModPlanner,
    /** Current settings, read per-server so a mid-session toggle is honoured. */
    private val settingsProvider: () -> SettingsData,
) {
    private val log = LoggerFactory.getLogger(AutoSyncService::class.java)

    /** Per-server lifecycle state, observed by dashboard badges. */
    enum class ServerState { QUEUED, SYNCING, SYNCED, FAILED, SKIPPED }

    /** Aggregate progress for the bottom status bar. */
    sealed class OverallState {
        object Idle : OverallState()
        data class InProgress(
            val currentServer: String,
            val currentIdx: Int,
            val total: Int,
            val bytesRead: Long,
            val totalBytes: Long,
        ) : OverallState()
        data class Done(val succeeded: Int, val failed: Int, val skipped: Int) : OverallState()
    }

    /**
     * Single observable view -- per-server lifecycle map paired with
     * the aggregate progress. One snapshot because dashboard consumers
     * always need both together (badges + progress strip).
     */
    data class Snapshot(
        val perServer: Map<String, ServerState>,
        val overall: OverallState,
    )

    private val _snapshot = MutableStateFlow(Snapshot(emptyMap(), OverallState.Idle))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private fun updateServerState(serverId: String, state: ServerState) {
        _snapshot.update { it.copy(perServer = it.perServer + (serverId to state)) }
    }

    private fun setPerServer(map: Map<String, ServerState>) {
        _snapshot.update { it.copy(perServer = map) }
    }

    private fun setOverall(state: OverallState) {
        _snapshot.update { it.copy(overall = state) }
    }

    /**
     * Sync every server in [allServers] that has a non-empty client directory.
     * Suspends until all servers processed. Caller decides on which dispatcher
     * to run -- typically `Dispatchers.IO` from the applicationScope in Main
     * (process-lifetime SupervisorJob, canceled on JVM exit so a half-done
     * sync doesn't orphan sockets / file descriptors).
     */
    suspend fun syncAll(allServers: List<ServerProfile>) {
        val creds = credentialsProvider()
        val pass = creds?.cachedPassword
        if (creds == null || pass.isNullOrBlank()) {
            log.info("Auto-sync skipped: no cached credentials")
            ActionRing.record("Auto-sync skipped: no cached credentials")
            setOverall(OverallState.Done(succeeded = 0, failed = 0, skipped = allServers.size))
            return
        }

        val installed = allServers.filter { hasClientFiles(it) }
        val skippedCount = allServers.size - installed.size

        if (installed.isEmpty()) {
            log.info("Auto-sync skipped: no installed servers")
            ActionRing.record("Auto-sync skipped: no installed servers")
            setOverall(OverallState.Done(succeeded = 0, failed = 0, skipped = skippedCount))
            return
        }

        ActionRing.record("Auto-sync started: ${installed.size} server(s) queued (${installed.joinToString { it.assetDir }})")

        // Mark everyone QUEUED upfront so the dashboard can show the queue ordering.
        setPerServer(installed.associate { it.assetDir to ServerState.QUEUED })

        var succeeded = 0
        var failed = 0

        for ((idx, server) in installed.withIndex()) {
            updateServerState(server.assetDir, ServerState.SYNCING)
            setOverall(OverallState.InProgress(
                currentServer = server.title ?: server.name,
                currentIdx = idx + 1,
                total = installed.size,
                bytesRead = 0,
                totalBytes = 0,
            ))

            // Try to obtain a SessionData. Four outcomes:
            //   * regular login -> use that session directly
            //   * 2FA gate WITH cached manifest -> use cached creds + manifest
            //   * 2FA gate WITHOUT cached manifest -> mark SKIPPED
            //     (not failed). The account just hasn't been through
            //     2FA on this machine for this server yet, so we have
            //     nothing to sync against. Red FAILED would read
            //     "server is broken"; SKIPPED reads "awaiting user
            //     action".
            //   * any other login throw (network, server reject) ->
            //     mark FAILED for this server and continue. Each
            //     server needs its own catch; without it a single
            //     login exception terminates syncAll for every later
            //     server in the queue.
            var sessionFailed = false
            val session: SessionData? = try {
                // A 2FA account is not logged in again, ever, from here. The damage
                // is done by the REQUEST, not by its failure: SmartyCraft mints a new
                // uid per login and invalidates the previous one, so a background
                // sync across N servers wipes the session the player just unlocked
                // with a code. Sync what the cached manifest allows and leave the
                // session alone.
                if (creds.twoFactor) throw TwoFactorRequiredException(uid = null, login = creds.playerName)
                authService.login(creds.playerName, pass, server.assetDir)
            } catch (_: TwoFactorRequiredException) {
                val cached = manifestCache.loadManifest(server.assetDir)
                if (cached == null) {
                    log.info(
                        "Auto-sync skipped for {}: 2FA + no cached manifest yet, awaiting manual login",
                        server.assetDir,
                    )
                    ActionRing.record("Auto-sync skipped: ${server.assetDir} needs manual 2FA login")
                    updateServerState(server.assetDir, ServerState.SKIPPED)
                    null
                } else {
                    // Carried forward for this pass; the flag is persisted where the
                    // code is actually answered (the login form), since this service
                    // deliberately holds a read-only view of the credentials.
                    creds.copy(fileManifest = cached, serverId = server.assetDir, twoFactor = true)
                }
            } catch (e: Exception) {
                log.warn("Auto-sync login failed for {}: {}", server.assetDir, e.message)
                sessionFailed = true
                null
            }

            if (sessionFailed) {
                updateServerState(server.assetDir, ServerState.FAILED)
                failed++
                continue
            }
            if (session == null) continue  // SKIPPED above; counters not bumped (it's neither succeeded nor failed)

            val settings = settingsProvider()
            val userState = optionalModsStateProvider(server.assetDir)
            val ignoredFiles = manifestProcessor.calculateIgnoredFiles(server, userState)
            // Second gate behind the server-list screening: this name came from
            // a server response and is about to become a directory we write into.
            val clientDir = dataDirectory.resolve("clients").resolve(ServerNameValidator.require(server.assetDir))
            val smartyPlan = runCatching { smartyPlanner.plan(server, session.fileManifest, settings) }
                .getOrElse {
                    log.warn("Auto-sync: Smarty planning failed for {}: {}", server.assetDir, it.message)
                    updateServerState(server.assetDir, ServerState.FAILED)
                    failed++
                    continue
                }

            // Refuse to strip Smarty with no replacement (same gate as a foreground
            // launch): don't mutate the pack into a broken state in the background.
            // This is "awaiting upstream/user action", not a failure -> SKIPPED, the
            // same convention the 2FA branch uses.
            if (settings.useOpenSmrtHelper && smartyPlan.ignoredAddon.isNotEmpty() &&
                !helperPresent(clientDir, server.version, smartyPlan)) {
                log.info("Auto-sync skipped for {}: no open-smrt helper for MC {}", server.assetDir, server.version)
                updateServerState(server.assetDir, ServerState.SKIPPED)
                continue
            }

            val ok = runCatching {
                ClientSyncCoordinator.withClientLock(clientDir) {
                    downloadService.processSession(
                        session = session,
                        serverId = server.assetDir,
                        targetDir = clientDir,
                        extraCheckSum = server.extraCheckSum,
                        ignoredFiles = ignoredFiles + smartyPlan.ignoredAddon,
                        messageUI = null,
                        progressUI = { progress ->
                            setOverall(OverallState.InProgress(
                                currentServer = server.title ?: server.name,
                                currentIdx = idx + 1,
                                total = installed.size,
                                bytesRead = progress.downloadedBytes,
                                totalBytes = progress.totalBytes,
                            ))
                        },
                        injectModJar = smartyPlan.injectJar,
                        strictModCheck = smartyPlan.strict,
                        helperKeepGlobs = smartyPlan.helperKeepGlobs,
                    )
                }
            }

            if (ok.isSuccess) {
                updateServerState(server.assetDir, ServerState.SYNCED)
                succeeded++
            } else {
                updateServerState(server.assetDir, ServerState.FAILED)
                failed++
                log.warn("Auto-sync failed for ${server.assetDir}: ${ok.exceptionOrNull()?.message}")
            }
        }

        setOverall(OverallState.Done(succeeded = succeeded, failed = failed, skipped = skippedCount))
        log.info("Auto-sync complete: {} succeeded, {} failed, {} skipped", succeeded, failed, skippedCount)
        ActionRing.record("Auto-sync complete: $succeeded ok / $failed failed / $skippedCount skipped")
    }

    /**
     * "Installed" = the per-server client directory exists and has at least
     * one entry. We never trigger a fresh many-GB pack download in the
     * background -- the user must explicitly launch a server at least once
     * to opt that pack into the auto-sync set.
     */
    private fun hasClientFiles(server: ServerProfile): Boolean {
        val dir = dataDirectory.resolve("clients").resolve(server.assetDir)
        if (!Files.isDirectory(dir)) return false
        return Files.list(dir).use { it.findFirst().isPresent }
    }

    private fun helperPresent(clientDir: Path, mcVersion: String, plan: SmartyModPlanner.Plan): Boolean =
        plan.injectJar != null ||
            Files.isRegularFile(clientDir.resolve("mods").resolve(OpenSmrtHelperResolver.helperFileName(mcVersion)))
}
