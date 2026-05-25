package hivens.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.background.BackgroundLoopMode
import hivens.ui.background.BackgroundSettings
import hivens.ui.background.CustomBackground
import hivens.ui.background.ScaleMode
import hivens.ui.components.GlassCard
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch

@Composable
fun BackgroundSettingsScreen(
    currentSettings: BackgroundSettings,
    onSettingsChanged: (BackgroundSettings) -> Unit,
    onBack: () -> Unit
) {
    val s = LocalStrings.current
    var settings by remember { mutableStateOf(currentSettings) }
    val scope = rememberCoroutineScope()

    fun update(block: BackgroundSettings.() -> BackgroundSettings) {
        settings = settings.block()
        onSettingsChanged(settings)
    }

    PuppetScreen("BackgroundSettings")
    PuppetClick("background.back") { onBack() }
    PuppetToggle("background.enabled", settings.enabled) { update { copy(enabled = it) } }
    PuppetClick("background.clearImage", enabled = settings.imagePath != null) {
        update { copy(imagePath = null, enabled = false) }
    }
    PuppetClick("background.reset") {
        settings = BackgroundSettings(); onSettingsChanged(settings)
    }
    // Puppet exposes only the binary controls here: PuppetField is string-typed
    // so float sliders aren't reachable, and scale-mode / tint-color buttons
    // require selecting by enum/hex name which has no puppet shape yet.

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, s.navBack, tint = CelestiaTheme.colors.textPrimary) }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(s.backgroundTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = CelestiaTheme.colors.textPrimary)
                Text(s.backgroundSubtitle, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Controls
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    // Enable
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wallpaper, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(s.backgroundEnable, fontWeight = FontWeight.Bold, color = CelestiaTheme.colors.textPrimary)
                        }
                        Switch(checked = settings.enabled, onCheckedChange = { update { copy(enabled = it) } }, colors = SwitchDefaults.colors(checkedThumbColor = CelestiaTheme.colors.primary, checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)))
                    }

                    HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.15f))

                    // Image
                    SectionTitle(s.backgroundSectionImage)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch {
                            FileKit.openFilePicker(type = FileKitType.File(extensions = listOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "apng")), dialogSettings = FileKitDialogSettings(
                                title = s.backgroundPickFile
                            )
                            )?.path?.let { path -> update { copy(imagePath = path, enabled = true) } }
                        } }, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s.backgroundPickButton)
                        }
                        if (settings.imagePath != null) {
                            IconButton(onClick = { update { copy(imagePath = null, enabled = false) } }) { Icon(Icons.Default.Delete, null, tint = CelestiaTheme.colors.error) }
                        }
                    }
                    if (settings.imagePath != null) {
                        Text(settings.imagePath!!.substringAfterLast("/").substringAfterLast("\\"), style = MaterialTheme.typography.labelSmall, color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f))
                    }

                    HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.15f))

                    // Scale
                    SectionTitle(s.backgroundSectionScale)
                    val scaleModes = listOf(ScaleMode.COVER to s.backgroundScaleCover, ScaleMode.CONTAIN to s.backgroundScaleContain, ScaleMode.STRETCH to s.backgroundScaleStretch, ScaleMode.ORIGINAL to s.backgroundScaleOriginal, ScaleMode.TILE to s.backgroundScaleTile)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        scaleModes.forEach { (mode, label) ->
                            val selected = settings.scaleMode == mode
                            Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) CelestiaTheme.colors.primary else glassSurfaceAlpha(0.4f)).clickable { update { copy(scaleMode = mode) } }, contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) Color.White else CelestiaTheme.colors.textSecondary)
                            }
                        }
                    }

                    // Position
                    SectionTitle(s.backgroundSectionPosition)
                    LabeledSlider(s.backgroundAlignX, settings.alignX, 0f..1f) { update { copy(alignX = it) } }
                    LabeledSlider(s.backgroundAlignY, settings.alignY, 0f..1f) { update { copy(alignY = it) } }

                    HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.15f))

                    // Effects
                    SectionTitle(s.backgroundSectionEffects)
                    LabeledSlider(s.backgroundBlur, settings.blurRadius, 0f..25f, "%.0f px") { update { copy(blurRadius = it) } }
                    LabeledSlider(s.backgroundDarken, settings.darkenAmount, 0f..0.9f, "%.0f%%", 100f) { update { copy(darkenAmount = it) } }
                    LabeledSlider(s.backgroundOpacity, settings.opacity, 0.1f..1f, "%.0f%%", 100f) { update { copy(opacity = it) } }
                    LabeledSlider(s.backgroundSaturation, settings.saturation, -1f..1f, "%+.0f%%", 100f) { update { copy(saturation = it) } }
                    LabeledSlider(s.backgroundParallax, settings.parallaxIntensity, 0f..1f, "%.0f%%", 100f) { update { copy(parallaxIntensity = it) } }
                    LabeledSlider(s.backgroundVignette, settings.vignetteIntensity, 0f..1f, "%.0f%%", 100f) { update { copy(vignetteIntensity = it) } }
                    LabeledSlider(s.backgroundAnimationSpeed, settings.animationSpeedMultiplier, 0.25f..4f, "%.2fx") { update { copy(animationSpeedMultiplier = it) } }

                    SectionTitle(s.backgroundLoopMode)
                    val loopModes = listOf(
                        BackgroundLoopMode.UseCodec    to s.backgroundLoopUseCodec,
                        BackgroundLoopMode.LoopForever to s.backgroundLoopForever,
                        BackgroundLoopMode.PlayOnce    to s.backgroundLoopOnce,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        loopModes.forEach { (mode, label) ->
                            val selected = settings.loopMode == mode
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) CelestiaTheme.colors.primary else glassSurfaceAlpha(0.4f))
                                    .clickable { update { copy(loopMode = mode) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                     color = if (selected) Color.White else CelestiaTheme.colors.textSecondary)
                            }
                        }
                    }

                    HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.15f))

                    // Tint
                    SectionTitle(s.backgroundSectionTint)
                    val tintPresets = listOf(null to s.backgroundTintNone, "#1A1A2E" to s.backgroundTintNavy, "#2D1B4E" to s.backgroundTintViolet, "#0D3B2E" to s.backgroundTintEmerald, "#3B1515" to s.backgroundTintBordeaux, "#1B2A3B" to s.backgroundTintSteel)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tintPresets.forEach { (hex, label) ->
                            val selected = settings.tintColor == hex
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).border(if (selected) 2.dp else 0.dp, if (selected) CelestiaTheme.colors.primary else Color.Transparent, RoundedCornerShape(6.dp)).clickable { update { copy(tintColor = hex, tintOpacity = if (hex != null) 0.3f else 0f) } }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(if (hex != null) try { Color(("FF" + hex.removePrefix("#")).toLong(16)) } catch (_: Exception) { Color.Gray } else CelestiaTheme.colors.surface).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp)))
                                Spacer(Modifier.height(4.dp))
                                Text(label, fontSize = 9.sp, color = CelestiaTheme.colors.textSecondary)
                            }
                        }
                    }
                    if (settings.tintColor != null) {
                        LabeledSlider(s.backgroundTintIntensity, settings.tintOpacity, 0f..0.7f, "%.0f%%", 100f) { update { copy(tintOpacity = it) } }
                    }

                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { settings = BackgroundSettings(); onSettingsChanged(settings) }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s.backgroundReset)
                    }
                }
            }

            // Preview
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                val previewMousePos = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
                var previewSize by remember { mutableStateOf(IntSize.Zero) }

                Box(
                    Modifier.fillMaxSize()
                        .onSizeChanged { previewSize = it }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Move) {
                                        val pos = event.changes.firstOrNull()?.position
                                        if (pos != null && previewSize.width > 0 && previewSize.height > 0) {
                                            previewMousePos.value = Offset(pos.x / previewSize.width, pos.y / previewSize.height)
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    CustomBackground(settings = settings, mousePosProvider = { previewMousePos.value })
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text(s.backgroundPreview, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                        Column {
                            Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(12.dp)).background(glassSurfaceAlpha(0.6f)).border(1.dp, CelestiaTheme.colors.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp)).padding(16.dp), contentAlignment = Alignment.CenterStart) {
                                Column {
                                    Text(s.backgroundPreviewServer, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                                    Text("1.21.1 • 42/100", style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(CelestiaTheme.colors.primary), contentAlignment = Alignment.Center) {
                                Text(s.launchButton, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = CelestiaTheme.colors.primary, letterSpacing = 1.sp)
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String = "%.2f", displayMultiplier: Float = 1f, onValueChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary, modifier = Modifier.width(110.dp))
        Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = CelestiaTheme.colors.primary, activeTrackColor = CelestiaTheme.colors.primary, inactiveTrackColor = CelestiaTheme.colors.outline.copy(alpha = 0.2f)))
        Text(format.format(value * displayMultiplier), style = MaterialTheme.typography.labelSmall, color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f), modifier = Modifier.width(44.dp))
    }
}
