package hivens.catalogue.api

import hivens.pages.api.PageValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/** A pack of a source: the source's id and the pack's id there. */
@Serializable
data class CataloguePackRef(val origin: String, val packId: String)

/**
 * One build of a project. [versionNumber] rides along because whoever opens the build has it, and a trail naming the
 * page needs it before anything is read.
 */
@Serializable
data class CatalogueBuildRef(val versionId: String, val versionNumber: String)

/**
 * What an opening hands a page of the catalogue. Declared by the catalogue module, so read as missing where a document
 * names none.
 */
object CataloguePages {
    const val PROJECT_ID: String = "catalogue.page.project"
    const val BUILD_ID: String = "catalogue.page.build"
    const val PACK_ID: String = "catalogue.page.pack"
    const val INTO_ID: String = "catalogue.page.into"

    /** The project the page is about. A target rather than an id, so a file the catalogue never indexed has a page too. */
    val Project: PageValue<ProjectTarget> = PageValue(PROJECT_ID, ProjectTarget.serializer())

    /** The build of [Project] the page is about. */
    val Build: PageValue<CatalogueBuildRef> = PageValue(BUILD_ID, CatalogueBuildRef.serializer())

    /** The catalogue's pack the page is about. */
    val Pack: PageValue<CataloguePackRef> = PageValue(PACK_ID, CataloguePackRef.serializer())

    /** The pack a browse was opened from and installs into, by its id. None where it was opened on its own. */
    val Into: PageValue<String> = PageValue(INTO_ID, String.serializer())
}
