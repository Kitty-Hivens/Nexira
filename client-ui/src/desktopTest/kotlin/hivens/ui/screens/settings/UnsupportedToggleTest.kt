package hivens.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import hivens.core.data.SettingsData
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.puppet.PuppetRegistry
import hivens.ui.theme.NxTheme
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A setting this host cannot act on shows its switch disabled and off, whatever is
 * stored. "Custom title bar" on a phone showed a switch turned on above the line
 * saying it is not supported here.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UnsupportedToggleTest {

    @AfterTest fun tearDown() = stopKoin()

    @Test
    fun `an unsupported setting stored on shows a switch that is disabled and off`() {
        // A host that binds no actions supports neither the title bar nor hiding after launch.
        startKoin { }
        val form = SettingsFormState(SettingsData(useCustomChrome = true, closeAfterStart = true))
        assertTrue(form.useCustomChrome && form.closeAfterStart, "the premise: both are stored on")
        val scene = ImageComposeScene(900, 2000, density = Density(1f)) {
            LocaleProvider(AppLocale.ENGLISH) {
                NxTheme(dark = true) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AppearanceSection(
                            form = form,
                            save = {},
                            isDarkTheme = true,
                            onToggleTheme = {},
                            onOpenThemePicker = {},
                            onOpenBackgroundSettings = {},
                            currentLocale = AppLocale.ENGLISH,
                            onLocaleChanged = {},
                        )
                    }
                }
            }
        }
        try {
            repeat(4) {
                Snapshot.sendApplyNotifications()
                scene.render()
            }
            val disabled = scene.switches().filter { SemanticsProperties.Disabled in it.config }
            assertEquals(2, disabled.size, "the title bar and hiding after launch are the unsupported ones")
            disabled.forEach { switch ->
                assertEquals(ToggleableState.Off, switch.config.getOrNull(SemanticsProperties.ToggleableState), "an unsupported switch claims to be on")
            }
            assertTrue(form.useCustomChrome && form.closeAfterStart, "the stored values are left as they were")
            // The automation hook reads what the screen shows, as every other toggle's does.
            val puppets = PuppetRegistry.snapshot().elements.associateBy { it.id }
            for (id in listOf("settings.useCustomChrome", "settings.closeAfterStart")) {
                val toggle = assertNotNull(puppets[id], "no toggle $id")
                assertEquals(false, toggle.boolValue, "$id reports on under a switch drawn off")
                assertFalse(toggle.enabled)
            }
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.switches(): List<SemanticsNode> =
        semanticsOwners.asSequence()
            .flatMap { it.rootSemanticsNode.withDescendants() }
            .filter { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            .toList()

    private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }
}
