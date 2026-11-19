package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.Translator
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookProvision
import hivens.look.manager.api.LookState
import hivens.widget.api.BootValues
import hivens.widget.api.WidgetScope
import hivens.widget.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.KoinApplication
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One value the shell draws its windows with, as it joins it in: the app source a module answers it
 * through, and what the shell draws with where no module brought in answers it.
 *
 * The shell does not know where the value comes from or how it is kept. A module brings it as input
 * data under its own API's key, and the shell reads it like any widget reads a key: from the app level
 * once the host is up ([read]), and from what the modules compiled in answered before that for the window
 * the launcher boots in ([atBoot]).
 */
class LookValue<T>(val key: SourceKey<T>, val fallback: T) {

    /** What the modules answered before the host was up, or [fallback] where none did. */
    // A value only ever goes into BootValues under its key's own type, see BootValues.Builder.put.
    @Suppress("UNCHECKED_CAST")
    fun atBoot(boot: BootValues): T = if (key in boot) boot[key] as T else fallback
}

/**
 * The values the shell joins in: the look and the words of the language module, each through a
 * [LookValue], the key its module's API names and the fallback the shell draws with where no module
 * answers it.
 *
 * The look is one value whatever axes it has, colour or motion or geometry or sound: the look manager
 * answers every axis under the one key, and the shell wraps what it draws in it without knowing any.
 */
object ShellLookValues {
    /** The answers of every axis of the look. None where no look manager is brought in, and every axis reads its base answers. */
    val Look: LookValue<LookProvision> = LookValue(LookKeys.Provide, LookProvision.NONE)

    /** The words every window is drawn in. Every key as itself where no language module is brought in. */
    val Words: LookValue<Translator> = LookValue(I18nKeys.Words, Translator.KEYS)

    /** Every value here. */
    val all: List<LookValue<*>> = listOf(Look, Words)
}

/**
 * Has this app level make what answers every value the shell draws with, and waits, at most [bound],
 * for the look manager to publish its first answers. A module makes its value on the first read, the
 * language module reading its tables cold, which takes longer than a frame, and the look manager answers
 * every axis with its base answers until its first pass has opened the providers the choice names. So a
 * host calls this off the event thread before the shell's first composition reads them there, and the
 * shell's first frame is drawn in the look rather than in the base answers.
 */
fun WidgetScope.prepareLook(bound: Duration = LOOK_BOUND) {
    ShellLookValues.all.forEach { find(it.key) }
    runBlocking { awaitLook(bound) }
}

/** [prepareLook] on the app level of the launcher's graph, [graph], for a host that holds only the graph. */
fun prepareLook(graph: KoinApplication) = graph.koin.get<WidgetScope>().prepareLook()

/**
 * Whether the look this app level answers has its first answers: true once the look manager published
 * its first pass, and where no manager is brought in, whose look is the base answers for good. For a
 * host whose first frame may come before [prepareLook] is done, and which holds that frame instead.
 */
fun WidgetScope.lookPublished(): Boolean = find(LookKeys.State)?.state?.value?.let(::published) ?: true

/** Returns once [lookPublished] is true, or after [bound] all the same. */
suspend fun WidgetScope.awaitLook(bound: Duration = LOOK_BOUND) {
    val state = find(LookKeys.State) ?: return
    withTimeoutOrNull(bound) { state.state.first(::published) }
}

/** Whether [state] is one a pass of the manager published, rather than the one it starts with. */
private fun published(state: LookState): Boolean = state !== LookState.EMPTY

/**
 * How long a host waits for the look's first answers before it draws in what it has. The manager's
 * first pass opens a provider that reads a file or two, so it is done well inside this, and a provider
 * that hangs costs the boot no more than this.
 */
val LOOK_BOUND: Duration = 2.seconds

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
