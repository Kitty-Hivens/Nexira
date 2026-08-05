package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * Letter-monogram fallback for a missing icon: up to two initials from
 * [name]'s words, white over the entity's [hue]. Fills its parent -- size
 * it from the outside (an AsyncImage error slot, an avatar Box).
 */
@Composable
fun InitialsAvatar(name: String, hue: Color, modifier: Modifier = Modifier) {
    val initials = name
        .split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    Box(
        modifier         = modifier.fillMaxSize().background(hue),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = initials,
            color      = Color.White,
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
