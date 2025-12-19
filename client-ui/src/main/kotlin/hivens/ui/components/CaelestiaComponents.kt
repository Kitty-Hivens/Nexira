package hivens.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Основной контейнер Celestia - темный, полупрозрачный, с тонкой обводкой.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    backgroundColor: Color = MaterialTheme.colors.surface.copy(alpha = 0.7f),
    borderColor: Color = Color(0xFF333333),
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        elevation = 0.dp // Celestia плоская, тени через слои
    ) {
        Box(content = content)
    }
}

@Composable
fun CelestiaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (primary) MaterialTheme.colors.primary else Color.Transparent,
            contentColor = if (primary) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
        ),
        border = if (!primary) BorderStroke(1.dp, Color(0xFF444444)) else null,
        elevation = null
    ) {
        Text(text)
    }
}
