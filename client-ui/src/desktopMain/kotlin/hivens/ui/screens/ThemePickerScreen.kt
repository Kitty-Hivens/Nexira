package hivens.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import hivens.ui.theme.*

@Composable
fun ThemePickerScreen(
    currentTheme: CustomTheme,
    onThemeSelected: (CustomTheme) -> Unit,
    onBack: () -> Unit
) {
    var selectedTheme by remember { mutableStateOf(currentTheme) }
    val themes = remember { ThemePresets.getAll() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = CelestiaTheme.colors.primary
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "ВЫБОР ТЕМЫ",
                    style = MaterialTheme.typography.h5,
                    fontWeight = FontWeight.Black,
                    color = CelestiaTheme.colors.textPrimary
                )
            }

            // Apply button
            Button(
                onClick = { onThemeSelected(selectedTheme) },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = CelestiaTheme.colors.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("ПРИМЕНИТЬ", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Themes Grid
            Box(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(themes) { theme ->
                            ThemeCard(
                                theme = theme,
                                isSelected = theme == selectedTheme,
                                onClick = { selectedTheme = theme }
                            )
                        }
                    }
                }
            }

            // Preview Panel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                ThemePreviewPanel(theme = selectedTheme)
            }
        }
    }
}

@Composable
fun ThemeCard(
    theme: CustomTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
    )

    Box(
        modifier = Modifier
            .aspectRatio(1.2f)
            .scale(scale)
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .border(
                    width = if (isSelected) 3.dp else 0.dp,
                    brush = if (isSelected) Brush.linearGradient(
                        listOf(
                            CustomTheme.parseHexColor(theme.primary),
                            CustomTheme.parseHexColor(theme.secondary)
                        )
                    ) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            backgroundColor = CustomTheme.parseHexColor(theme.background).copy(alpha = 0.8f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Theme name
                Column {
                    Text(
                        theme.name,
                        style = MaterialTheme.typography.subtitle1,
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
                                "Выбрана",
                                style = MaterialTheme.typography.caption,
                                color = CustomTheme.parseHexColor(theme.primary),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Color palette
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
fun ThemePreviewPanel(theme: CustomTheme) {
    GlassCard(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = CustomTheme.parseHexColor(theme.background).copy(alpha = 0.8f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp)
        ) {
            Text(
                "ПРЕДПРОСМОТР",
                style = MaterialTheme.typography.caption,
                color = CustomTheme.parseHexColor(theme.primary),
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(24.dp))

            // Theme info
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorRow("Primary", theme.primary)
                ColorRow("Secondary", theme.secondary)
                ColorRow("Background", theme.background)
                ColorRow("Surface", theme.surface)
                ColorRow("Accent", theme.accent)
                ColorRow("Success", theme.success)
                ColorRow("Error", theme.error)
            }

            Spacer(Modifier.height(24.dp))

            // Sample buttons
            Button(
                onClick = { },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = CustomTheme.parseHexColor(theme.primary)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Sample Button",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(2.dp, CustomTheme.parseHexColor(theme.primary)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Outlined Button",
                    color = CustomTheme.parseHexColor(theme.primary),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ColorRow(label: String, hexColor: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.body2,
            color = CelestiaTheme.colors.textSecondary
        )
        
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
                style = MaterialTheme.typography.caption,
                color = CelestiaTheme.colors.textPrimary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
