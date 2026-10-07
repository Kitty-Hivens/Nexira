package hivens.ui.widgets.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.notifications.SessionRegistry
import hivens.ui.settle
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The running games as the title bar carries them, at the bar's own height, with
 * one short and one long pack name. The bar is 44dp, and an entry that does not
 * fit in it would push the lanes beside it off.
 */
class TopBarSessionsRenderTest {

    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        stopKoin()
        scope.cancel()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `running games sit in the title bar`() {
        val start = Instant.parse("2026-10-05T12:00:00Z")
        val now = start.plusSeconds(3_725)
        val registry = SessionRegistry(scope) { now }
        registry.register("a", "Industrial", null, {}, {})
        registry.register("b", "Create: Above and Beyond, with a long name", null, {}, {})
        startKoin { modules(module { single { registry } }) }

        val d = 2f
        val scene = ImageComposeScene((720 * d).toInt(), (44 * d).toInt(), density = Density(d)) {
            LocaleProvider(AppLocale.RUSSIAN) {
                NxTheme(dark = true) {
                    Box(
                        Modifier.fillMaxSize().background(NxColor.page),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterEnd) {
                            TopBarSessionsWidget()
                        }
                    }
                }
            }
        }
        val t = scene.settle(frames = 30)
        val img = scene.render(t)
        scene.close()
        File("build/render").mkdirs()
        val png = img.encodeToData(EncodedImageFormat.PNG)?.bytes
        png?.let { File("build/render/topbar-sessions.png").writeBytes(it) }
        assertTrue((png?.size ?: 0) > 0)
    }
}
