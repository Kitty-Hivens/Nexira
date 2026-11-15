package hivens.ui.feature.catalogue.browse

import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.ProjectKind
import hivens.i18n.api.Strings

// What Browse is searching lives in the catalogue module, one per window, read through
// CatalogueKeys.Browse and changed through its commands. What is left here is how the
// built-in widgets name it.

internal fun kindLabel(kind: ProjectKind?, s: Strings): String = when (kind) {
    null -> s.text("browseKindPacks")
    ProjectKind.Mod -> s.text("contentFilterMods")
    ProjectKind.ResourcePack -> s.text("contentFilterResourcePacks")
    ProjectKind.ShaderPack -> s.text("contentFilterShaderPacks")
}

internal fun BrowseSort.label(s: Strings): String = when (this) {
    BrowseSort.Relevance -> s.text("browseSortRelevance")
    BrowseSort.Downloads -> s.text("browseSortDownloads")
    BrowseSort.Follows -> s.text("browseSortFollows")
    BrowseSort.Newest -> s.text("browseSortNewest")
    BrowseSort.Updated -> s.text("browseSortUpdated")
}
