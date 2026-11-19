package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxThemed

/**
 * Letter-monogram fallback for a missing icon: up to two initials from
 * [name]'s words, in whichever ink reads on the entity's [hue]. Fills its parent -- size
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
            color      = NxColor.on(hue),
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Preview
@Composable
private fun InitialsAvatarDarkPreview() = InitialsAvatars(dark = true)

@Preview
@Composable
private fun InitialsAvatarLightPreview() = InitialsAvatars(dark = false)

@Composable
private fun InitialsAvatars(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Row(
            modifier              = Modifier.width(420.dp).background(NxColor.page).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            for (name in listOf("Better End", "create-mod", "Vanilla", "")) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) { InitialsAvatar(name, NxColor.hashed(name)) }
            }
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))) { InitialsAvatar("Small Pack", NxColor.lead()) }
        }
    }
}
