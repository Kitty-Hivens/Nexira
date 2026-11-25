package hivens.ui.navigation

import hivens.catalogue.api.CatalogueBuildRef
import hivens.catalogue.api.CataloguePackRef
import hivens.catalogue.api.CataloguePages
import hivens.catalogue.api.ProjectTarget
import hivens.packs.api.PackPages
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.PageValue
import hivens.widget.model.Show
import kotlinx.serialization.builtins.serializer

/**
 * The configs of the launcher's own composition, and how each is opened. A screen somebody made is a config too, by
 * the id it has in the layout graph.
 */
object LauncherConfigs {
    /** The composition they are configs of. */
    val Composition = CompositionName("launcher")

    val Home = ConfigId("home")
    val Library = ConfigId("library")
    val Browse = ConfigId("browse")
    val Profile = ConfigId("profile")
    val Wardrobe = ConfigId("wardrobe")
    val Settings = ConfigId("settings")
    val Themes = ConfigId("theme.picker")
    val About = ConfigId("about")
    val Background = ConfigId("background")

    /** An installed pack's page. */
    val Pack = ConfigId("pack")

    /** The builds of an installed pack: what it kept, what changed in each, and the way to switch. */
    val PackVersions = ConfigId("pack.versions")

    /** A pack of a catalogue source, installed or not. */
    val CataloguePack = ConfigId("catalogue.pack")

    /**
     * A project's page. A config of its own and not a panel, because what it says of the project stands in the right
     * rail, which every screen has, so in place it would have nowhere to stand.
     */
    val Project = ConfigId("project")

    /** One build of a project: what it runs on, what it needs, what changed and which files it ships. */
    val Build = ConfigId("project.build")

    val all: Set<ConfigId> = setOf(
        Home, Library, Browse, Profile, Wardrobe, Settings, Themes, About, Background,
        Pack, PackVersions, CataloguePack, Project, Build,
    )

    /** [config] the way an entry of a rail opens it: over home, so back from it comes home. */
    fun section(config: ConfigId): Show = Show(config, over = Home)

    fun pack(id: String): Show = Show(Pack, mapOf(PackPages.Pack.of(id)))

    fun packVersions(id: String): Show = Show(PackVersions, mapOf(PackPages.Pack.of(id)))

    /** The catalogue opened from inside a pack, which every install goes into. */
    fun browseInto(packId: String): Show = Show(Browse, mapOf(CataloguePages.Into.of(packId)))

    fun cataloguePack(origin: String, packId: String): Show =
        Show(CataloguePack, mapOf(CataloguePages.Pack.of(CataloguePackRef(origin, packId))))

    fun project(target: ProjectTarget): Show = Show(Project, mapOf(CataloguePages.Project.of(target)))

    /** A build of the project [target], which the rail goes on describing and an install from here goes by. */
    fun build(target: ProjectTarget, versionId: String, versionNumber: String): Show =
        Show(Build, mapOf(CataloguePages.Project.of(target), CataloguePages.Build.of(CatalogueBuildRef(versionId, versionNumber))))
}

/**
 * The section a pack's page has its settings open on, by name, or none where they are shut. The page notes it on its
 * own entry before it leads away from the settings, so back lands in them and not on the bare page.
 */
val PackSettingsPage: PageValue<String> = PageValue("pack.page.settings", String.serializer())
