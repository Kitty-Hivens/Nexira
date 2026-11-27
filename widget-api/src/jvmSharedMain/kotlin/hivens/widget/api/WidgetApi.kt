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
 *    [ServiceContext.servicesOf], to run that module's code with that module's settings. Once
 *    the host is up, a trusted module brings in a compiled module the document does not name
 *    ([BringIn]), a host service, and a key registered while the levels live is read again by
 *    whoever found it missing.
 *    A module compiled in ships its words as files the way a jar does and reads them with
 *    [ModuleStrings.resources], in the languages the launcher ships
 *    ([ModuleStrings.SHIPPED_LANGUAGES]), through the reader the loader uses for a jar's
 *    ([ModuleStrings.read]).
 *    A module compiled in brings widgets of its own ([CompiledModule.widgets]), and every
 *    composition answers the kernel's own keys for the config it draws and the windows it owns
 *    ([CompositionKeys]). Pages and their history are modules' APIs, not the kernel's. A level
 *    stands under its owner whatever the owner's kind ([WidgetScope.child]), and a level answers
 *    a key with the value it is asked for with ([PageLevels.levelFor]).
 *    A settings domain may answer the value a launcher's first start opens on
 *    ([SettingsDomain.firstRun]), which the host writes into the domain's file then.
 *    A widget's arrival is told whether the node itself names its character ([WidgetEntrance]),
 *    since a value the node names is played as set whatever the look holds still.
 *    A module says what it does not run without and what it only uses ([CompiledModule.requires],
 *    [CompiledModule.uses], [MANIFEST_REQUIRES], [MANIFEST_USES]), offers itself by its id where
 *    it states a version, and a jar publishes APIs of its own ([MANIFEST_PROVIDES]). What a module
 *    requires is brought in with it, where [MANIFEST_REQUIRES] named only what had to be carried.
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
     * Jar manifest attribute naming what the module does not run without, as
     * `id=major.minor` entries separated by commas (see [hivens.widget.model.ApiVersion.parseAll]):
     * the module APIs it was compiled against, and modules that offer themselves by their
     * id. Optional. What it names is brought in with it, and where one is not there, is
     * switched off or is in a version that does not serve the one named, the module waits
     * (see [hivens.widget.model.ModuleGraph]). The classes of an API it names are visible to it.
     */
    const val MANIFEST_REQUIRES = "Nexira-Module-Requires"

    /**
     * Jar manifest attribute naming what the module makes use of where it is there and runs
     * without, in the form of [MANIFEST_REQUIRES]. Optional. Nothing is brought in for it. The
     * classes of an API it names are visible to it where the API is there in a version that
     * serves, so the module asks before it touches them.
     */
    const val MANIFEST_USES = "Nexira-Module-Uses"

    /**
     * Jar manifest attribute carrying the module's own version, as `major.minor`. Optional: with
     * it the module offers itself by its id, so another can require it without an API of its own.
     */
    const val MANIFEST_MODULE_VERSION = "Nexira-Module-Version"

    /**
     * Jar manifest attribute naming the APIs the jar publishes for other modules to compile
     * against, in the form of [MANIFEST_REQUIRES]. Optional. Each needs its packages in
     * [MANIFEST_API_PACKAGES].
     */
    const val MANIFEST_PROVIDES = "Nexira-Module-Provides"

    /**
     * Jar manifest attribute naming the packages each API in [MANIFEST_PROVIDES] lives in, as
     * `id=package` entries separated by commas, an id once for each of its packages. What of the
     * jar a module that names the API is shown.
     */
    const val MANIFEST_API_PACKAGES = "Nexira-Module-Api-Packages"
}
