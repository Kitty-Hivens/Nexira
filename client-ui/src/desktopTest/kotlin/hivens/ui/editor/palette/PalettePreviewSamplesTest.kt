package hivens.ui.editor.palette

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SettingsData
import hivens.ui.appearance.AppearanceStore
import hivens.ui.background.BackgroundSettings
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.onEventThread
import hivens.ui.theme.NxTheme
import hivens.ui.theme.ThemeManager
import hivens.ui.uiCommandRegistry
import hivens.ui.utils.PreferenceWriter
import hivens.ui.uiDataRegistry
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.ui.widgets.Sources
import hivens.ui.widgets.WidgetSurface
import hivens.ui.widgets.themepicker.ThemeSelectionSource
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.DefaultLayout
import hivens.widget.model.ScopeLevel
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A tile in the gallery for a widget that reads what its page hands it. The gallery
 * has no page, so it draws the widget on the samples the keys declare, and only a key
 * with none leaves the tile on its letter, which is [WidgetPreview.Refused].
 */
@OptIn(ExperimentalComposeUiApi::class)
class PalettePreviewSamplesTest {

    private val sampled = uiDataRegistry(
        activity         = MutableStateFlow(emptyList()),
        notifications    = MutableStateFlow(emptyList()),
        doNotDisturb     = MutableStateFlow(false),
        sslBypass        = flowSource(MutableStateFlow(false)),
        background       = MutableStateFlow(BackgroundSettings()),
        accountsRevision = MutableStateFlow(0),
    )

    private val commands = uiCommandRegistry({}, {}, {}, {})

    /**
     * What the composition's level reads the theme keys from, the way the launcher's
     * does: they are the composition's own and answered wherever it is, so only the
     * page's keys are left for the samples to stand in for.
     */
    private val themes: Path = Files.createTempDirectory("palette-preview-samples")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        themes.deleteRecursively()
    }

    private val appearance = AppearanceStore(
        settings    = object : ISettingsService {
            private var current = SettingsData()
            override fun getSettings(): SettingsData = current
            override fun saveSettings(settings: SettingsData) {
                current = settings
            }
        },
        preferences = PreferenceWriter("palette-preview-samples-test"),
        themes      = ThemeManager(themes) { _, _ -> },
        contributed = MutableStateFlow(emptyList()),
        scope       = CoroutineScope(Dispatchers.Unconfined),
    )

    /** The theme picker's keys as declared before they had samples. */
    private val bare = WidgetDataRegistry().apply {
        declare(Sources.ThemeLibrary)
        declare(Sources.DarkTheme)
        declare(ThemeSelectionSource)
    }

    /** What the gallery makes of [kind], drawn the way the palette draws it, under a composition like the editor's. */
    private fun previewOf(kind: String, data: WidgetDataRegistry, commands: WidgetCommandRegistry): WidgetPreview {
        val app: WidgetScope = koinApplication {
            modules(
                module {
                    single { appearance }
                    single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) }
                },
            )
        }.koin.get()
        val widget = WidgetKind(kind)
        return onEventThread {
            var host: OffscreenPreviewHost? = null
            val scene = ImageComposeScene(400, 400, density = Density(1f)) {
                LocaleProvider(AppLocale.ENGLISH) {
                    NxTheme(dark = true) {
                        CompositionLocalProvider(
                            LocalWidgetDataRegistry provides data,
                            LocalWidgetCommandRegistry provides commands,
                            LocalScope provides app,
                            LocalWidgetRegistry provides GeneratedWidgetRegistry,
                            LocalLayoutGraph provides DefaultLayout.load(),
                            LocalWidgetSurfaceRenderer provides { spec, body -> WidgetSurface(spec, body) },
                        ) {
                            ChildLevel(ScopeLevel.Composition) {
                                host = OffscreenPreviewHost(currentCompositionLocalContext, LocalDensity.current)
                            }
                        }
                    }
                }
            }
            try {
                scene.render().close()
                val gallery = checkNotNull(host)
                try {
                    runBlocking { gallery.draw(widget, checkNotNull(GeneratedWidgetRegistry[widget])) }
                    gallery.peek(widget)
                } finally {
                    gallery.close()
                }
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `the theme grid is drawn on its samples, not left on its letter`() {
        assertIs<WidgetPreview.Drawn>(previewOf("theme.picker.grid", sampled, commands))
    }

    @Test
    fun `the theme preview and the update panel are drawn on theirs`() {
        assertIs<WidgetPreview.Drawn>(previewOf("theme.picker.preview", sampled, commands))
        assertIs<WidgetPreview.Drawn>(previewOf("about.update.panel", sampled, commands))
    }

    @Test
    fun `the same widget with no samples to draw on keeps its letter`() {
        assertIs<WidgetPreview.Refused>(previewOf("theme.picker.grid", bare, WidgetCommandRegistry()))
    }
}
