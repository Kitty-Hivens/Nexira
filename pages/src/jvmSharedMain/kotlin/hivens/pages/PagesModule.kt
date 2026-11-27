// The pages module is compiled into the application and reads what the modules brought in contribute through the
// host's own services.
@file:OptIn(HostApi::class)

package hivens.pages

import hivens.look.motion.api.MotionApi
import hivens.pages.api.MadeScreen
import hivens.pages.api.PagesApi
import hivens.pages.api.PagesHostProps
import hivens.pages.api.pagesHost
import hivens.pages.api.pagesHostProps
import hivens.pages.api.PagesKeys
import hivens.pages.api.ScreenContribution
import hivens.pages.api.ScreenId
import hivens.pages.api.ScreenView
import hivens.widget.api.CompiledModule
import hivens.widget.api.CompositionKeys
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelContext
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.api.hostServices
import hivens.widget.api.ofKind
import hivens.widget.model.ApiVersion
import hivens.widget.model.Composition
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The pages module as the application carries it: compiled in, and brought in by a document that names [PagesApi.ID],
 * or by the history module, which requires it. It takes the screens the modules contribute.
 */
object PagesModule : CompiledModule {
    override val id: String = PagesApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(PagesModule::class.java, PagesApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(PagesApi.ID to PagesApi.VERSION)

    // The host swaps pages with the kit's swap, which reads its timing from the look's motion.
    override val uses: Map<String, ApiVersion> = mapOf(MotionApi.ID to MotionApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(PagesApi.ID to setOf(PagesApi::class.java.name.substringBeforeLast('.')))
    override val contributionKinds: List<Class<out ModuleContribution>> = listOf(ScreenContribution::class.java)
    override val contributions: List<ModuleContribution> = listOf(PagesKeysContribution)
    override val widgets: WidgetRegistry get() = pagesWidgets

    private const val NAME = "pages.name"
}

/** [PagesKeys.Screens] and [PagesKeys.Start], per composition, from the screens contributed and the composition's host. */
object PagesKeysContribution : KeyContribution {
    override val id: String = "pages.keys"
    override val label: String = "Pages"

    override fun declare(keys: ModuleKeys) {
        keys.source(PagesKeys.Screens, sample = { emptyList() }) { composition ->
            val taken = composition.hostServices.find<TakenContributions>()?.all ?: MutableStateFlow(emptyList())
            val record = composition.record()
            val screens = combine(taken, record) { all, own -> screensOf(all.ofKind(), own?.pagesHost()) }
            flowSource(screens.stateIn(composition.coroutineScope, SharingStarted.Eagerly, screensOf(taken.value.ofKind(), record.value?.pagesHost())))
        }
        keys.source(PagesKeys.Start, sample = { null }) { composition ->
            val screens = composition.find(PagesKeys.Screens)?.state ?: MutableStateFlow(emptyList())
            val record = composition.record()
            val start = combine(screens, record) { all, own -> startOf(all, own?.pagesHost()) }
            flowSource(start.stateIn(composition.coroutineScope, SharingStarted.Eagerly, startOf(screens.value, record.value?.pagesHost())))
        }
    }
}

/** The composition's own record, or nothing where whoever draws it hands none. */
private fun LevelContext.record(): StateFlow<Composition?> =
    find(CompositionKeys.Record)?.state?.let { flow -> flow.map { it }.stateIn(coroutineScope, SharingStarted.Eagerly, flow.value) }
        ?: MutableStateFlow(null)

/**
 * The screens the modules brought, each once, the first to bring an id keeping it, under what the person called them
 * in [host] where they did, and then the ones the person made there, each under its own id where no module brought it.
 */
internal fun screensOf(contributed: List<TakenContribution<ScreenContribution>>, host: WidgetInstance?): List<ScreenView> {
    val props = host?.pagesHostProps() ?: PagesHostProps()
    val named = props.named.associateBy(MadeScreen::id)
    val brought = contributed.distinctBy { it.item.id }.map { taken ->
        val own = named[taken.item.id]
        ScreenView(
            id    = ScreenId(taken.item.id),
            title = own?.title?.ifBlank { null } ?: taken.item.label,
            owner = if (own?.title.isNullOrBlank()) taken.moduleId else null,
            icon  = own?.icon?.ifBlank { null } ?: taken.item.icon,
            kind  = taken.item.kind,
        )
    }
    val taken = brought.mapTo(HashSet()) { it.id.value }
    val made = props.made.filter { it.id.isNotBlank() && it.id !in taken }.distinctBy(MadeScreen::id)
    return brought + made.map { ScreenView(ScreenId(it.id), it.title, owner = null, icon = it.icon.ifBlank { null }, made = true) }
}

/** The screen [host] starts on where it names one there is, or the first of [screens]. */
internal fun startOf(screens: List<ScreenView>, host: WidgetInstance?): ScreenId? {
    val named = host?.pagesHostProps()?.start?.takeIf { it.isNotBlank() }?.let(::ScreenId)
    return named?.takeIf { id -> screens.any { it.id == id } } ?: screens.firstOrNull()?.id
}
