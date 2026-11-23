package hivens.ui.widgets.modules

import hivens.accounts.AccountsModule
import hivens.catalogue.CatalogueModule
import hivens.i18n.I18nModule
import hivens.look.colour.api.ColourModule
import hivens.look.colour.nx.controls.ColourNxControls
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
import hivens.look.surface.nx.controls.SurfaceNxControls
import hivens.look.timing.api.TimingModule
import hivens.look.timing.nx.TimingNxModule
import hivens.packs.PacksModule
import hivens.source.mirror.MirrorSourceModule
import hivens.source.modrinth.ModrinthSourceModule
import hivens.themes.ThemesModule
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.model.ApiVersion

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
        SurfaceModule, SurfaceNxModule, SurfaceNxControls,
        TimingModule, TimingNxModule,
        PacksModule, AccountsModule, CatalogueModule, MirrorSourceModule, ModrinthSourceModule,
    )

/**
 * The kinds of contribution read out of every jar: the ones the widget kernel declares and
 * the ones the APIs of [modules] add (see [CompiledModule.contributionKinds]).
 */
fun offeredContributionKinds(modules: List<CompiledModule> = CompiledModules): List<Class<out ModuleContribution>> =
    (ModuleContribution.kinds + modules.flatMap { it.contributionKinds }).distinct()

/** The module APIs [modules] publish, which a jar may have been compiled against. */
fun offeredApis(modules: List<CompiledModule> = CompiledModules): Map<String, ApiVersion> =
    modules.fold(emptyMap()) { all, module -> all + module.provides }

/**
 * The packages each module API [modules] publish lives in, by API id: what a jar that
 * names the API in its manifest is shown of the application, beside the widget kernel.
 * Each module declares them beside the API itself (see [CompiledModule.apiPackages]).
 */
fun offeredApiPackages(modules: List<CompiledModule> = CompiledModules): Map<String, Set<String>> =
    modules.fold(emptyMap()) { all, module -> all + module.apiPackages }
