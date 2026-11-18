// The module is compiled into the application and trusted with the host's own services: the data
// directory the person's earlier choice is kept in.
@file:OptIn(HostApi::class)

package hivens.look.motion.nx

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.motion.api.Motion
import hivens.look.motion.api.MotionAnswers
import hivens.look.motion.api.MotionApi
import hivens.ui.theme.nxMotionAnswers
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

/**
 * The nx motion provider as the application carries it: compiled in, and brought in by the look
 * manager where the person's choice names nx for motion.
 *
 * It answers the motion axis ([NxMotionProvider]) and keeps whether the person asked for less
 * movement in its own settings ([NxMotionSettingsDomain]), which its keys ([MotionNxKeys]) read and
 * change. Nothing of it is the host's.
 */
object MotionNxModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.motion.nx"

    /** The family it answers the motion axis as, which a pack names it by. */
    const val FAMILY: String = "nx"

    override val id: String = ID
    override val name: String = "motion.nx.name"
    override val strings: ModuleStrings = ModuleStrings.resources(MotionNxModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(MotionNxKeysContribution, NxMotionProvider)
}

/** What the nx motion provider keeps of the person's choice. */
@Serializable
data class NxMotionSettings(
    /**
     * Whether the movement that only decorates holds still: an arrival a widget declares, the order a
     * slot's widgets arrive in, a hover's wash, a sheet's travel, the look's own change, drifting
     * particles. What a widget's node says of its own arrival is the node's and plays all the same.
     * Null until said here, and then the choice the launcher kept before this module had its own.
     */
    val reduceMotion: Boolean? = null,
)

/**
 * The provider's settings, in `motion.nx.json` under the settings folder.
 *
 * Reduce motion was kept in `customization.json` before the provider had settings of its own. Where
 * this domain has said nothing yet, the choice kept there holds, and the module writes it here the
 * first time it opens, so it is kept from then on whatever becomes of that file.
 */
object NxMotionSettingsDomain : SettingsDomain<NxMotionSettings>("motion.nx", NxMotionSettings.serializer()) {
    override fun defaults(): NxMotionSettings = NxMotionSettings()
}

/** The keys the nx motion provider answers, all at the app level. The ids are constants so a widget can name them in its needs. */
object MotionNxKeys {
    const val SETTINGS_ID = "motion.nx.settings"
    const val UPDATE_SETTINGS_ID = "motion.nx.settings.update"
    const val REDUCE_MOTION_ID = "motion.nx.reduce"
    const val SET_REDUCE_MOTION_ID = "motion.nx.reduce.set"

    /** The provider's settings, as stored. */
    val Settings = SourceKey<NxMotionSettings>(SETTINGS_ID, ScopeLevel.App)

    /** Changes the provider's settings. */
    val UpdateSettings = CommandKey<SettingsChange<NxMotionSettings>>(UPDATE_SETTINGS_ID, ScopeLevel.App)

    /** Whether the movement that only decorates holds still now, with the choice carried over where none was made here. */
    val ReduceMotion = SourceKey<Boolean>(REDUCE_MOTION_ID, ScopeLevel.App)

    /** Asks for less movement, or for all of it again. */
    val SetReduceMotion = CommandKey<Boolean>(SET_REDUCE_MOTION_ID, ScopeLevel.App)
}

/** The provider's keys, answered over its [NxMotionChoice]. */
internal object MotionNxKeysContribution : KeyContribution {
    override val id: String = "motion.nx.keys"
    override val label: String = "nx motion"

    override fun declare(keys: ModuleKeys) {
        keys.settings(NxMotionSettingsDomain, MotionNxKeys.Settings, MotionNxKeys.UpdateSettings)
        keys.service(ScopeLevel.App, ::choiceOf)
        keys.source(MotionNxKeys.ReduceMotion, sample = { false }) { app -> flowSource(app.services.get<NxMotionChoice>().reduceMotion) }
        keys.command(MotionNxKeys.SetReduceMotion) { app -> command(app.services.get<NxMotionChoice>()::set) }
    }
}

/**
 * Whether the person asked for less movement, as this module keeps it once the host is up: its own
 * setting, and where that says nothing yet, the choice the launcher kept before, which it writes into
 * its own setting at once.
 */
class NxMotionChoice internal constructor(
    private val store: SettingsStore<NxMotionSettings>,
    carried: () -> Boolean,
    scope: CoroutineScope,
) {
    private val fallback: Boolean = if (store.value.value.reduceMotion == null) carried() else false

    init {
        if (store.value.value.reduceMotion == null) {
            store.update { current -> if (current.reduceMotion == null) current.copy(reduceMotion = fallback) else current }
        }
    }

    /** Whether the movement that only decorates holds still now. */
    val reduceMotion: StateFlow<Boolean> = store.value
        .map { it.reduceMotion ?: fallback }
        .stateIn(scope, SharingStarted.Eagerly, store.value.value.reduceMotion ?: fallback)

    /** Asks for less movement where [on], or for all of it again. */
    fun set(on: Boolean) = store.update { it.copy(reduceMotion = on) }
}

private fun choiceOf(context: ServiceContext): NxMotionChoice {
    val dataDir = context.hostServices.get<HostFiles>().dataDir
    return NxMotionChoice(
        store   = context.services.get<SettingsStores>().of(NxMotionSettingsDomain),
        carried = { carriedOver(dataDir) },
        scope   = context.coroutineScope,
    )
}

/**
 * The nx family of the motion axis: nx's durations and curves, as nx-ui answers them
 * ([nxMotionAnswers]), with the movement that only decorates held still where the person asked for less.
 *
 * Before the host is up it answers from the module's own settings, and the earlier choice where they
 * say nothing yet, read the way the module reads them once up. Once up it follows the module's choice
 * as it changes, handing the other answers without being opened again.
 */
internal object NxMotionProvider : LookProvider<MotionAnswers> {
    override val axis: String = Motion.id
    override val family: String = MotionNxModule.FAMILY
    override val contract = MotionApi.VERSION
    override val label: String = "motion.nx.provider"

    override fun open(context: LookContext): LookAnswers<MotionAnswers> {
        context.boot?.let { boot -> return LookAnswers.of(nxMotionAnswers(bootReduceMotion(boot))) }
        val choice = context.services.get<NxMotionChoice>()
        val answers = choice.reduceMotion
            .map(::nxMotionAnswers)
            .stateIn(context.coroutineScope, SharingStarted.Eagerly, nxMotionAnswers(choice.reduceMotion.value))
        return LookAnswers.following(answers)
    }
}

/** Whether the person asked for less movement, as the window drawn before the host is up reads it. */
internal fun bootReduceMotion(boot: BootContext): Boolean =
    boot.settings(NxMotionSettingsDomain).reduceMotion ?: carriedOver(boot.dataDir)

/** The file reduce motion was kept in before the provider had settings of its own. */
internal const val CARRIED_FILE = "customization.json"

/** The reduce motion [CARRIED_FILE] in [dataDir] holds, false where it holds none or cannot be read. */
internal fun carriedOver(dataDir: Path): Boolean {
    val file = dataDir.resolve(CARRIED_FILE)
    if (!Files.isRegularFile(file)) return false
    return try {
        val kept = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
        (kept?.get("reduceMotion") as? JsonPrimitive)?.booleanOrNull ?: false
    } catch (e: Exception) {
        log.warn("The reduce motion kept in {} could not be read, so motion is not reduced", file, e)
        false
    }
}

private val log = LoggerFactory.getLogger("hivens.look.motion.nx.Module")
