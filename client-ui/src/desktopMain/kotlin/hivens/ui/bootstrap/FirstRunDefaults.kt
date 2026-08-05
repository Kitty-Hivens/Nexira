package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.core.data.SettingsData
import hivens.core.data.ThemeMode
import hivens.core.io.AtomicFiles
import hivens.ui.i18n.AppLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val log = LoggerFactory.getLogger("FirstRunDefaults")

/**
 * The settings a launcher starts with when it has never been started before.
 *
 * [SettingsData]'s own defaults answer "what does this field mean with nothing
 * to go on", which is the right question for a key an older settings file omits
 * and the wrong one for a first launch: the machine knows things. It has a
 * language, and it is a games machine opened at whatever hour its owner plays.
 * Left to the field defaults a first launch comes up in English and, in system
 * theme mode, immediately follows a desktop that has never been moved off its
 * light default -- so the first thing a non-English player sees at night is a
 * white window in a language they may not read.
 *
 * Written only when no settings file exists, and only the keys whose first-run
 * answer differs from the shipped default ([firstRunOverrides]) -- an install
 * that never opens the settings screen keeps tracking every other default as it
 * changes. Nothing here alters what an existing file decodes to: a key a save
 * predates still takes that key's own default, which is what keeps an upgrade
 * from silently re-deciding something its owner already chose.
 */
object FirstRunDefaults {

    /**
     * Seeds the settings file for a first launch. No-op when one is already
     * there, and best-effort: a launcher that cannot write its settings still
     * runs, on exactly the values it would have had anyway.
     */
    fun seed(dataDir: Path, systemLocale: Locale = Locale.getDefault()) {
        val file = dataDir.resolve(Storage.SETTINGS_FILE)
        if (Files.exists(file)) return
        val seeded = firstRunOverrides(systemLocale)
        runCatching {
            Files.createDirectories(dataDir)
            AtomicFiles.writeString(file, json.encodeToString(JsonObject.serializer(), seeded))
        }.onFailure {
            log.warn("Could not seed first-run settings at {} -- starting on the shipped defaults", file, it)
        }.onSuccess {
            log.info("First launch: seeded settings {}", seeded.keys)
        }
    }

    /**
     * The first-run values as the settings keys that carry them, for the two
     * writers that edit settings.json as JSON rather than through the settings
     * service ([seed] and the recovery surface's reset).
     *
     * Only the keys that actually differ from a shipped [SettingsData]: derived
     * by comparison rather than listed, so a value that later becomes the
     * shipped default stops being written here on its own.
     */
    internal fun firstRunOverrides(systemLocale: Locale = Locale.getDefault()): JsonObject {
        val seeded = json.encodeToJsonElement(firstRunSettings(systemLocale)).jsonObject
        val shipped = json.encodeToJsonElement(SettingsData()).jsonObject
        return JsonObject(seeded.filter { (key, value) -> shipped[key] != value })
    }

    /**
     * What a first launch gets: the machine's own language where the launcher
     * speaks it, and dark.
     *
     * Dark as a mode of its own rather than [ThemeMode.System], which would hand
     * the decision to the desktop. A launcher is opened to start a game, most
     * often in the evening, and the light scheme a stock Windows still ships
     * with would otherwise put a white screen in front of that. Following the OS
     * stays one click away in the appearance settings and is remembered once
     * chosen -- this only decides which way the very first window opens.
     */
    internal fun firstRunSettings(systemLocale: Locale): SettingsData = SettingsData(
        locale = supportedLocaleTag(systemLocale),
        isDarkTheme = true,
        themeMode = ThemeMode.Manual,
    )

    /**
     * The shipped locale closest to [systemLocale]: its language when the
     * launcher is translated into it, English otherwise.
     *
     * Matched on language alone, so ru_UA, ru_KZ and ru_RU all land on Russian
     * -- the region decides date formats, not which words a person reads. An
     * unsupported language falls back to English rather than to a neighbouring
     * one: German serves a German-speaking Swiss user, nothing here serves a
     * Polish one, and guessing at that is worse than the language the launcher
     * is documented and screenshotted in.
     */
    internal fun supportedLocaleTag(
        systemLocale: Locale,
        supported: Set<String> = AppLocale.entries.map { it.tag }.toSet(),
    ): String {
        val language = systemLocale.language.lowercase(Locale.ROOT)
        return if (language in supported) language else FALLBACK
    }

    // encodeDefaults so the comparison above sees every key on both sides.
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private const val FALLBACK = "en"
}
