package hivens.ui.widgets

import hivens.accounts.api.AccountsApi
import hivens.catalogue.api.CatalogueApi
import hivens.i18n.api.I18nApi
import hivens.packs.api.PacksApi
import hivens.themes.api.ThemesApi
import hivens.ui.HostKeys
import hivens.ui.background.BackgroundSettings
import hivens.ui.editor.EditorSurfaces
import hivens.ui.editor.WidgetAdmission
import hivens.ui.moduleKeys
import hivens.ui.modulesNamedBy
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.ui.widgets.modules.CompiledModules
import hivens.widget.api.ContributedServices
import hivens.widget.api.KeyDeclaration
import hivens.widget.api.KeyOrigin
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.CommandKey
import hivens.widget.model.DefaultLayout
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ids widgets name in their needs, the keys the launcher declares, and the
 * surfaces the editor says provide them, held together. Each is a string in a
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
            named    = modulesNamedBy(DefaultLayout.load()) { DefaultLayout.modules() },
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
    fun `the bundled document names the language, themes, packs, accounts and catalogue modules, so a fresh install has their keys`() {
        assertEquals(listOf(I18nApi.ID, ThemesApi.ID, PacksApi.ID, AccountsApi.ID, CatalogueApi.ID), DefaultLayout.modules())
        assertEquals(null, DefaultLayout.load().modules, "the bundled graph itself names none, so nothing written from it freezes the list")
        assertTrue(CompiledModules.any { it.id == PacksApi.ID }, "and the build carries the packs module")
        assertTrue(CompiledModules.any { it.id == AccountsApi.ID }, "and the accounts module")
        assertTrue(CompiledModules.any { it.id == CatalogueApi.ID }, "and the catalogue module")
        assertTrue(CompiledModules.any { it.id == I18nApi.ID }, "and the language module")
        assertTrue(CompiledModules.any { it.id == ThemesApi.ID }, "and the themes module")
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

    @Test
    fun `every key a page hands its widgets is listed on the surface of that page, and only those`() {
        val handedOver = declared.filter { it.level == ScopeLevel.Page && it.origin == KeyOrigin.Owner }.map { it.id }.toSet()
        val listed = EditorSurfaces.all.flatMap { it.provides }
        assertEquals(handedOver, listed.toSet(), "a key a page provides that no surface lists keeps its widgets off that page too")
        assertEquals(listed.size, listed.toSet().size, "a key is handed over by one page")
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
        val admission = WidgetAdmission(::declarationOf) { listOf(Commands.CheckUpdate.id) }
        assertEquals(
            listOf(Commands.CheckUpdate.id),
            admission.missing(GeneratedWidgetRegistry.all().keys.first(), SurfaceId("home.new"), DefaultLayout.load()),
            "a widget that needs it is kept off where no host answers it",
        )
    }

    @Test
    fun `the bundled layout puts no widget where something it needs has no answer`() {
        val admission = WidgetAdmission(::declarationOf) { kind -> GeneratedWidgetRegistry[kind]?.needs.orEmpty() }
        val bundled = DefaultLayout.load()
        val misplaced = bundled.surfaces.flatMap { (surface, layout) ->
            layout.allSlots().flatMap { it.widgets.asSequence() }.mapNotNull { widget ->
                admission.missing(widget, surface, bundled).takeIf { it.isNotEmpty() }?.let { "${widget.kind.value} on ${surface.value}: $it" }
            }.toList()
        }
        assertTrue(misplaced.isEmpty(), "a fresh install would draw these as misplaced: $misplaced")
    }
}
