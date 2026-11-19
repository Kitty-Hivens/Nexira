package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.widgets.modules.CompiledModules
import hivens.widget.api.CompiledModule
import hivens.widget.api.SettingsDomain
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val log = LoggerFactory.getLogger("FirstRunDefaults")

/**
 * The settings a launcher starts with when it has never been started before.
 *
 * A settings domain's defaults answer "what does this mean with nothing to go on", which is the right
 * question for a value an older file omits and the wrong one for a first launch: the machine knows
 * things. Each domain that knows better says so ([SettingsDomain.firstRun]), the language module the
 * machine's language and the themes module dark, and that is written into the domain's file once,
 * before the window the launcher boots in asks the modules what to draw.
 *
 * The host names none of those values: it asks the domains every module compiled in declares, the way
 * the boot asks them what they declare. Only a value other than the domain's defaults is written, so a
 * domain whose first start is its defaults keeps following them as they change. Nothing here alters
 * what an existing file decodes to.
 */
object FirstRunDefaults {

    /**
     * Seeds a first launch: no-op where a settings file is already there. Best-effort: a launcher that
     * cannot write its settings still runs, on the defaults it would have had anyway.
     */
    fun seed(dataDir: Path, compiled: List<CompiledModule> = CompiledModules) {
        if (Files.exists(dataDir.resolve(Storage.SETTINGS_FILE))) return
        val seeded = seedDomains(dataDir, compiled)
        log.info("First launch: seeded the settings of {}", seeded)
    }

    /**
     * Writes the first start's value of every settings domain [compiled] declare that has one and no file
     * of its own yet, each through the domain's own store, so the fields it mirrors into the former
     * settings file are written too. For a first launch, and for a reset of the settings, which starts
     * over the same way. Answers the ids of the domains written.
     */
    fun seedDomains(dataDir: Path, compiled: List<CompiledModule> = CompiledModules): Set<String> {
        val files = SettingsDomainFiles(dir = dataDir.resolve(Storage.SETTINGS_DIR), legacyFile = dataDir.resolve(Storage.SETTINGS_FILE))
        val seeded = LinkedHashSet<String>()
        compiled.forEach { module ->
            val declared = runCatching { Owned.of(module.contributions).declared }
                .onFailure { log.warn("The settings of the module '{}' could not be asked for their first start", runCatching { module.id }.getOrNull(), it) }
                .getOrDefault(emptyList())
            declared.forEach { domain ->
                runCatching { if (seedOne(dataDir, files, domain)) seeded += domain.id }
                    .onFailure { log.warn("Could not seed the first start's settings of '{}', which start on their defaults", domain.id, it) }
            }
        }
        return seeded
    }

    private fun <T : Any> seedOne(dataDir: Path, files: SettingsDomainFiles, domain: SettingsDomain<T>): Boolean {
        if (Files.exists(files.fileOf(domain))) return false
        val first = domain.firstRun() ?: return false
        if (first == domain.defaults()) return false
        Files.createDirectories(dataDir.resolve(Storage.SETTINGS_DIR))
        files.of(domain).update { first }
        return Files.exists(files.fileOf(domain))
    }
}
