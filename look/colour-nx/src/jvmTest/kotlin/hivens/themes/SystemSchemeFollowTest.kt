package hivens.themes

import hivens.i18n.api.FixedTranslations
import hivens.i18n.api.Translator
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemeSettings
import hivens.widget.api.LevelServices
import hivens.widget.api.ServiceContext
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The module follows the system's colour scheme the way the shells did before it read the scheme itself:
 * one read as it starts says whether the scheme can be followed at all, the system mode follows every
 * change, every other mode listens to nothing, and entering the system mode reads the scheme again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemSchemeFollowTest {

    private val dir: Path = Files.createTempDirectory("system-scheme")
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** A system whose scheme is [os] now, counting who listens to it. */
    private class Desktop(initial: Boolean?, private val readable: Boolean = true) : SystemSchemeReader {
        val os = MutableStateFlow(initial)
        val listening = AtomicInteger()
        val listened = AtomicInteger()

        override suspend fun probe(): Boolean? = if (readable) os.value else null

        override fun observe(): Flow<Boolean?> = flow {
            listened.incrementAndGet()
            listening.incrementAndGet()
            try {
                emitAll(os)
            } finally {
                listening.decrementAndGet()
            }
        }
    }

    private fun following(settings: ThemeSettings, desktop: Desktop): ThemeManager =
        ThemeManager(MemoryStore(settings), ThemeLibraryFile(dir) { path, content -> Files.writeString(path, content) }, FixedTranslations(Translator.KEYS), scope)
            .also { followSystemScheme(it, desktop, scope) }

    @Test
    fun `the system mode follows the system's scheme from the start and as it changes`() {
        val desktop = Desktop(initial = false)
        val themes = following(ThemeSettings(mode = ThemeMode.System, dark = true), desktop)
        assertTrue(themes.systemSchemeAvailable.value)
        assertFalse(themes.dark.value, "the scheme read as the module starts decides at once")

        desktop.os.value = true
        assertTrue(themes.dark.value)
        desktop.os.value = false
        assertFalse(themes.dark.value)
        assertEquals(1, desktop.listening.get())
    }

    @Test
    fun `the other modes listen to nothing, and entering the system mode reads the scheme again`() {
        val desktop = Desktop(initial = false)
        val themes = following(ThemeSettings(mode = ThemeMode.Manual, dark = true), desktop)
        assertTrue(themes.systemSchemeAvailable.value, "readable, so following it is offered")
        assertTrue(themes.dark.value, "the person's own choice stands")
        assertEquals(0, desktop.listened.get())

        themes.setMode(ThemeMode.Wallpaper)
        assertEquals(0, desktop.listened.get(), "the wallpaper mode costs the system nothing")

        themes.setMode(ThemeMode.System)
        assertFalse(themes.dark.value, "the scheme is read again as the mode is entered")
        assertEquals(1, desktop.listening.get())

        themes.setDark(true)
        assertEquals(ThemeMode.Manual, themes.mode.value)
        assertEquals(0, desktop.listening.get(), "a choice by hand stops the listening")
        desktop.os.value = true
        desktop.os.value = false
        assertTrue(themes.dark.value, "and what the system says then changes nothing")

        themes.setMode(ThemeMode.System)
        assertFalse(themes.dark.value)
        assertEquals(2, desktop.listened.get())
    }

    @Test
    fun `a moment the scheme cannot tell says nothing`() {
        val desktop = Desktop(initial = true)
        val themes = following(ThemeSettings(mode = ThemeMode.System, dark = false), desktop)
        assertTrue(themes.dark.value)
        desktop.os.value = null
        assertTrue(themes.dark.value)
        assertTrue(themes.systemSchemeAvailable.value)
    }

    @Test
    fun `a scheme that cannot be read is said once and never listened to`() {
        val desktop = Desktop(initial = false, readable = false)
        val themes = following(ThemeSettings(mode = ThemeMode.System, dark = true), desktop)
        assertFalse(themes.systemSchemeAvailable.value)
        assertTrue(themes.dark.value, "the last scheme seen stays")
        themes.setMode(ThemeMode.Manual)
        themes.setMode(ThemeMode.System)
        assertEquals(0, desktop.listened.get())
        assertTrue(themes.dark.value)
    }

    @Test
    fun `the switch tells the module the scheme is not readable, and the desktop is asked otherwise`() {
        val before = System.getProperty(SYSTEM_SCHEME_SWITCH)
        val context = object : ServiceContext {
            override val level = ScopeLevel.App
            override val coroutineScope = scope
            override val services = object : LevelServices {
                override fun <T : Any> find(type: KClass<T>): T? = null
                override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException()
            }
        }
        try {
            System.setProperty(SYSTEM_SCHEME_SWITCH, "off")
            assertSame(UnreadableScheme, systemSchemeReader(context))
            System.clearProperty(SYSTEM_SCHEME_SWITCH)
            assertSame(SystemTheme, systemSchemeReader(context))
        } finally {
            if (before == null) System.clearProperty(SYSTEM_SCHEME_SWITCH) else System.setProperty(SYSTEM_SCHEME_SWITCH, before)
        }
    }
}
