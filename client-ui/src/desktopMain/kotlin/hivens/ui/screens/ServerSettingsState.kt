package hivens.ui.screens

import androidx.compose.runtime.Composable
import hivens.core.io.deleteTree
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import hivens.core.api.PlayerRepository
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.OptionalMod
import hivens.core.jvm.AutomaticHeap
import hivens.core.jvm.SystemMemory
import hivens.auth.AccountStore
import hivens.launcher.ProfileManager
import hivens.launcher.platform.ServerNameValidator
import hivens.launcher.ProfilerProfileStore
import hivens.ui.platform.SystemActions
import hivens.ui.utils.pickFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds

/** UI-facing state for the "return to spawn" action shown on 1.12.2 servers. */
internal sealed interface SpawnResetState {
    data object Idle : SpawnResetState
    data object Loading : SpawnResetState
    data object Success : SpawnResetState
    data object Error : SpawnResetState
}

/**
 * State holder for [ServerSettingsScreen]. Owns the editable [InstanceProfile]
 * fields, the optional-mod toggle map, and the IO (profile load/save, icon
 * pick/load, spawn reset, client wipe). The composable renders these and
 * forwards intents.
 *
 * Persistence reads this holder's own fields -- the canonical editor state --
 * via [assembleProfile], so a mod toggle no longer rebuilds the profile from a
 * snapshot of unrelated composition variables. IO runs on the injected [scope]
 * (a `rememberCoroutineScope`), not in click lambdas.
 */
private val log = LoggerFactory.getLogger("ServerSettingsState")

