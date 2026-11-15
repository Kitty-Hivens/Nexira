package hivens.ui.components

import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.SourceView
import hivens.source.mirror.MirrorPackSource
import hivens.source.mirror.MirrorSourceModule
import hivens.source.modrinth.ModrinthPackSource
import hivens.source.modrinth.ModrinthSourceModule
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.flowSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's data with the two sources the build carries brought in, the way a host
 * lists them, for a sheet that draws a source badge outside the launcher.
 */
internal fun loadedSources(): WidgetDataRegistry = WidgetDataRegistry().apply {
    register(
        CatalogueKeys.Sources,
        flowSource(
            MutableStateFlow(
                listOf(
                    SourceView(MirrorPackSource.id, MirrorSourceModule.ID, MirrorPackSource.label),
                    SourceView(ModrinthPackSource.id, ModrinthSourceModule.ID, ModrinthPackSource.label),
                ),
            ),
        ),
    )
}
