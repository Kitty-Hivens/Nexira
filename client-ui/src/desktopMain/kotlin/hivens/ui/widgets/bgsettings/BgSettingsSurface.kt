package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hivens.ui.background.BackgroundSettings
import hivens.ui.components.GlassCard
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

private const val SURFACE = "bg.settings"

// bg.settings surface. AppLayout routes Screen.BackgroundSettings
// here. Two slots: `controls` (weight 1, holds enable+image+scale+
// position+effects+loop+tint+reset widgets in a scrollable card)
// and `preview` (weight 1, holds the live preview widget).
//
// The CONTROLS slot is wrapped in a GlassCard whose internal Column
// has verticalScroll -- this is the one surface where a slot needs
// scroll because the knob stack is genuinely tall (~15 widgets).
// The scroll wraps the slot itself; widgets inside cannot be
// Lazy-list-based without crashing, but the controls slot has no
// such widget by default and the palette filter (Phase 5) will
// eventually keep foreign Lazy widgets out. Until then, the user
// can crash here by dropping LazyColumn into controls -- documented
// trade-off, recoverable via reset.
//
// PREVIEW slot has no scroll; the preview widget fills its bounded
// box and lets CustomBackground handle layered rendering.
@Composable
fun BgSettingsSurface(
    currentSettings: BackgroundSettings,
    onSettingsChanged: (BackgroundSettings) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    val settings = remember { mutableStateOf(currentSettings) }
    val previewMousePos = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val previewSize = remember { mutableStateOf(IntSize.Zero) }

    val update: (BackgroundSettings.() -> BackgroundSettings) -> Unit = remember(onSettingsChanged) {
        { block ->
            settings.value = settings.value.block()
            onSettingsChanged(settings.value)
        }
    }

    val ctx = remember(settings, update, previewMousePos, previewSize) {
        BgSettingsContext(
            settings        = settings,
            update          = update,
            previewMousePos = previewMousePos,
            previewSize     = previewSize,
        )
    }

    PuppetScreen("BackgroundSettings")
    PuppetClick("background.back") { onBack() }
    PuppetToggle("background.enabled", settings.value.enabled) { update { copy(enabled = it) } }
    PuppetClick("background.clearImage", enabled = settings.value.imagePath != null) {
        update { copy(imagePath = null, enabled = false) }
    }
    PuppetClick("background.reset") {
        settings.value = BackgroundSettings()
        onSettingsChanged(settings.value)
    }

    CompositionLocalProvider(LocalBgSettingsContext provides ctx) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = s.navBack,
                        tint               = CelestiaTheme.colors.textPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text       = s.backgroundTitle,
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color      = CelestiaTheme.colors.textPrimary,
                    )
                    Text(
                        text  = s.backgroundSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                    SlotRenderer(
                        SurfaceId(SURFACE),
                        SlotId("controls"),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        spacing  = 16.dp,
                    )
                }

                GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                    SlotRenderer(SurfaceId(SURFACE), SlotId("preview"), Modifier.fillMaxSize())
                }
            }
        }
    }
}
