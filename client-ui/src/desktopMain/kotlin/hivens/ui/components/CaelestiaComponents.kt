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
import hivens.ui.effects.pulsatingGlow

/**
 * Main Celestia container — translucent, with a thin border.
 *
 * [borderColor] is now theme-aware: if [Color.Unspecified] is passed (or omitted),
 * a light gray border is used in light theme, and dark gray in dark theme.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    backgroundColor: Color = MaterialTheme.colors.surface.copy(alpha = 0.7f),
    borderColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    val resolvedBorder = when {
        borderColor != Color.Unspecified -> borderColor
        MaterialTheme.colors.isLight     -> Color(0xFFCCCCCC)
        else                             -> Color(0xFF2C2C2C)
    }

    Surface(
        modifier  = modifier,
        shape     = shape,
        color     = backgroundColor,
        border    = BorderStroke(1.dp, resolvedBorder),
        elevation = 0.dp
    ) {
        Box(content = content)
    }
}

/**
 * Celestia button.
 *
 * Setting [glowing] = true enables a pulsating neon glow behind the button —
 * perfect for the PLAY button in the Idle state.
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
    val glowColor = MaterialTheme.colors.primary

    Button(
        onClick   = onClick,
        modifier  = modifier
            .height(48.dp)
            .let { if (glowing) it.pulsatingGlow(glowColor, cornerRadius = 12.dp) else it },
        enabled   = enabled,
        shape     = RoundedCornerShape(12.dp),
        colors    = ButtonDefaults.buttonColors(
            backgroundColor      = if (primary) MaterialTheme.colors.primary else Color.Transparent,
            contentColor         = if (primary) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
            disabledBackgroundColor = if (primary)
                MaterialTheme.colors.primary.copy(alpha = 0.5f)
            else
                Color.Transparent
        ),
        border    = if (!primary) BorderStroke(1.dp, Color(0xFF444444)) else null,
        elevation = null
    ) {
        Text(text)
    }
}
