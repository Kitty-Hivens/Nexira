package hivens.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import hivens.ui.effects.pulsatingGlow
import hivens.ui.theme.CelestiaTheme

/**
 * Main Celestia container -- translucent, with a thin border.
 *
 * [borderColor] is now theme-aware: if [Color.Unspecified] is passed (or omitted),
 * a light gray border is used in light theme, and dark gray in dark theme.
 */
@Composable
fun GlassCard( // TODO: translate to English!
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    backgroundColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
    borderColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    val resolvedBorder = when {
        borderColor != Color.Unspecified -> borderColor
        MaterialTheme.colorScheme.surface.luminance() < 0.5f -> Color(0xFF2C2C2C)
        else -> Color(0xFFCCCCCC)
    }

    OutlinedCard(
        modifier  = modifier,
        shape     = shape,
        colors    = CardDefaults.outlinedCardColors(containerColor = backgroundColor),
        border    = BorderStroke(1.dp, resolvedBorder),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp)
    ) {
        Box(content = content)
    }
}

/**
 * Кнопка Celestia.
 *
 * [glowing] = true включает пульсирующий неоновый эффект --
 * идеально для кнопки PLAY в состоянии Idle.
 */
@Composable
fun CelestiaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    glowing: Boolean = false
) {
    val glowColor = MaterialTheme.colorScheme.primary

    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier
                .height(48.dp)
                .let { if (glowing) it.pulsatingGlow(glowColor, cornerRadius = 12.dp) else it },
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CelestiaTheme.colors.primary,
                contentColor = Color.White,
                disabledContainerColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.6f)
            ),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            border = BorderStroke(1.dp, Color(0xFF444444))
        ) {
            Text(text)
        }
    }
}
