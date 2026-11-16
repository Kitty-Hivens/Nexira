package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.Translator
import hivens.themes.api.ThemesKeys
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.Theme
import hivens.widget.api.BootValues
import hivens.widget.api.WidgetScope
import hivens.widget.model.SourceKey
import org.koin.core.KoinApplication

/**
 * One value the shell draws its windows with, as it joins it in: the app source a module answers it
 * through, and what the shell draws with where no module brought in answers it.
 *
 * The shell does not know where the value comes from or how it is kept. A module brings it as input
 * data under its own API's key, every widget decides for itself what to make of it, and the shell
 * reads it like any widget reads a key: from the app level once the host is up ([from]), and from what
 * the modules compiled in answered before that for the window the launcher boots in ([atBoot]).
 */
class LookValue<T>(val key: SourceKey<T>, val fallback: T) {

    /** What the modules answered before the host was up, or [fallback] where none did. */
    // A value only ever goes into BootValues under its key's own type, see BootValues.Builder.put.
    @Suppress("UNCHECKED_CAST")
    fun atBoot(boot: BootValues): T = if (key in boot) boot[key] as T else fallback
}

/**
 * The values the shell joins in: the colours a theme module brings and the words of the language
 * module. A later axis of the look, motion or geometry or sound, joins the same way, as one more
 * value here under the key its module's API names, with a fallback of its own.
 */
object ShellLookValues {
    /** The theme every window is drawn in. Neutral where no themes module is brought in. */
    val Theme: LookValue<Theme> = LookValue(ThemesKeys.Active, NeutralTheme)

    /** Whether it is drawn in its dark scheme. Dark where nothing says otherwise, as a first start opens. */
    val Dark: LookValue<Boolean> = LookValue(ThemesKeys.Dark, true)

    /** The words every window is drawn in. Every key as itself where no language module is brought in. */
    val Words: LookValue<Translator> = LookValue(I18nKeys.Words, Translator.KEYS)

    /** Every value here. */
    val all: List<LookValue<*>> = listOf(Theme, Dark, Words)
}

/**
 * Has this app level make what answers every value the shell draws with. A module makes it on the
 * first read, the language module reading its tables cold, which takes longer than a frame, so a host
 * calls this off the event thread before the shell's first composition reads them there.
 */
fun WidgetScope.prepareLook() = ShellLookValues.all.forEach { find(it.key) }

/** [prepareLook] on the app level of the launcher's graph, [graph], for a host that holds only the graph. */
fun prepareLook(graph: KoinApplication) = graph.koin.get<WidgetScope>().prepareLook()

/** [value] as [app] answers it now and every time it changes, its fallback where nothing does. */
@Composable
fun <T> WidgetScope.read(value: LookValue<T>): T = readOrNull(value.key) ?: value.fallback

/**
 * The value of the app source [key] as this level answers it now and every time it changes, or null
 * where nothing answers it. Read off the level on every composition, so a module loaded again, whose
 * key the level made afresh, is read anew.
 */
@Composable
fun <T> WidgetScope.readOrNull(key: SourceKey<T>): T? {
    val source = find(key) ?: return null
    return source.state.collectAsState().value
}
