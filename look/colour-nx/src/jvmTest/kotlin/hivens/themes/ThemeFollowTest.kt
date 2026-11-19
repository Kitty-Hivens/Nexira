package hivens.themes

import androidx.compose.ui.graphics.Color
import hivens.i18n.api.FixedTranslations
import hivens.i18n.api.Translator
import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemeScheme
import hivens.themes.api.ThemeSettings
import hivens.widget.api.ModuleContribution
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsStore
import hivens.widget.api.TakenContribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The themes the modules brought in reach the manager as [ThemeManager.follow] promises: what is there now, before it returns. */
class ThemeFollowTest {

    private val dir: Path = Files.createTempDirectory("theme-follow")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private class Store : SettingsStore<ThemeSettings> {
        override val value = MutableStateFlow(ThemeSettings())
        override val readOnly = false
        override fun update(change: SettingsChange<ThemeSettings>) {
            value.value = change.apply(value.value)
        }
    }

    private class Night : ThemeContribution {
        override val id = "night"
        override val label = "night"
        override val dark = ThemeScheme(listOf(Color(0xFF101010), Color(0xFF202020)), listOf(Color.Red))
    }

    @Test
    fun `the themes brought in already are on offer the moment follow returns, with nothing left to run`() {
        // A scope that runs nothing it is handed, so only what follow does before it returns counts.
        val idle = CoroutineScope(Job().apply { cancel() })
        val manager = ThemeManager(Store(), ThemeLibraryFile(dir) { _, _ -> }, FixedTranslations(Translator.KEYS), idle)
        val taken: StateFlow<List<TakenContribution<ModuleContribution>>> = MutableStateFlow(listOf(TakenContribution("looks", Night())))

        manager.follow(taken, idle)

        assertTrue(manager.offered.value.any { it.id == "looks/night" })
    }
}
