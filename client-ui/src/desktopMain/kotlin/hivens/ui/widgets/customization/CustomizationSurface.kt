package hivens.ui.widgets.customization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.components.GlassCard
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

private const val SURFACE = "customization"

// customization surface. AppLayout routes Screen.CustomizationExtension
// here. Four slots:
//   visual  -- always rendered (density, glass intensity, accent override, experimental toggle)
//   colors  -- only when experimentalColorOverridesEnabled (12 color roles + glass alpha)
//   shape   -- only when experimentalColorOverridesEnabled (corner radii, anim multiplier, soft glow)
//   actions -- always rendered (reset)
//
// Conditional mounting of colors + shape means the editor only
// exposes those widgets when the user has experimental on. Toggling
// it off unmounts them; reset-to-default brings them back.
//
// Surface keeps verticalScroll on the inner Column because 4 + 13 + 5
// + 1 widgets exceed any reasonable window height. Dropping a Lazy
// widget into customization via the editor will crash; Phase 5
// palette surface-compat is the proper fix.
//
// Density counter-wrap: the screen lives inside a Density that's
// the inverse of densityScale so the density slider stays
// grabbable while every other surface live-scales as the user
// drags. Without this, every drag tick re-measures the slider
// host under a new density and the gesture detector loses the
// pointer.
@Composable
fun CustomizationSurface(
    currentSettings: CustomizationSettings,
    onSettingsChanged: (CustomizationSettings) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    val settings = remember { mutableStateOf(currentSettings) }
    val update: (CustomizationSettings.() -> CustomizationSettings) -> Unit =
        remember(onSettingsChanged) {
            { block ->
                settings.value = settings.value.block()
                onSettingsChanged(settings.value)
            }
        }

    val ctx = remember(settings, update) {
        CustomizationContext(settings = settings, update = update)
    }

    PuppetScreen("CustomizationExtension")
    PuppetClick("customization.back") { onBack() }
    PuppetClick("customization.reset") {
        settings.value = CustomizationSettings()
        onSettingsChanged(settings.value)
    }

    val outerDensity = LocalDensity.current
    val baseDensity  = remember(outerDensity, settings.value.densityScale) {
        Density(
            outerDensity.density / settings.value.densityScale.coerceAtLeast(0.01f),
            outerDensity.fontScale,
        )
    }

    CompositionLocalProvider(
        LocalDensity              provides baseDensity,
        LocalCustomizationContext provides ctx,
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Symbol(icon = NxIcon.ArrowBack,
                        contentDescription = null,
                        tint               = CelestiaTheme.colors.textPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text       = s.customizationTitle,
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color      = CelestiaTheme.colors.textPrimary,
                    )
                    Text(
                        text  = s.customizationSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            GlassCard(Modifier.fillMaxSize()) {
                Column(
                    modifier            = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Multi-slot scroll column: each slot keeps its own
                    // 16dp intra-slot spacing via `spacing`; the outer
                    // Column spaces the slot blocks + dividers.
                    SlotRenderer(SurfaceId(SURFACE), SlotId("visual"), spacing = 16.dp)
                    HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.15f))

                    if (settings.value.experimentalColorOverridesEnabled) {
                        SlotRenderer(SurfaceId(SURFACE), SlotId("colors"), spacing = 16.dp)
                        HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.15f))
                        SlotRenderer(SurfaceId(SURFACE), SlotId("shape"), spacing = 16.dp)
                        HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.15f))
                    }

                    SlotRenderer(SurfaceId(SURFACE), SlotId("actions"), spacing = 16.dp)
                }
            }
        }
    }
}
