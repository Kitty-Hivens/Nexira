package hivens.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.components.GlassCard
import hivens.ui.easter.AprilFoolsButton
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.*

@Composable
fun ThemePickerScreen(
    currentTheme: CustomTheme,
    onThemeSelected: (CustomTheme) -> Unit,
    onBack: () -> Unit
) {
    val s = LocalStrings.current
    var selectedTheme by remember { mutableStateOf(currentTheme) }
    val themes = remember { ThemePresets.getAll() }

    PuppetScreen("ThemePicker")
    PuppetClick("themePicker.back") { onBack() }
    PuppetClick("themePicker.apply") { onThemeSelected(selectedTheme) }
    // Per-theme select. Puppet driver names themes by their canonical
    // theme.name (free text -- Tea Sakura, Aurora, etc.). LazyVerticalGrid
    // only composes visible rows; scroll the grid first if you target an
    // off-screen theme. Aura's preset list fits in one viewport.
    themes.forEach { theme ->
        PuppetClick("themePicker.select.${theme.name}") { selectedTheme = theme }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = s.navBack,
                        tint = CelestiaTheme.colors.primary
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    s.themePickerTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = CelestiaTheme.colors.textPrimary
                )
            }

            // Apply button -- chaos target
            AprilFoolsButton(
                id      = "theme_picker_apply_btn",
                text    = s.themePickerApply,
                onClick = { onThemeSelected(selectedTheme) },
                colors  = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(modifier = Modifier.weight(2f).fillMaxHeight()) {
                GlassCard(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp)) {
                    LazyVerticalGrid(
                        columns               = GridCells.Fixed(2),
                        contentPadding        = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement   = Arrangement.spacedBy(16.dp)
                    ) {
                        items(themes) { theme ->
                            ThemeCard(
                                theme         = theme,
                                isSelected    = theme == selectedTheme,
                                selectedLabel = s.themePickerSelected,
                                onClick       = { selectedTheme = theme }
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                ThemePreviewPanel(theme = selectedTheme, s = s)
            }
        }
    }
}

@Composable
fun ThemeCard(
    theme: CustomTheme,
    isSelected: Boolean,
    selectedLabel: String,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue   = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
    )

    Box(modifier = Modifier.aspectRatio(1.2f).scale(scale)) {
        GlassCard(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .border(
                    width  = if (isSelected) 3.dp else 0.dp,
                    brush  = if (isSelected) Brush.linearGradient(
                        listOf(
                            CustomTheme.parseHexColor(theme.primary),
                            CustomTheme.parseHexColor(theme.secondary)
                        )
                    ) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                    shape  = RoundedCornerShape(16.dp)
                ),
            shape           = RoundedCornerShape(16.dp),
            backgroundColor = CustomTheme.parseHexColor(theme.background).copy(alpha = 0.8f)
        ) {
            Column(
                modifier            = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        theme.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CustomTheme.parseHexColor(theme.primary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(4.dp))

                    if (isSelected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CustomTheme.parseHexColor(theme.primary).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = CustomTheme.parseHexColor(theme.primary),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                selectedLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = CustomTheme.parseHexColor(theme.primary),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorCircle(CustomTheme.parseHexColor(theme.primary))
                    ColorCircle(CustomTheme.parseHexColor(theme.secondary))
                    ColorCircle(CustomTheme.parseHexColor(theme.accent))
                    ColorCircle(CustomTheme.parseHexColor(theme.success))
                }
            }
        }
    }
}

@Composable
fun ColorCircle(color: Color) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
    )
}

@Composable
fun ThemePreviewPanel(theme: CustomTheme, s: hivens.ui.i18n.AppStrings) {
    GlassCard(
        modifier        = Modifier.fillMaxSize(),
        shape           = RoundedCornerShape(16.dp),
        backgroundColor = CustomTheme.parseHexColor(theme.background).copy(alpha = 0.8f)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                s.themePickerPreview,
                style = MaterialTheme.typography.bodySmall,
                color = CustomTheme.parseHexColor(theme.primary),
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(24.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorRow(s.themePickerColorPrimary,    theme.primary)
                ColorRow(s.themePickerColorSecondary,  theme.secondary)
                ColorRow(s.themePickerColorBackground, theme.background)
                ColorRow(s.themePickerColorSurface,    theme.surface)
                ColorRow(s.themePickerColorAccent,     theme.accent)
                ColorRow(s.themePickerColorSuccess,    theme.success)
                ColorRow(s.themePickerColorError,      theme.error)
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = CustomTheme.parseHexColor(theme.primary)),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Text(s.themePickerBtnSample, color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick  = {},
                modifier = Modifier.fillMaxWidth(),
                border   = BorderStroke(2.dp, CustomTheme.parseHexColor(theme.primary)),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Text(s.themePickerBtnOutlined, color = CustomTheme.parseHexColor(theme.primary), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ColorRow(label: String, hexColor: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CelestiaTheme.colors.textSecondary)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CustomTheme.parseHexColor(hexColor))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            )
            Text(
                hexColor,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textPrimary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
