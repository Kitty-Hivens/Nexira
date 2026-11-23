// The module is compiled into the application and trusted with the host's own services: the data
// directory the person's earlier choice is kept in.
@file:OptIn(HostApi::class)

package hivens.look.surface.nx

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.surface.api.Surface
import hivens.look.surface.api.SurfaceAnswers
import hivens.look.surface.api.SurfaceApi
import hivens.ui.theme.nxSurfaceAnswers
import hivens.widget.api.BootContext
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.HostFiles
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.ServiceContext
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsDomain
import hivens.widget.api.SettingsStore
import hivens.widget.api.SettingsStores
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.service
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The nx surface provider as the application carries it: compiled in, and brought in by the look
 * manager where the person's choice names nx for surface.
 *
 * It answers the surface axis ([NxSurfaceProvider]) and keeps whether the person wants glass to blur
 * in its own settings ([NxSurfaceSettingsDomain]), which its keys ([SurfaceNxKeys]) read and change.
 * Nothing of it is the host's.
 */
object SurfaceNxModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.surface.nx"

    /** The family it answers the surface axis as, which a pack names it by. */
    const val FAMILY: String = "nx"

    override val id: String = ID
    override val name: String = "surface.nx.name"
    override val strings: ModuleStrings = ModuleStrings.resources(SurfaceNxModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(SurfaceNxKeysContribution, NxSurfaceProvider)
}

/** What the nx surface provider keeps of the person's choice. */
@Serializable
data class NxSurfaceSettings(
    /**
     * Whether a plane blurs what is behind it at all. A backdrop filter cannot be cached, so it is
     * worth being able to switch off in one place: off, every plane keeps its body, its opacity and its
     * shape and only stops filtering. Null until said here, and then the choice the launcher kept before
     * this module had its own.
     */
    val blur: Boolean? = null,
)

/**
 * The provider's settings, in `surface.nx.json` under the settings folder.
 *
 * Blur was kept in `customization.json` before the provider had settings of its own. Where this domain
 * has said nothing yet, the choice kept there holds, and the module writes it here the first time it
 * opens, so it is kept from then on whatever becomes of that file.
 */
object NxSurfaceSettingsDomain : SettingsDomain<NxSurfaceSettings>("surface.nx", NxSurfaceSettings.serializer()) {
    override fun defaults(): NxSurfaceSettings = NxSurfaceSettings()
}

/** The keys the nx surface provider answers, all at the app level. The ids are constants so a widget can name them in its needs. */
object SurfaceNxKeys {
    const val SETTINGS_ID = "surface.nx.settings"
    const val UPDATE_SETTINGS_ID = "surface.nx.settings.update"
    const val BLUR_ID = "surface.nx.blur"
    const val SET_BLUR_ID = "surface.nx.blur.set"

    /** The provider's settings, as stored. */
    val Settings = SourceKey<NxSurfaceSettings>(SETTINGS_ID, ScopeLevel.App)

    /** Changes the provider's settings. */
    val UpdateSettings = CommandKey<SettingsChange<NxSurfaceSettings>>(UPDATE_SETTINGS_ID, ScopeLevel.App)

    /** Whether planes blur now, with the choice carried over where none was made here. */
    val Blur = SourceKey<Boolean>(BLUR_ID, ScopeLevel.App)

    /** Turns blur on or off. */
    val SetBlur = CommandKey<Boolean>(SET_BLUR_ID, ScopeLevel.App)
}

/** The provider's keys, answered over its [NxSurfaceChoice]. */
internal object SurfaceNxKeysContribution : KeyContribution {
    override val id: String = "surface.nx.keys"
    override val label: String = "nx surface"

    override fun declare(keys: ModuleKeys) {
        keys.settings(NxSurfaceSettingsDomain, SurfaceNxKeys.Settings, SurfaceNxKeys.UpdateSettings)
        keys.service(ScopeLevel.App, ::choiceOf)
        keys.source(SurfaceNxKeys.Blur, sample = { true }) { app -> flowSource(app.services.get<NxSurfaceChoice>().blur) }
        keys.command(SurfaceNxKeys.SetBlur) { app -> command(app.services.get<NxSurfaceChoice>()::set) }
    }
}

/**
 * Whether the person wants planes to blur, as this module keeps it once the host is up: its own
 * setting, and where that says nothing yet, the choice the launcher kept before, which it writes into
 * its own setting at once.
 */
class NxSurfaceChoice internal constructor(
    private val store: SettingsStore<NxSurfaceSettings>,
    carried: () -> Boolean,
    scope: CoroutineScope,
) {
    private val fallback: Boolean = if (store.value.value.blur == null) carried() else true

    init {
        if (store.value.value.blur == null) {
            store.update { current -> if (current.blur == null) current.copy(blur = fallback) else current }
        }
    }

    /** Whether planes blur now. */
    val blur: StateFlow<Boolean> = store.value
        .map { it.blur ?: fallback }
        .stateIn(scope, SharingStarted.Eagerly, store.value.value.blur ?: fallback)

    /** Turns blur on where [on], or off. */
    fun set(on: Boolean) = store.update { it.copy(blur = on) }
}

private fun choiceOf(context: ServiceContext): NxSurfaceChoice {
    val dataDir = context.hostServices.get<HostFiles>().dataDir
    return NxSurfaceChoice(
        store   = context.services.get<SettingsStores>().of(NxSurfaceSettingsDomain),
        carried = { carriedOver(dataDir) },
        scope   = context.coroutineScope,
    )
}

/**
 * The nx family of the surface axis: nx's planes, as nx-ui answers them ([nxSurfaceAnswers]), with no
 * blur drawn where the person switched it off.
 *
 * Before the host is up it answers from the module's own settings, and the earlier choice where they
 * say nothing yet, read the way the module reads them once up. Once up it follows the module's choice
 * as it changes, handing the other answers without being opened again.
 */
internal object NxSurfaceProvider : LookProvider<SurfaceAnswers> {
    override val axis: String = Surface.id
    override val family: String = SurfaceNxModule.FAMILY
    override val contract = SurfaceApi.VERSION
    override val label: String = "surface.nx.provider"

    override fun open(context: LookContext): LookAnswers<SurfaceAnswers> {
        context.boot?.let { boot -> return LookAnswers.of(nxSurfaceAnswers(bootBlur(boot))) }
        val choice = context.services.get<NxSurfaceChoice>()
        val answers = choice.blur
            .map(::nxSurfaceAnswers)
            .stateIn(context.coroutineScope, SharingStarted.Eagerly, nxSurfaceAnswers(choice.blur.value))
        return LookAnswers.following(answers)
    }
}

/** Whether planes blur, as the window drawn before the host is up reads it. */
internal fun bootBlur(boot: BootContext): Boolean =
    boot.settings(NxSurfaceSettingsDomain).blur ?: carriedOver(boot.dataDir)

/** The file blur was kept in before the provider had settings of its own. */
internal const val CARRIED_FILE = "customization.json"

/** The blur [CARRIED_FILE] in [dataDir] holds, true where it holds none or cannot be read, as it was. */
internal fun carriedOver(dataDir: Path): Boolean {
    val file = dataDir.resolve(CARRIED_FILE)
    if (!Files.isRegularFile(file)) return true
    return try {
        val kept = Json.parseToJsonElement(file.readText()) as? JsonObject
        (kept?.get("surfaceBlur") as? JsonPrimitive)?.booleanOrNull ?: true
    } catch (e: Exception) {
        log.warn("The blur kept in {} could not be read, so planes blur", file, e)
        true
    }
}

private val log = LoggerFactory.getLogger("hivens.look.surface.nx.Module")
