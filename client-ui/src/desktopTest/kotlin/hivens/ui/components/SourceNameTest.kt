package hivens.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.SourceView
import hivens.core.data.PackOrigin
import hivens.i18n.api.LocalTranslator
import hivens.i18n.take
import hivens.launcher.sources.EngineSources
import hivens.source.mirror.MirrorPackSource
import hivens.source.mirror.MirrorSourceModule
import hivens.ui.i18n.NexiraStrings
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.ModuleStrings
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.flowSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a pack's badge calls its source: the core's own two by the core, a loaded source
 * by its module, and one no module brings by its id with a word that it is not loaded,
 * so a pack from a source module that is gone still lists and says why.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SourceNameTest {

    private val mirror = SourceView(MirrorPackSource.id, MirrorSourceModule.ID, MirrorPackSource.label)
    private val shelf = SourceView("Shelf", "test.shelf", "shelf.name")

    /** [origin]'s name with [loaded] brought in, or with no catalogue module at all where it is null. */
    private fun named(origin: PackOrigin, loaded: List<SourceView>?, language: String = "en"): String {
        val keys = WidgetDataRegistry().apply { if (loaded != null) register(CatalogueKeys.Sources, flowSource(MutableStateFlow(loaded))) }
        val words = NexiraStrings.engine(null, language).apply {
            take(MirrorSourceModule.id, MirrorSourceModule.strings)
            take("test.shelf", ModuleStrings(mapOf("en" to mapOf("shelf.name" to "The Shelf"))))
        }.current.value
        var out = ""
        val scene = ImageComposeScene(10, 10) {
            CompositionLocalProvider(LocalWidgetDataRegistry provides keys, LocalTranslator provides words) { out = sourceName(origin) }
        }
        scene.render()
        scene.close()
        return out
    }

    @Test
    fun `a source no module brings is named by its id and said not to be loaded`() {
        assertEquals("Curseforge (not loaded)", named(PackOrigin("Curseforge"), listOf(mirror)))
        assertEquals("Curseforge (не загружен)", named(PackOrigin("Curseforge"), listOf(mirror), "ru"))
        assertEquals("Unknown (not loaded)", named(PackOrigin("Unknown"), listOf(mirror)), "what an older build folded an origin into")
    }

    @Test
    fun `a loaded source is named by its module`() {
        assertEquals("The Shelf", named(PackOrigin("Shelf"), listOf(mirror, shelf)))
        assertEquals("Hivens", named(EngineSources.Mirror, listOf(mirror)), "the mirror is called what its module calls it, as Browse calls it")
    }

    @Test
    fun `without the catalogue module every source but the core's own is missing`() {
        assertEquals("Mirror (not loaded)", named(EngineSources.Mirror, null))
        assertEquals("Local", named(PackOrigin.Local, null))
        assertEquals("SmartyCraft", named(PackOrigin.Smartycraft, null))
    }
}
