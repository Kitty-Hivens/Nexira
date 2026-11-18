package hivens.themes

import hivens.themes.api.ThemeMode
import hivens.widget.api.ServiceContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * What the system's colour scheme says. [readable] says whether it can be read at all, which decides
 * whether following the system is offered. [dark] is what the scheme says now, null where it cannot
 * tell at the moment.
 */
data class SystemScheme(val readable: Boolean, val dark: Boolean? = null)

/** Where this module reads the system's colour scheme, one per platform it runs on. */
internal interface SystemSchemeReader {
    /** What the scheme says now: true for dark, false for light, null where it cannot tell. */
    suspend fun probe(): Boolean?

    /**
     * The scheme as it changes, starting with what it says now. Cold: it reads the system only while
     * collected, so a mode that does not follow the system costs nothing.
     */
    fun observe(): Flow<Boolean?>
}

/** The reader of the platform this module runs on, which reaches what it needs through [context]. */
internal expect fun platformSchemeReader(context: ServiceContext): SystemSchemeReader

/**
 * The system property that tells this module the system's colour scheme is not readable, when it is
 * `off`: the module then reads nothing of the system and offers no following of it, as on a desktop
 * with no portal to ask. For a run that must not ask the machine it runs on, every test run among them.
 * The launcher itself never sets it.
 */
internal const val SYSTEM_SCHEME_SWITCH: String = "nexira.systemScheme"

/** The reader this module reads the system's scheme with: none where [SYSTEM_SCHEME_SWITCH] is `off`, the platform's otherwise. */
internal fun systemSchemeReader(context: ServiceContext): SystemSchemeReader =
    if (System.getProperty(SYSTEM_SCHEME_SWITCH) == "off") UnreadableScheme else platformSchemeReader(context)

/** A system whose scheme cannot be read. */
internal object UnreadableScheme : SystemSchemeReader {
    override suspend fun probe(): Boolean? = null

    override fun observe(): Flow<Boolean?> = emptyFlow()
}

/**
 * Tells [manager] what the system's scheme says, read through [reader], on [scope]: once as the module
 * starts, which says whether it can be read at all, and then, while it can and the mode follows the
 * system, every change. Entering the system mode reads the scheme again, since [SystemSchemeReader.observe]
 * starts with what it says now.
 */
internal fun followSystemScheme(manager: ThemeManager, reader: SystemSchemeReader, scope: CoroutineScope) {
    scope.launch {
        val probed = reader.probe()
        val readable = probed != null
        manager.systemScheme(SystemScheme(readable = readable, dark = probed))
        if (!readable) return@launch
        manager.mode.collectLatest { mode ->
            if (mode == ThemeMode.System) reader.observe().collect { dark -> manager.systemScheme(SystemScheme(readable = true, dark = dark)) }
        }
    }
}
