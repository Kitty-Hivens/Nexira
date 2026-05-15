package hivens.launcher

import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.core.diag.ActionRing
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
 * sequentially (one at a time) — parallel sync fights for bandwidth on
 * slow connections and competes for disk IO during MD5 verification.
 * Power users with all 7 SMARTYcraft packs trade ~2 minutes of background
 * traffic for "click any server, it's ready".
 *
 * Skipped servers:
 * - User has no cached credentials (never logged in / opted not to save)
 * - Pack directory missing or empty (would mean a fresh many-GB download
 *   which the user clearly hasn't asked for — they've never launched this
 *   server before)
 *
 * Per-server failures don't abort the rest of the queue; they're logged
 * and surfaced via [serverStates] for the dashboard badges.
 */
class AutoSyncService(
    private val authService: IAuthService,
    private val downloadService: IFileDownloadService,
    private val manifestProcessor: IManifestProcessorService,
    private val manifestCache: ManifestCache,
    private val dataDirectory: Path,
    /**
     * Loads cached credentials. Lambda-injected (rather than holding a
     * CredentialsManager directly) so the service is testable without
     * the real CredentialsManager — it's a `final class` and our test
     * setup doesn't include mockk's inline-mock agent. Returning null
     * means "no creds available" → sync is skipped.
     */
    private val credentialsProvider: () -> SessionData?,
    /**
     * Looks up the user's optional-mods enable/disable state for a given
     * server. Lambda-injected for the same testability reason as
     * [credentialsProvider].
     */
    private val optionalModsStateProvider: (serverId: String) -> Map<String, Boolean>,
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

    private val _serverStates = MutableStateFlow<Map<String, ServerState>>(emptyMap())
    val serverStates: StateFlow<Map<String, ServerState>> = _serverStates.asStateFlow()

    private val _overallState = MutableStateFlow<OverallState>(OverallState.Idle)
    val overallState: StateFlow<OverallState> = _overallState.asStateFlow()

    /**
     * Sync every server in [allServers] that has a non-empty client directory.
     * Suspends until all servers processed. Caller decides on which dispatcher
     * to run — typically `Dispatchers.IO` from the applicationScope in Main
     * (process-lifetime SupervisorJob, cancelled on JVM exit so a half-done
     * sync doesn't orphan sockets / file descriptors).
     */
    suspend fun syncAll(allServers: List<ServerProfile>) {
        val creds = credentialsProvider()
        val pass = creds?.cachedPassword
        if (creds == null || pass.isNullOrBlank()) {
            log.info("Auto-sync skipped: no cached credentials")
            ActionRing.record("Auto-sync skipped: no cached credentials")
            _overallState.value = OverallState.Done(succeeded = 0, failed = 0, skipped = allServers.size)
            return
        }

        val installed = allServers.filter { hasClientFiles(it) }
        val skippedCount = allServers.size - installed.size

        if (installed.isEmpty()) {
            log.info("Auto-sync skipped: no installed servers")
            ActionRing.record("Auto-sync skipped: no installed servers")
            _overallState.value = OverallState.Done(succeeded = 0, failed = 0, skipped = skippedCount)
            return
        }

        ActionRing.record("Auto-sync started: ${installed.size} server(s) queued (${installed.joinToString { it.assetDir }})")

        // Mark everyone QUEUED upfront so the dashboard can show the queue ordering.
        _serverStates.value = installed.associate { it.assetDir to ServerState.QUEUED }

        var succeeded = 0
        var failed = 0

        for ((idx, server) in installed.withIndex()) {
            _serverStates.update { it + (server.assetDir to ServerState.SYNCING) }
            _overallState.value = OverallState.InProgress(
                currentServer = server.title ?: server.name,
                currentIdx = idx + 1,
                total = installed.size,
                bytesRead = 0,
                totalBytes = 0,
            )

            // Try to obtain a SessionData for this server. Three outcomes:
            //   * regular login → use that session directly
            //   * 2FA gate WITH cached manifest → use cached creds + manifest
            //   * 2FA gate WITHOUT cached manifest → mark SKIPPED (not failed)
            //     and move on. This isn't a failure — the account just hasn't
            //     been through 2FA on this machine for this server yet, so
            //     we have nothing to sync against. Red FAILED would imply
            //     "server is broken"; SKIPPED reads as "awaiting user action".
            val session: SessionData? = try {
                authService.login(creds.playerName, pass, server.assetDir)
            } catch (_: hivens.core.api.TwoFactorRequiredException) {
                val cached = manifestCache.loadManifest(server.assetDir)
                if (cached == null) {
                    log.info(
                        "Auto-sync skipped for {}: 2FA + no cached manifest yet, awaiting manual login",
                        server.assetDir,
                    )
                    ActionRing.record("Auto-sync skipped: ${server.assetDir} needs manual 2FA login")
                    _serverStates.update { it + (server.assetDir to ServerState.SKIPPED) }
                    null
                } else {
                    creds.copy(fileManifest = cached, serverId = server.assetDir)
                }
            }

            if (session == null) continue  // SKIPPED above; counters not bumped (it's neither succeeded nor failed)

            val ok = runCatching {
                val userState = optionalModsStateProvider(server.assetDir)
                val ignoredFiles = manifestProcessor.calculateIgnoredFiles(server, userState)
                val clientDir = dataDirectory.resolve("clients").resolve(server.assetDir)

                downloadService.processSession(
                    session = session,
                    serverId = server.assetDir,
                    targetDir = clientDir,
                    extraCheckSum = server.extraCheckSum,
                    ignoredFiles = ignoredFiles,
                    messageUI = null,
                    progressUI = { _, _, bytesRead, totalBytes, _ ->
                        _overallState.value = OverallState.InProgress(
                            currentServer = server.title ?: server.name,
                            currentIdx = idx + 1,
                            total = installed.size,
                            bytesRead = bytesRead,
                            totalBytes = totalBytes,
                        )
                    },
                )
            }

            if (ok.isSuccess) {
                _serverStates.update { it + (server.assetDir to ServerState.SYNCED) }
                succeeded++
            } else {
                _serverStates.update { it + (server.assetDir to ServerState.FAILED) }
                failed++
                log.warn("Auto-sync failed for ${server.assetDir}: ${ok.exceptionOrNull()?.message}")
            }
        }

        _overallState.value = OverallState.Done(succeeded = succeeded, failed = failed, skipped = skippedCount)
        log.info("Auto-sync complete: {} succeeded, {} failed, {} skipped", succeeded, failed, skippedCount)
        ActionRing.record("Auto-sync complete: $succeeded ok / $failed failed / $skippedCount skipped")
    }

    /**
     * "Installed" = the per-server client directory exists and has at least
     * one entry. We never trigger a fresh many-GB pack download in the
     * background — the user must explicitly launch a server at least once
     * to opt that pack into the auto-sync set.
     */
    private fun hasClientFiles(server: ServerProfile): Boolean {
        val dir = dataDirectory.resolve("clients").resolve(server.assetDir)
        if (!Files.isDirectory(dir)) return false
        return Files.list(dir).use { it.findFirst().isPresent }
    }
}
