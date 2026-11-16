package hivens.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import hivens.i18n.I18nEngine
import hivens.i18n.PropertiesFiles
import hivens.i18n.ShippedLanguages
import hivens.i18n.api.LocalTranslator
import hivens.i18n.api.Strings
import hivens.i18n.api.Translator
import hivens.i18n.api.strings
import hivens.widget.api.ModuleStrings
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The launcher's own words: the namespace they live under in the language module, and the tables
 * it ships for them, one `i18n/nexira/<tag>.properties` per shipped language.
 *
 * Keys are the names the words had as properties of the interface they replaced, so a call site
 * reads `s.text("loginButton")` where it read `s.loginButton`. The widget names and property labels
 * keep their dotted keys.
 */
object NexiraStrings {
    const val NAMESPACE: String = "nexira"

    private const val ROOT = "i18n/$NAMESPACE"

    /** The tables shipped, by language tag. A language with no file is left out and reads in English. */
    fun tables(): Map<String, Map<String, String>> = ShippedLanguages.tags.mapNotNull { tag ->
        NexiraStrings::class.java.getResourceAsStream("/$ROOT/$tag.properties")?.use { tag to PropertiesFiles.read(it) }
    }.toMap()

    /** [tables] as what the language module takes from an owner: its namespace to its tables. */
    fun contribution(): Map<String, Map<String, Map<String, String>>> = mapOf(NAMESPACE to tables())

    /** [tables] as the words the launcher ships under [NAMESPACE], the way a module ships its own. */
    fun words(): ModuleStrings = ModuleStrings(tables = tables())

    /** The language tags the launcher's own words ship a table in, without reading any of them. */
    val languages: Set<String>
        get() = ShippedLanguages.tags.filterTo(LinkedHashSet()) { NexiraStrings::class.java.getResource("/$ROOT/$it.properties") != null }

    /** The keys the binary byte units are under, smallest first: bytes, then kilo-, mega-, giga-, tera-. */
    val BYTE_UNIT_KEYS: List<String> = listOf("byteUnitBytes", "byteUnitKilo", "byteUnitMega", "byteUnitGiga", "byteUnitTera")

    /** An engine with the launcher's own words in it and a person's files from [userDir], speaking [language]. */
    fun engine(userDir: Path?, language: String): I18nEngine =
        I18nEngine(userDir, language).apply { contribute(NAMESPACE, contribution()) }

    private val shippedTranslators = ConcurrentHashMap<String, Translator>()

    /** The shipped words alone, in the language [tag]: for a probe, a test or a preview that has no engine. */
    fun translator(tag: String): Translator = shippedTranslators.computeIfAbsent(tag) { engine(null, it).current.value }

    /** The launcher's own shipped words in the language [tag]. See [translator]. */
    fun shipped(tag: String): Strings = translator(tag).strings(NAMESPACE)
}

/**
 * Draws [content] in the shipped words of the language [tag], whatever is around it: for a probe,
 * a test or a preview that has no engine. The launcher itself provides its engine's words instead.
 */
@Composable
fun ShippedLanguageProvider(tag: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTranslator provides NexiraStrings.translator(tag), content = content)
}

/** The launcher's own words in the language this part of the composition is drawn in. */
@Composable
@ReadOnlyComposable
fun nexiraStrings(): Strings = strings(NexiraStrings.NAMESPACE)

/** The binary byte units as this language abbreviates them, smallest first. */
val Strings.byteUnits: List<String> get() = NexiraStrings.BYTE_UNIT_KEYS.map { text(it) }

/**
 * The label of a Modrinth category id: the language's word for one of the modpack categories,
 * else a casing of the id itself. Loader ids keep their brand casing, everything else is split on
 * `-` and `_` and title-cased ("kitchen-sink" -> "Kitchen Sink").
 */
fun Strings.modrinthCategory(id: String): String =
    "modrinthCategory.$id".let { key -> if (has(key)) text(key) else humanizedCategory(id) }

private fun humanizedCategory(raw: String): String = when (raw) {
    "fabric"   -> "Fabric"
    "forge"    -> "Forge"
    "quilt"    -> "Quilt"
    "neoforge" -> "NeoForge"
    else -> raw.split('-', '_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
}

private val absoluteTimeFormats = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

/**
 * [instant] as the notification tooltip writes it, in this language's pattern and the system's zone.
 * The pattern is a word like any other, so one missing or mistyped in a person's own table falls back
 * to the English one rather than taking the tooltip down.
 */
fun Strings.notificationAbsoluteTime(instant: Instant): String {
    val pattern = if (has(TIME_PATTERN)) text(TIME_PATTERN) else FALLBACK_TIME_PATTERN
    val format = absoluteTimeFormats.computeIfAbsent(pattern to locale) { (p, l) ->
        runCatching { DateTimeFormatter.ofPattern(p, l) }.getOrElse { DateTimeFormatter.ofPattern(FALLBACK_TIME_PATTERN, l) }
            .withZone(ZoneId.systemDefault())
    }
    return format.format(instant)
}

private const val TIME_PATTERN = "notificationAbsoluteTimePattern"
private const val FALLBACK_TIME_PATTERN = "d MMM yyyy, HH:mm:ss"
