package hivens.ui.widgets.modules

import hivens.accounts.AccountsModule
import hivens.catalogue.CatalogueModule
import hivens.i18n.I18nModule
import hivens.look.colour.api.ColourModule
import hivens.look.colour.nx.controls.ColourNxControls
import hivens.look.cursor.api.CursorModule
import hivens.look.cursor.nx.CursorNxModule
import hivens.look.cursor.system.CursorSystemModule
import hivens.look.icon.api.IconModule
import hivens.look.icon.google.rounded.IconGoogleRoundedModule
import hivens.look.manager.LookManagerModule
import hivens.look.motion.api.MotionModule
import hivens.look.motion.nx.MotionNxModule
import hivens.look.motion.nx.controls.MotionNxControls
import hivens.look.packs.LookPacksModule
import hivens.look.shape.api.ShapeModule
import hivens.look.shape.nx.ShapeNxModule
import hivens.look.stroke.api.StrokeModule
import hivens.look.stroke.nx.StrokeNxModule
import hivens.look.surface.api.SurfaceModule
import hivens.look.surface.nx.SurfaceNxModule
import hivens.look.timing.api.TimingModule
import hivens.look.timing.nx.TimingNxModule
import hivens.look.feedback.api.FeedbackModule
import hivens.look.feedback.nx.FeedbackNxModule
import hivens.look.type.api.TypeModule
import hivens.look.type.nx.TypeNxModule
import hivens.packs.PacksModule
import hivens.source.mirror.MirrorSourceModule
import hivens.source.modrinth.ModrinthSourceModule
import hivens.themes.ThemesModule
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.model.ApiVersion
import hivens.widget.model.ModuleGraph
import hivens.widget.model.ModuleNode

/**
 * The modules compiled into this build. Carrying one is not using it: the host brings
 * in a module's keys only where the document names it, the same as a jar's.
 */
val CompiledModules: List<CompiledModule> =
    listOf(
        I18nModule, ThemesModule, LookManagerModule, ColourModule, LookPacksModule, ColourNxControls,
        MotionModule, MotionNxModule, MotionNxControls,
        ShapeModule, ShapeNxModule,
        StrokeModule, StrokeNxModule,
        SurfaceModule, SurfaceNxModule,
        TimingModule, TimingNxModule,
        FeedbackModule, FeedbackNxModule,
        TypeModule, TypeNxModule,
        IconModule, IconGoogleRoundedModule,
        CursorModule, CursorSystemModule, CursorNxModule,
        PacksModule, AccountsModule, CatalogueModule, MirrorSourceModule, ModrinthSourceModule,
    )

/**
 * The kinds of contribution read out of every jar: the ones the widget kernel declares and
 * the ones the APIs of [modules] add (see [CompiledModule.contributionKinds]).
 */
fun offeredContributionKinds(modules: List<CompiledModule> = CompiledModules): List<Class<out ModuleContribution>> =
    (ModuleContribution.kinds + modules.flatMap { it.contributionKinds }).distinct()

/** What [modules] can be required by: the APIs they publish, which a jar may have been compiled against, and their ids where they state a version. */
fun offeredApis(modules: List<CompiledModule> = CompiledModules): Map<String, ApiVersion> =
    modules.fold(emptyMap()) { all, module -> all + offersOf(module) }

/** [modules] by what they require of each other. */
fun moduleGraph(modules: List<CompiledModule> = CompiledModules): ModuleGraph =
    ModuleGraph.of(modules.map { ModuleNode(it.id, offersOf(it), it.requires, it.uses) })

private fun offersOf(module: CompiledModule): Map<String, ApiVersion> =
    module.version?.let { mapOf(module.id to it) }.orEmpty() + module.provides

/**
 * The modules the host brings in where the document names [named] and the jars switched on do not run
 * without [jarsRequire]: those named, and of [compiled] what either requires, and what that requires.
 */
internal fun broughtIn(named: Set<String>, compiled: List<CompiledModule>, jarsRequire: Set<String> = emptySet()): Set<String> {
    val graph = moduleGraph(compiled)
    val asked = named + jarsRequire.mapNotNull(graph::providerOf)
    return asked + graph.requiredBy(asked)
}

/**
 * The packages each module API [modules] publish lives in, by API id: what a jar that
 * names the API in its manifest is shown of the application, beside the widget kernel.
 * Each module declares them beside the API itself (see [CompiledModule.apiPackages]).
 * A module that offers itself by its id shares no classes for it.
 */
fun offeredApiPackages(modules: List<CompiledModule> = CompiledModules): Map<String, Set<String>> =
    modules.fold(emptyMap()) { all, module -> all + (if (module.version == null) emptyMap() else mapOf(module.id to emptySet())) + module.apiPackages }
