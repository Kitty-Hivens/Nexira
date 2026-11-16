package hivens.ui.host

import hivens.i18n.api.Strings
import hivens.i18n.api.Translations
import hivens.i18n.api.Translator
import hivens.widget.api.WidgetScope
import hivens.widget.api.find
import org.slf4j.LoggerFactory

/**
 * The words the host says outside a composition, a notice or the name of a module, and the languages
 * it fetches in: the [Translations] the language module keeps at [app]'s level, looked up each time,
 * since the module makes them there and the level is up only once the host is. Where the document
 * brings no language module, or it could not make its words, every key shows as itself, as a
 * composition without one reads it, and nothing said through here is lost to it.
 */
internal class HostWords(private val app: () -> WidgetScope) {

    private fun translations(): Translations? = try {
        app().launcherServices.find<Translations>()
    } catch (e: Exception) {
        log.warn("The language module's words could not be had, so every key shows as itself", e)
        null
    }

    /** The words of [namespace] in the language they are read in now. */
    fun strings(namespace: String): Strings = (translations()?.current?.value ?: Translator.KEYS).strings(namespace)

    /** The languages the words are read in now, best first, or null where no language module answers. */
    val readingOrder: List<String>? get() = translations()?.readingOrder

    private companion object {
        val log = LoggerFactory.getLogger(HostWords::class.java)
    }
}
