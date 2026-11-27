package hivens.ui.widgets

import hivens.accounts.api.AccountsApi
import hivens.catalogue.api.CatalogueApi
import hivens.i18n.api.I18nApi
import hivens.look.colour.nx.controls.ColourNxControls
import hivens.look.manager.api.LookApi
import hivens.look.motion.nx.controls.MotionNxControls
import hivens.packs.api.PacksApi
import hivens.source.mirror.MirrorSourceModule
import hivens.source.modrinth.ModrinthSourceModule
import hivens.themes.api.ThemesApi
import hivens.ui.HostKeys
import androidx.compose.runtime.Composable
import hivens.ui.background.BackgroundSettings
import hivens.ui.editor.DocumentAdmission
import hivens.ui.moduleKeys
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.ui.widgets.modules.CompiledModules
import hivens.widget.api.ContributedServices
import hivens.widget.api.KeyDeclaration
import hivens.widget.api.KeyKind
import hivens.widget.api.KeyOrigin
import hivens.pages.api.PageKeys
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.CommandKey
import hivens.widget.model.Document
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.ScopeLevel
import hivens.widget.model.StandardDocument
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.contentAt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import hivens.history.api.HistoryApi
import hivens.pages.api.PagesApi

/**
 * The ids widgets name in their needs, the keys the launcher declares, and the
 * screens that say they hand them, held together. Each is a string in a
 * different file, and a typo in any of them does not fail anything at runtime: the
 * widget silently leaves the palette, or draws the placeholder on its own page.
 */
class KeyDeclarationDriftTest {

    // The registries the launcher's module builds, from stand-in flows, with the keys of
    // the modules the bundled document names, the way the launcher brings them in.
    private val data = uiDataRegistry(
        activity         = MutableStateFlow(emptyList()),
        notifications    = MutableStateFlow(emptyList()),
        doNotDisturb     = MutableStateFlow(false),
        sslBypass        = flowSource(MutableStateFlow(false)),
        background       = MutableStateFlow(BackgroundSettings()),
    )

    private val commands = uiCommandRegistry({}, {}, {})

    init {
        moduleKeys(
            keys     = HostKeys(data, commands, ContributedServices()),
            named    = StandardDocument.load().modules.toSet(),
            compiled = CompiledModules,
            jars     = emptyList(),
        )
    }

    private fun declarationOf(id: String): KeyDeclaration? = data.declarationOf(id) ?: commands.declarationOf(id)

    private val declared: List<KeyDeclaration> = (data.keys() + commands.keys()).mapNotNull(::declarationOf)

    @Test
    fun `every need of every built-in widget names a key the launcher declares`() {
        val unknown = GeneratedWidgetRegistry.all().values.flatMap { descriptor ->
            descriptor.needs.filter { declarationOf(it) == null }.map { "${descriptor.kind.value} needs '$it'" }
        }
        assertEquals(emptyList(), unknown, "these widgets would be kept off every surface and drawn as misplaced everywhere")
    }

    @Test
    fun `every show condition of every widget the build carries names a source the launcher declares`() {
        val widgets = GeneratedWidgetRegistry.all().values + CompiledModules.flatMap { it.widgets.all().values }
        val unknown = widgets.mapNotNull { descriptor ->
            val condition = descriptor.shownWhen ?: return@mapNotNull null
            "${descriptor.kind.value} is shown under '$condition'".takeIf { declarationOf(condition)?.kind != KeyKind.Source }
        }
        assertEquals(emptyList(), unknown, "these widgets would be hidden on every surface")
    }

    @Test
    fun `the standard document names the language, themes, look, packs, accounts and catalogue modules, the two sources, the pages and the history, so a fresh install has their keys`() {
        assertEquals(
            listOf(I18nApi.ID, ThemesApi.ID, LookApi.ID, ColourNxControls.ID, MotionNxControls.ID, PacksApi.ID, AccountsApi.ID, CatalogueApi.ID, MirrorSourceModule.ID, ModrinthSourceModule.ID, PagesApi.ID, HistoryApi.ID),
            StandardDocument.load().modules,
        )
        assertTrue(CompiledModules.any { it.id == PacksApi.ID }, "and the build carries the packs module")
        assertTrue(CompiledModules.any { it.id == AccountsApi.ID }, "and the accounts module")
        assertTrue(CompiledModules.any { it.id == CatalogueApi.ID }, "and the catalogue module")
        assertTrue(CompiledModules.any { it.id == I18nApi.ID }, "and the language module")
        assertTrue(CompiledModules.any { it.id == ThemesApi.ID }, "and the themes module")
        assertTrue(CompiledModules.any { it.id == LookApi.ID }, "and the look manager")
        assertTrue(CompiledModules.any { it.id == ColourNxControls.ID }, "and the colour provider's controls")
        assertTrue(CompiledModules.any { it.id == MotionNxControls.ID }, "and the motion provider's controls")
        assertTrue(CompiledModules.any { it.id == MirrorSourceModule.ID }, "and the mirror source")
        assertTrue(CompiledModules.any { it.id == ModrinthSourceModule.ID }, "and the Modrinth source")
        assertTrue(CompiledModules.any { it.id == PagesApi.ID }, "and the pages module")
        assertTrue(CompiledModules.any { it.id == HistoryApi.ID }, "and the history module")
    }

