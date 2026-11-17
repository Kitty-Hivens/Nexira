package hivens.widget.api

/**
 * The widget ABI a build speaks.
 *
 * A widget compiled into a jar carries calls into compose-runtime with the
 * calling convention its compiler generated: a Composer parameter, change flags,
 * group keys. None of that is stable across Compose versions, so a module built
 * against one launcher can fail to link against the next, or -- worse -- link and
 * misbehave. There is no compatibility to be had here, only an honest refusal.
 *
 * So a loadable module declares the version it was built against and the loader
 * refuses anything else, saying so. Bump this whenever the kernel's shape or the
 * Compose runtime under it changes in a way a compiled module would notice: the
 * ABI dumps of :widget-api and :widget-model change with every such change, and a
 * changed dump is the cue. The module builds stamp their manifests from the
 * `nexira.widgetApi` Gradle property, which a test holds equal to this.
 *
 * 2: source and command keys gained a level, so their constructors changed.
 * 3: a module declares settings domains through its keys, and [ModuleStrings] carries a
 *    module's words for the language module instead of looking them up. A theme a module
 *    brings is the themes module's contribution, `hivens.themes.api.ThemeContribution`, and
 *    no longer the kernel's, and a module compiled in declares the kinds of contribution its
 *    API adds ([CompiledModule.contributionKinds]). [SettingsDomain.imported] is handed the
 *    value a domain holds, so taking a choice back from the former settings file keeps what
 *    that file never held. The host hands the modules it trusts what the modules brought in
 *    contribute of the kinds the compiled modules take, compiled modules' contributions
 *    among them ([TakenContributions], [TakenContribution]): the catalogue module's sources
 *    reach it that way. A trusted module's service maker gets another module's view of a
 *    level's services ([ServiceContext.servicesOf]), to run that module's code with what
 *    that module may use. A module compiled in answers some of its app sources before the
 *    host is up ([CompiledModule.boot], [BootAnswers], [BootContext], [BootValues]), and
 *    makes what it owns from the host's data directory ([HostFiles]), naming a file of its
 *    own a newer build wrote in its own words, and the words every owner ships
 *    ([TakenWords]), both host services. Before the host is up it is handed what the modules
 *    compiled in carry of the kinds they take as well ([BootContext.services]), for the look
 *    manager to open the window the launcher boots in with the providers the choice names,
 *    and another compiled module's view of that context ([BootContext.ofModule]), the twin of
 *    [ServiceContext.servicesOf], to run that module's code with that module's settings.
 *    A module compiled in ships its words as files the way a jar does and reads them with
 *    [ModuleStrings.resources], in the languages the launcher ships
 *    ([ModuleStrings.SHIPPED_LANGUAGES]), through the reader the loader uses for a jar's
 *    ([ModuleStrings.read]).
 */
object WidgetApi {
    const val VERSION: Int = 3

    /** Jar manifest attribute carrying [VERSION]. */
    const val MANIFEST_VERSION = "Nexira-Widget-Api"

    /** Jar manifest attribute carrying the module's stable id. */
    const val MANIFEST_ID = "Nexira-Module-Id"

    /** Jar manifest attribute carrying the human-readable name. Optional. */
    const val MANIFEST_NAME = "Nexira-Module-Name"

    /**
     * Jar manifest attribute naming the module APIs the jar was compiled against, as
     * `id=major.minor` entries separated by commas (see [hivens.widget.model.ApiVersion.parseAll]).
     * Optional: a module that uses no other module's API leaves it out. The loader
     * refuses a jar that names an API nothing here offers, or offers in a version that
     * does not serve the one it was built against.
     */
    const val MANIFEST_REQUIRES = "Nexira-Module-Requires"
}
