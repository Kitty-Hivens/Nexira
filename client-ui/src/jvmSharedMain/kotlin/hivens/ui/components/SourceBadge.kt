package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.catalogue.api.CatalogueKeys
import hivens.core.api.dto.smrt.SmrtSource
import hivens.core.data.PackOrigin
import hivens.i18n.api.strings
import hivens.ui.i18n.nexiraStrings
import hivens.ui.nx.NxSourceBadge
import hivens.ui.theme.originColor
import hivens.ui.theme.sourceColor
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull

// Provenance pill wrappers: map the two domain types (a pack's [PackOrigin]
// and a mirror entry's [SmrtSource]) onto the neutral [NxSourceBadge], so the
// label table and brand colour live in one place instead of three.

@Composable
fun SourceBadge(origin: PackOrigin, modifier: Modifier = Modifier) {
    NxSourceBadge(label = sourceName(origin), color = originColor(origin), modifier = modifier)
}

/** The pill for a source by its id, the way the packs and catalogue APIs carry it. */
@Composable
fun SourceBadge(origin: String, modifier: Modifier = Modifier) = SourceBadge(PackOrigin(origin), modifier)

/**
 * What a pack's source is called. A local and a SmartyCraft pack are the core's own and
 * named here. Any other source is named the way the catalogue lists it, in the words of
 * the module that brings it, and a source no module brings is named by its id and said
 * not to be loaded: the pack still lists, opens and plays, and this is why its source has
 * no page.
 */
@Composable
fun sourceName(origin: PackOrigin): String {
    val s = nexiraStrings()
    // Absent without the catalogue module, and then no source is loaded.
    val declared = rememberScopedSource(CatalogueKeys.Sources).valueOrNull()?.firstOrNull { it.id == origin.id }
    return when {
        origin == PackOrigin.Local -> "Local"
        origin == PackOrigin.Smartycraft -> "SmartyCraft"
        declared == null -> s.text("packSourceMissing", origin.id)
        else -> strings(declared.module).text(declared.label)
    }
}

@Composable
fun SourceBadge(source: SmrtSource, modifier: Modifier = Modifier) {
    val label = when (source) {
        is SmrtSource.Modrinth   -> "Modrinth"
        is SmrtSource.SmrtCache  -> "Mirror"
        is SmrtSource.SmrtStatic -> "Static"
        is SmrtSource.CurseForge -> "CurseForge"
        is SmrtSource.Github     -> "GitHub"
        is SmrtSource.Unknown    -> "Unknown"
    }
    NxSourceBadge(label = label, color = sourceColor(source), modifier = modifier)
}
