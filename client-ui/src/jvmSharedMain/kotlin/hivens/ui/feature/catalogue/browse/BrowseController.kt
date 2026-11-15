package hivens.ui.feature.catalogue.browse

import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.ProjectKind
import hivens.ui.i18n.AppStrings

// What Browse is searching lives in the catalogue module, one per window, read through
// CatalogueKeys.Browse and changed through its commands. What is left here is how the
// built-in widgets name it.

internal fun kindLabel(kind: ProjectKind?, s: AppStrings): String = when (kind) {
    null -> s.browseKindPacks
    ProjectKind.Mod -> s.contentFilterMods
    ProjectKind.ResourcePack -> s.contentFilterResourcePacks
    ProjectKind.ShaderPack -> s.contentFilterShaderPacks
}

internal fun BrowseSort.label(s: AppStrings): String = when (this) {
    BrowseSort.Relevance -> s.browseSortRelevance
    BrowseSort.Downloads -> s.browseSortDownloads
    BrowseSort.Follows -> s.browseSortFollows
    BrowseSort.Newest -> s.browseSortNewest
    BrowseSort.Updated -> s.browseSortUpdated
}