    @Test
    fun `every key a built-in widget needs below the app has a sample to be shown on`() {
        val bare = GeneratedWidgetRegistry.all().values.flatMap { descriptor ->
            descriptor.needs
                .filter { id -> declarationOf(id)?.level?.let { it != ScopeLevel.App } == true }
                .filter { id -> (data.sampleOf(id) ?: commands.sampleOf(id)) == null }
                .map { "${descriptor.kind.value} needs '$it'" }
        }
        assertEquals(emptyList(), bare, "the palette would show these as a letter, and the editor as a warning card")
    }

    // The launcher update check is the desktop's own, added by its host. A phone adds
    // nothing, so there the key is absent, and a widget that needs it is kept off
    // rather than drawn with a button that does nothing.
    @Test
    fun `every app command in the catalog is answered by the shared registry, except the host's own`() {
        val catalog = Commands::class.java.declaredMethods
            .filter { it.parameterCount == 0 && CommandKey::class.java.isAssignableFrom(it.returnType) }
            .map { it.invoke(Commands) as CommandKey<*> }
        assertTrue(catalog.isNotEmpty())
        val unanswered = catalog.filter { it.level == ScopeLevel.App && it.id !in commands.keys() }.map { it.id }
        assertEquals(listOf(Commands.CheckUpdate.id), unanswered)
        assertEquals(null, commands.declarationOf(Commands.CheckUpdate.id), "absent, not answered by something that does nothing")
        val needy = GeneratedWidgetRegistry.all().keys.first()
        val admission = DocumentAdmission(::declarationOf, { kind -> if (kind == needy) Needs(listOf(Commands.CheckUpdate.id)) else GeneratedWidgetRegistry[kind] })
        val standard = StandardDocument.load()
        val anywhere = NodePath(standard.start, NodeRoot.Layout(standard.compositions.getValue(standard.start).start, standard.compositions.getValue(standard.start).startConfig!!.layouts.first().id))
        assertEquals(listOf(Commands.CheckUpdate.id), admission.missing(needy, anywhere, standard), "a widget that needs it is kept off where no host answers it")
    }

    @Test
    fun `every key a page's owner hands is declared by the screen that hands it, and only by one`() {
        val handedOver = declared.filter { it.level == ScopeLevel.Page && it.origin == KeyOrigin.Owner }.map { it.id }.toSet()
        val listed = GeneratedWidgetRegistry.all().values.flatMap { it.hands }
        assertEquals(handedOver, listed.toSet() + PageKeys.Entry.id, "a key a page hands that no screen declares keeps its widgets off that page too")
        assertEquals(listed.size, listed.toSet().size, "a key is handed by one screen")
    }

    @Test
    fun `the standard document puts no widget where something it needs has no answer`() {
        val standard = StandardDocument.load()
        val admission = DocumentAdmission(::declarationOf, ::descriptorOf, everyPage = setOf(PageKeys.Entry.id))
        val misplaced = standard.compositions.flatMap { (name, composition) ->
            composition.configs.flatMap { (id, config) ->
                config.layouts.flatMap { layout -> standing(standard, NodePath(name, NodeRoot.Layout(id, layout.id))).toList() }
            }
        }.mapNotNull { (path, widget) ->
            admission.missing(widget.kind, path, standard).takeIf { it.isNotEmpty() }?.let { "${widget.kind.value} at $path: $it" }
        }
        assertTrue(misplaced.isEmpty(), "a fresh install would draw these as misplaced: $misplaced")
    }

    /** A widget the launcher carries, its own or a compiled module's, the pages host among them. */
    private fun descriptorOf(kind: WidgetKind): WidgetDescriptor? =
        GeneratedWidgetRegistry[kind] ?: CompiledModules.firstNotNullOfOrNull { it.widgets[kind] }

    /**
     * Every widget drawn under [path], with the slot it stands in, as the document resolves each slot: the slots a
     * widget declares, and those it holds where its content names them, the pages host's screens.
     */
    private fun standing(document: Document, path: NodePath): Sequence<Pair<NodePath, WidgetInstance>> = sequence {
        document.contentAt(path)?.widgets.orEmpty().forEach { widget ->
            yield(path to widget)
            val descriptor = descriptorOf(widget.kind) ?: return@forEach
            val slots = if (descriptor.openSlots) widget.children.keys.toList() else descriptor.slots
            slots.forEach { slot -> yieldAll(standing(document, path.child(widget.instanceId, slot))) }
        }
    }

    private class Needs(override val needs: List<String>) : WidgetDescriptor {
        override val kind = WidgetKind("test.needy")
        override val displayName = "needy"
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) = Unit
    }
}
