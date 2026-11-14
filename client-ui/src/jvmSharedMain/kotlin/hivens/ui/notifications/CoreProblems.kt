package hivens.ui.notifications

import hivens.config.Branding
import hivens.ui.i18n.AppStrings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * The parts of the launcher's core, by what the person loses when one does not
 * start. A key that cannot be made is told under its part, so one broken service
 * is one notice however many keys it backs.
 */
enum class CorePart {
    /** Signing in and out. */
    Session,

    /** Themes, the dark scheme and the language. */
    Appearance,

    /** The packs and what is done to them. */
    Packs,

    /** The console. */
    Console,

    /** Anything else a key stands for. */
    Other,
}

/** A failure in the launcher's core the person has to be told about, because nothing on screen would say it. */
sealed interface CoreProblem {
    /** What the problem is told under. One notice per key, see [perAttempt]. */
    val key: String

    /**
     * Whether the problem answers something the person asked for and may ask for
     * again: a sign-out, a launch. Each attempt that fails is told, since the last
     * notice may be dismissed by then and the new attempt otherwise says nothing.
     * A problem that is not is told once for the life of the process, however many
     * windows and reloads run into it.
     */
    val perAttempt: Boolean get() = false

    /** The launcher's own sign-in threw, so the launcher was left signed out. */
    data class SignInFailed(val cause: Throwable) : CoreProblem {
        override val key: String get() = "core:sign-in"
    }

    /** Signing out threw before the accounts were cleared, so they are still on this device. */
    data class SignOutFailed(val cause: Throwable) : CoreProblem {
        override val key: String get() = "core:sign-out"
        override val perAttempt: Boolean get() = true
    }

    /** Signed out, but the offline name could not be removed, so the next start may sign in under it. */
    data class SignedOutNameKept(val cause: Throwable) : CoreProblem {
        override val key: String get() = "core:sign-out:name"
        override val perAttempt: Boolean get() = true
    }

    /** A part of the core could not be made, so whatever reads its keys has nothing. */
    data class PartMissing(val part: CorePart, val cause: Throwable) : CoreProblem {
        override val key: String get() = "core:part:${part.name}"
    }

    /**
     * A launch was asked for the pack [packId], which is not installed. Under a key
     * of its own rather than the pack's launch group, whose notices are the launch
     * driver's, so taking this one back cannot take the driver's with it.
     */
    data class PackNotInstalled(val packId: String) : CoreProblem {
        override val key: String get() = "core:pack:$packId:not-installed"
        override val perAttempt: Boolean get() = true
    }
}

/** Where a [CoreProblem] is reported. The launcher's says it in the notification centre. */
interface CoreProblems {
    /** Says [problem]. Returns at once, whatever thread calls it. */
    fun report(problem: CoreProblem)

    /** Takes back every report of [kind] still standing, once what it said no longer holds. */
    fun withdraw(kind: KClass<out CoreProblem>)

    /** Takes back the report standing under [problem]'s key, once what it said no longer holds. */
    fun withdraw(problem: CoreProblem)
}

/**
 * [CoreProblems] as sticky notifications in the person's language. Sticky, because
 * each says that something the person relies on did not happen, and a toast that
 * ages out before it is read says nothing.
 *
 * Each problem is told once for the life of the process under its key, however many
 * times and from however many threads it is reported, so a part missing in every
 * window and in every reload is still one notice and one record in the history. A
 * report that was withdrawn can be told again, and so can one that answers an
 * attempt ([CoreProblem.perAttempt]), each time the attempt fails.
 *
 * The notice is put together and pushed on [dispatcher] under [scope], never on the
 * caller's thread: a report can come from inside a frame, and reading the language
 * waits on the settings lock, which a save holds across a file write. Logging is the
 * caller's, which knows what it was doing.
 */
class NotifiedCoreProblems(
    private val notifications: NotificationCenter,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val strings: () -> AppStrings,
) : CoreProblems {

    // The keys told and not withdrawn, with the kind each was told for.
    private val told = ConcurrentHashMap<String, KClass<out CoreProblem>>()

    override fun report(problem: CoreProblem) {
        if (problem.perAttempt) {
            told[problem.key] = problem::class
        } else if (told.putIfAbsent(problem.key, problem::class) != null) {
            return
        }
        scope.launch(dispatcher) {
            // Withdrawn before it got here: there is nothing left to say.
            if (told[problem.key] == null) return@launch
            val s = strings()
            val _ = notifications.push(
                sourceKey = problem.key,
                sender    = Branding.TITLE,
                iconUrl   = null,
                severity  = Severity.Critical,
                kind      = Kind.Sticky,
                title     = title(problem, s),
                body      = body(problem, s),
            )
            // Withdrawn while it was being pushed: the withdraw found nothing to take down.
            if (told[problem.key] == null) notifications.dismiss(problem.key)
        }
    }

    override fun withdraw(kind: KClass<out CoreProblem>) {
        told.entries.filter { it.value == kind }.forEach { (key, _) ->
            if (told.remove(key) != null) notifications.dismiss(key)
        }
    }

    override fun withdraw(problem: CoreProblem) {
        if (told.remove(problem.key) != null) notifications.dismiss(problem.key)
    }

    private fun title(problem: CoreProblem, s: AppStrings): String = when (problem) {
        is CoreProblem.SignInFailed -> s.notifSignInFailedTitle
        is CoreProblem.SignOutFailed -> s.notifSignOutFailedTitle
        is CoreProblem.SignedOutNameKept -> s.notifSignOutNameKeptTitle
        is CoreProblem.PackNotInstalled -> s.notifLaunchNotInstalledTitle
        is CoreProblem.PartMissing -> when (problem.part) {
            CorePart.Session -> s.notifCoreSessionMissingTitle
            CorePart.Appearance -> s.notifCoreAppearanceMissingTitle
            CorePart.Packs -> s.notifCorePacksMissingTitle
            CorePart.Console -> s.notifCoreConsoleMissingTitle
            CorePart.Other -> s.notifCorePartMissingTitle
        }
    }

    private fun body(problem: CoreProblem, s: AppStrings): String = when (problem) {
        is CoreProblem.SignInFailed -> detail(problem.cause, s)
        is CoreProblem.SignOutFailed -> detail(problem.cause, s)
        is CoreProblem.SignedOutNameKept -> s.notifSignOutNameKeptBody
        is CoreProblem.PackNotInstalled -> s.notifReasonNotInstalled
        is CoreProblem.PartMissing -> s.notifCorePartMissingBody
    }

    private fun detail(cause: Throwable, s: AppStrings): String =
        cause.message?.takeIf { it.isNotBlank() }?.let(s::notifReasonInternalDetail) ?: s.notifReasonInternal
}
