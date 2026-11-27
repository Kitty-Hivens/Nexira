package hivens.ui.navigation

import hivens.catalogue.api.CatalogueBuildRef
import hivens.catalogue.api.CataloguePackRef
import hivens.catalogue.api.CataloguePages
import hivens.catalogue.api.ProjectTarget
import hivens.packs.api.PackPages
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.pages.api.PageValue
import hivens.pages.api.ScreenId
import hivens.pages.api.Show
import kotlinx.serialization.builtins.serializer

/** The launcher's own compositions, and the console's one config. */
object LauncherConfigs {
    /** The composition the launcher's window draws. */
    val Composition = CompositionName("launcher")

    /** The console's composition, in a window of its own, and its one config. */
    val ConsoleComposition = CompositionName("console")
    val Console = ConfigId("console")
}

/** The screens of the launcher's own composition, and how each is opened. A screen somebody made is one too, by its id. */
object LauncherScreens {
    val Home = ScreenId("home")
    val Library = ScreenId("library")
    val Browse = ScreenId("browse")
    val Profile = ScreenId("profile")
    val Wardrobe = ScreenId("wardrobe")
    val Settings = ScreenId("settings")
    val Themes = ScreenId("theme.picker")
    val About = ScreenId("about")
    val Background = ScreenId("background")

    /** An installed pack's page. */
    val Pack = ScreenId("pack")

    /** The builds of an installed pack: what it kept, what changed in each, and the way to switch. */
    val PackVersions = ScreenId("pack.versions")

    /** A pack of a catalogue source, installed or not. */
    val CataloguePack = ScreenId("catalogue.pack")

    /**
     * A project's page. A config of its own and not a panel, because what it says of the project stands in the right
     * rail, which every screen has, so in place it would have nowhere to stand.
     */
    val Project = ScreenId("project")

    /** One build of a project: what it runs on, what it needs, what changed and which files it ships. */
    val Build = ScreenId("project.build")

    val all: Set<ScreenId> = setOf(
        Home, Library, Browse, Profile, Wardrobe, Settings, Themes, About, Background,
        Pack, PackVersions, CataloguePack, Project, Build,
    )

    /** [screen] the way an entry of a rail opens it: over home, so back from it comes home. */
    fun section(screen: ScreenId): Show = Show(screen, over = Home)

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

/** The config of the launcher's composition a screen is drawn as, by the same id, while each screen is a config. */
internal fun ScreenId.asConfig(): ConfigId = ConfigId(value)

/** The screen a config of the launcher's composition draws, by the same id, while each screen is a config. */
internal fun ConfigId.asScreen(): ScreenId = ScreenId(value)
