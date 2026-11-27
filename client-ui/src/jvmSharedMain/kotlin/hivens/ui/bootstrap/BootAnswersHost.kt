package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.ui.layout.DocumentStore
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.widgets.modules.CompiledModules
import hivens.ui.widgets.modules.bootTakenContributions
import hivens.ui.widgets.modules.broughtIn
import hivens.ui.widgets.modules.fixedTakenWords
import hivens.ui.widgets.modules.launcherWords
import hivens.widget.api.BootContext
import hivens.widget.api.BootValues
import hivens.widget.api.CommandFactory
import hivens.widget.api.CompiledModule
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ServiceContext
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsDomain
import hivens.widget.api.SourceFactory
import hivens.widget.api.TakenContributions
import hivens.widget.api.TakenWords
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.StandardDocument
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass
import hivens.ui.widgets.modules.BuiltInWidgetsRequire

private val log = LoggerFactory.getLogger("BootAnswers")

/**
 * What the modules compiled into the build answer of their app sources before the host is up, from
 * what [dataDir] holds: the [CompiledModule.boot] of every module the document names ([named]) and of what
 * those require, in the build's order, the first answer of a key kept. The window the launcher boots in is drawn and worded
 * with these, and a module the document leaves out answers nothing, as its keys answer nothing once
 * the shell is up.
 *
 * A module answers only the app sources it declares, and reads only the settings domains it declares,
 * the rules its keys keep at runtime: an answer that names another key, or reads another module's
 * domain, is refused whole. A module whose words, declaration or answer fail answers nothing and is
 * logged, so it costs only its own values.
 */
fun bootValues(
    dataDir: Path,
    compiled: List<CompiledModule> = CompiledModules,
    named: Set<String> = documentModules(dataDir),
): BootValues {
    val words = fixedTakenWords(launcherWords(compiled))
    val brought = broughtIn(named, compiled, builtInRequire = BuiltInWidgetsRequire)
    val taken = bootTakenContributions(brought, compiled)
    return compiled.fold(BootValues.NONE) { gathered, module ->
        val answered = try {
            answerOf(module, brought, dataDir, words, taken, compiled)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            log.warn("The module '{}' could not answer before the boot, and its values are drawn with the fallbacks", runCatching { module.id }.getOrNull(), e)
            BootValues.NONE
        }
        gathered over answered
    }
}

/**
 * [answer], worked out on a thread of its own from now on, for a window that waits for it later, and
 * nothing where it fails all the same, so that window opens on its fallbacks rather than not at all.
 */
fun answeredLater(answer: () -> BootValues): CompletableFuture<BootValues> =
    CompletableFuture.supplyAsync(answer).exceptionally { e ->
        log.error("Nothing was answered before the boot, and the boot window is drawn with the fallbacks", e)
        BootValues.NONE
    }

/**
 * The modules the document names, decided the way the shell decides it once it is up: the person's own
 * document where it is one this build reads, the standard document otherwise. Nothing is written.
 */
internal fun documentModules(dataDir: Path): Set<String> =
    DocumentStore.peek(dataDir.resolve(Storage.INTERFACE_FILE)) { StandardDocument.load() }.modules.toSet()

/**
 * What [module] answers, held to what it declares, or nothing where it is not in [brought] or it answers
 * nothing. [compiled] are the modules whose context it may ask for, see [BootContext.ofModule].
 */
private fun answerOf(
    module: CompiledModule,
    brought: Set<String>,
    dataDir: Path,
    words: TakenWords,
    taken: TakenContributions,
    compiled: List<CompiledModule>,
): BootValues {
    val id = module.id
    if (id !in brought) return BootValues.NONE
    val answers = module.boot ?: return BootValues.NONE
    val owned = Owned.of(module.contributions)
    val answered = answers.answer(HostBootContext(id, dataDir, owned.domains, words, taken, compiled))
    val foreign = answered.ids - owned.sources
    check(foreign.isEmpty()) { "the module '$id' answered ${foreign.joinToString()}, which it does not declare" }
    return answered
}

/** The app sources and the settings domains a module declares: the sources by id, the domains as declared. */
internal class Owned(val sources: Set<String>, val declared: List<SettingsDomain<*>>) {
    /** The ids of [declared]. */
    val domains: Set<String> get() = declared.mapTo(HashSet()) { it.id }

    companion object {
        /** What [contributions] declare, as their key contributions declare it to the host. */
        fun of(contributions: List<ModuleContribution>): Owned {
            val recorder = Recorder()
            contributions.filterIsInstance<KeyContribution>().forEach { it.declare(recorder) }
            return Owned(recorder.sources, recorder.domains)
        }
    }
}

/** Keeps the app sources declared into it by id and the settings domains as declared, and nothing else. */
private class Recorder : ModuleKeys {
    val sources = HashSet<String>()
    val domains = ArrayList<SettingsDomain<*>>()

    override fun <T> source(key: SourceKey<T>, sample: (() -> T)?, factory: SourceFactory<T>) {
        if (key.level == ScopeLevel.App) sources += key.id
    }

    override fun <P> command(key: CommandKey<P>, factory: CommandFactory<P>) = Unit

    override fun <T> owned(key: SourceKey<T>, sample: (() -> T)?) = Unit

    override fun owned(key: CommandKey<*>) = Unit

    override fun <T : Any> settings(domain: SettingsDomain<T>, source: SourceKey<T>, update: CommandKey<SettingsChange<T>>) {
        domains += domain
        if (source.level == ScopeLevel.App) sources += source.id
    }

    override fun <T : Any> service(level: ScopeLevel, type: KClass<T>, make: (ServiceContext) -> T) = Unit

    override fun <T : Any> hostService(level: ScopeLevel, type: KClass<T>, make: (ServiceContext) -> T) = Unit
}

/**
 * What the host hands the module [module] before it is up: the data directory, the settings domains it
 * declared, [domains], as the settings host opens them, read and not kept, the words the launcher and
 * every module compiled in ship, and what the modules compiled in carry of the kinds they take. Every
 * module asked is compiled in and trusted, so each may have the context of any module in [compiled].
 */
private class HostBootContext(
    private val module: String,
    override val dataDir: Path,
    private val domains: Set<String>,
    private val words: TakenWords,
    private val taken: TakenContributions,
    private val compiled: List<CompiledModule>,
) : BootContext {
    private val files = SettingsDomainFiles(
        dir        = dataDir.resolve(Storage.SETTINGS_DIR),
        legacyFile = dataDir.resolve(Storage.SETTINGS_FILE),
    )

    override fun <T : Any> settings(domain: SettingsDomain<T>): T {
        check(domain.id in domains) { "module '$module' did not declare the settings domain '${domain.id}'" }
        return files.peek(domain)
    }

    override val services: LevelServices = FixedServices(mapOf(TakenWords::class to words, TakenContributions::class to taken))

    // The other module's domains are read off its declaration, as the host reads every module's.
    override fun ofModule(moduleId: String): BootContext? = compiled.firstOrNull { it.id == moduleId }?.let { other ->
        HostBootContext(moduleId, dataDir, Owned.of(other.contributions).domains, words, taken, compiled)
    }
}

/** Services that are what [byType] holds and nothing else. */
private class FixedServices(private val byType: Map<KClass<*>, Any>) : LevelServices {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> find(type: KClass<T>): T? = byType[type] as T?

    override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException("no ${type.qualifiedName} before the host is up")
}