@Stable
internal class ServerSettingsState(
    val server: ServerProfile,
    private val profileManager: ProfileManager,
    private val manifestProcessor: IManifestProcessorService,
    private val settingsService: ISettingsService,
    private val profilerStore: ProfilerProfileStore,
    private val credentialsManager: AccountStore,
    private val playerRepository: PlayerRepository,
    private val dataDirectory: Path,
    private val scope: CoroutineScope,
) {
    /** Whether the experimental JVM-args builder entry points are shown. */
    val jvmBuilderEnabled: Boolean = settingsService.getSettings().jvmBuilderEnabled

    var javaPath by mutableStateOf("")
    var memoryMb by mutableStateOf(4096)
    var isAutoMode by mutableStateOf(true)
    var resolvedAutoMb by mutableStateOf(AutomaticHeap.compute(SystemMemory.totalPhysicalMb()))
        private set
    var jvmArgs by mutableStateOf("")
    var winWidth by mutableStateOf("925")
    var winHeight by mutableStateOf("530")
    var fullScreen by mutableStateOf(false)
    var autoConnect by mutableStateOf(true)
    var serverIcon by mutableStateOf<ImageBitmap?>(null)
        private set
    var spawnResetState by mutableStateOf<SpawnResetState>(SpawnResetState.Idle)
        private set
    var mods by mutableStateOf<List<OptionalMod>>(emptyList())
        private set

    /** Per-mod enabled bits, the canonical map both the UI and [save] read. */
    val modStates = mutableStateMapOf<String, Boolean>()

    var modsLoaded by mutableStateOf(false)
        private set

    // The profile as loaded from disk -- the base [save] copies over with the
    // current field values, so non-editor fields stay intact.
    private var loadedProfile: InstanceProfile? = null

    /** Load the profile, derived RAM baseline, optional mods, and icon. */
    suspend fun load() {
        val p = profileManager.getProfile(server.assetDir)
        loadedProfile = p
        javaPath = p.javaPath ?: ""
        jvmArgs = p.jvmArgs ?: ""
        winWidth = p.windowWidth.toString()
        winHeight = p.windowHeight.toString()
        fullScreen = p.fullScreen
        autoConnect = p.autoConnect
        if (p.memoryMb > 0) memoryMb = p.memoryMb

        // RAM mode: Auto unless the user pinned a value. The Auto chip shows what
        // the next launch will actually use -- the adaptive-derived heap when
        // adaptive is on and has data, otherwise the machine-aware baseline.
        isAutoMode = !p.fixedMemory
        val settings = settingsService.getSettings()
        val adaptiveOn = settings.experimentalFeaturesEnabled && settings.adaptiveMemoryEnabled
        val clientDir = clientDirOrNull()
        val derivedMb = if (adaptiveOn && clientDir != null) {
            withContext(Dispatchers.IO) { profilerStore.readProfile(clientDir)?.derivedHeapMb }
        } else {
            null
        }
        resolvedAutoMb = derivedMb ?: AutomaticHeap.compute(SystemMemory.totalPhysicalMb())

        val loadedMods = manifestProcessor.getOptionalModsForClient(server)
        mods = loadedMods
        loadedMods.forEach { mod ->
            modStates[mod.id] = p.optionalModsState.getOrDefault(mod.id, mod.isDefault)
        }
        modsLoaded = true

        withContext(Dispatchers.IO) {
            val iconFile = getServerIconFile(dataDirectory, server)
            if (iconFile.exists()) {
                runCatching { serverIcon = ImageIO.read(iconFile)?.toComposeImageBitmap() }
            }
        }
    }

    /** Persist the current editor state. No-op until [load] has run. */
    fun save() {
        val base = loadedProfile ?: return
        profileManager.saveProfile(
            assembleProfile(
                base        = base,
                javaPath    = javaPath,
                memoryMb    = memoryMb,
                isAutoMode  = isAutoMode,
                jvmArgs     = jvmArgs,
                winWidth    = winWidth,
                winHeight   = winHeight,
                fullScreen  = fullScreen,
                autoConnect = autoConnect,
                modStates   = modStates,
            ),
        )
    }

    /** Toggle one optional mod (with its mutual-exclusions) and persist. */
    fun toggleMod(mod: OptionalMod, enabled: Boolean) {
        applyModToggle(modStates, mod, enabled)
        save()
    }

    fun pickIcon(dialogSettings: FileKitDialogSettings) {
        scope.launch {
            val file = pickFile(
                type     = FileKitType.File(extensions = listOf("png", "jpg", "jpeg")),
                settings = dialogSettings,
            )
            file?.path?.let { selectedPath ->
                withContext(Dispatchers.IO) {
                    val targetFile = getServerIconFile(dataDirectory, server)
                    targetFile.parentFile.mkdirs()
                    Files.copy(
                        Path.of(selectedPath),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    runCatching { serverIcon = ImageIO.read(targetFile)?.toComposeImageBitmap() }
                }
            }
        }
    }

    fun pickJava(dialogSettings: FileKitDialogSettings) {
        scope.launch {
            val file = pickFile(
                type     = FileKitType.File(extensions = listOf("exe", "bin")),
                settings = dialogSettings,
            )
            file?.path?.let { javaPath = it }
        }
    }

    /**
     * `clients/<assetDir>` for this server, or null when [ServerProfile.assetDir]
     * is not a name we will turn into a path.
     *
     * The field arrives in a server response and lands here as a directory name
     * that [resetClient] deletes recursively. The list is screened where it
     * enters the launcher; this is the second gate, at the point where the name
     * actually becomes a path, so a profile reaching this screen by some other
     * route cannot aim a recursive delete.
     */
    private fun clientDirOrNull(): Path? =
        runCatching { dataDirectory.resolve("clients").resolve(ServerNameValidator.require(server.assetDir)) }
            .onFailure { log.warn("Server '{}' has an unusable assetDir -- refusing to touch a directory for it", server.name) }
            .getOrNull()

    fun openClientFolder() {
        val path = clientDirOrNull() ?: return
        if (!path.toFile().exists()) path.toFile().mkdirs()
        SystemActions.openFile(path.toFile())
    }

    /** Wipe clients/<assetDir> irrecoverably, persist, then notify [onDone]. */
    fun resetClient(onDone: () -> Unit) {
        val dir = clientDirOrNull() ?: return
        runCatching { deleteTree(dir) }
            .onFailure { log.warn("Reset of {} did not finish", dir, it) }
        save()
        onDone()
    }

    /** Server-side "return to spawn"; flashes a transient success/error state. */
    fun resetSpawn() {
        spawnResetState = SpawnResetState.Loading
        scope.launch {
            val credentials = withContext(Dispatchers.IO) { credentialsManager.load() }
            if (credentials == null) {
                spawnResetState = SpawnResetState.Error
                delay(3000.milliseconds)
                spawnResetState = SpawnResetState.Idle
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { playerRepository.resetSpawn(credentials, server.name) }
            spawnResetState = if (ok) SpawnResetState.Success else SpawnResetState.Error
            delay(3000.milliseconds)
            spawnResetState = SpawnResetState.Idle
        }
    }
}

@Composable
internal fun rememberServerSettingsState(server: ServerProfile): ServerSettingsState {
    val profileManager: ProfileManager = koinInject()
    val manifestProcessor: IManifestProcessorService = koinInject()
    val settingsService: ISettingsService = koinInject()
    val profilerStore: ProfilerProfileStore = koinInject()
    val credentialsManager: AccountStore = koinInject()
    val playerRepository: PlayerRepository = koinInject()
    val dataDirectory: Path = koinInject()
    val scope = rememberCoroutineScope()
    return remember(server) {
        ServerSettingsState(
            server = server,
            profileManager = profileManager,
            manifestProcessor = manifestProcessor,
            settingsService = settingsService,
            profilerStore = profilerStore,
            credentialsManager = credentialsManager,
            playerRepository = playerRepository,
            dataDirectory = dataDirectory,
            scope = scope,
        )
    }
}

// ── Pure logic (unit-tested without a ProfileManager) ────────────────────────

/**
 * Build the persisted profile from the editor's current field values plus the
 * mod toggle map, on top of [base] (so fields the editor doesn't surface stay
 * untouched). Blank text fields collapse to null; bad dimensions fall back to
 * the launcher defaults.
 */
internal fun assembleProfile(
    base: InstanceProfile,
    javaPath: String,
    memoryMb: Int,
    isAutoMode: Boolean,
    jvmArgs: String,
    winWidth: String,
    winHeight: String,
    fullScreen: Boolean,
    autoConnect: Boolean,
    modStates: Map<String, Boolean>,
): InstanceProfile {
    return base.copy(
        javaPath     = javaPath.ifBlank { null },
        memoryMb     = memoryMb,
        fixedMemory  = !isAutoMode,
        jvmArgs      = jvmArgs.ifBlank { null },
        windowWidth  = winWidth.toIntOrNull() ?: 925,
        windowHeight = winHeight.toIntOrNull() ?: 530,
        fullScreen   = fullScreen,
        autoConnect  = autoConnect,
        // Merge the editor's canonical toggles over whatever the base carried,
        // as a fresh map -- the immutable field has value semantics, so no two
        // profiles ever share one backing map.
        optionalModsState = base.optionalModsState + modStates,
    )
}

/**
 * Apply a single optional-mod toggle to [modStates] in place. Enabling a mod
 * also disables every mod it declares as a conflict ([OptionalMod.excludings]),
 * mirroring the curator's mutual-exclusion intent; disabling never cascades.
 */
internal fun applyModToggle(
    modStates: MutableMap<String, Boolean>,
    mod: OptionalMod,
    enabled: Boolean,
) {
    modStates[mod.id] = enabled
    if (enabled) {
        mod.excludings.forEach { conflict -> modStates[conflict] = false }
    }
}

internal fun getServerIconFile(dataDirectory: Path, server: ServerProfile): File =
    dataDirectory.resolve("clients/${server.assetDir}/icon.png").toFile()
