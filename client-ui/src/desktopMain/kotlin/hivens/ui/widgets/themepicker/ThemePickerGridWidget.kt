package hivens.ui.widgets.themepicker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.components.GlassCard
import hivens.ui.customization.scaledAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CustomTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Grid of every preset CustomTheme. Tapping a card writes
// `selectedTheme.value` on the surface context; the preview widget
// reads the same context and re-renders. Removing this widget hides
// the picker -- the user keeps whatever theme was active at edit time
// and must restore-to-default to see the grid again.
@Widget(id = "theme.picker.grid", displayName = "Сетка тем")
@Composable
fun ThemePickerGridWidget(instance: WidgetInstance) {
    val ctx = LocalThemePickerContext.current
    val s = LocalStrings.current
    val selectedTheme by ctx.selectedTheme

    GlassCard(modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
        LazyVerticalGrid(
            columns               = GridCells.Fixed(2),
            contentPadding        = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement   = Arrangement.spacedBy(16.dp),
        ) {
            items(ctx.themes) { theme ->
                ThemeCard(
                    theme         = theme,
                    isSelected    = theme == selectedTheme,
                    selectedLabel = s.themePickerSelected,
                    onClick       = { ctx.selectedTheme.value = theme },
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: CustomTheme,
    isSelected: Boolean,
    selectedLabel: String,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue   = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
    )

    Box(modifier = Modifier.aspectRatio(1.2f).scale(scale)) {
        GlassCard(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .border(
                    width = if (isSelected) 3.dp else 0.dp,
                    brush = if (isSelected) Brush.linearGradient(
                        listOf(
                            CustomTheme.parseHexColor(theme.primary),
                            CustomTheme.parseHexColor(theme.secondary),
                        ),
                    ) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                    shape = RoundedCornerShape(16.dp),
                ),
            shape           = RoundedCornerShape(16.dp),
            backgroundColor = scaledAlpha(CustomTheme.parseHexColor(theme.background), 0.8f),
        ) {
            Column(
                modifier            = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text       = theme.name,
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color      = CustomTheme.parseHexColor(theme.primary),
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (isSelected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CustomTheme.parseHexColor(theme.primary).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Check,
                                contentDescription = null,
                                tint               = CustomTheme.parseHexColor(theme.primary),
                                modifier           = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text       = selectedLabel,
                                style      = MaterialTheme.typography.bodySmall,
                                color      = CustomTheme.parseHexColor(theme.primary),
                                fontWeight = FontWeight.Bold,
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
private fun ColorCircle(color: Color) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), CircleShape),
    )
}
