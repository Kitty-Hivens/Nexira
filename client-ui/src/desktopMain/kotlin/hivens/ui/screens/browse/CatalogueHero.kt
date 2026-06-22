package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.hivens.skinema.compose.VideoScale
import hivens.ui.components.FullscreenVideo
import hivens.ui.components.VideoMedia
import hivens.ui.components.isVideoUrl
import hivens.ui.effects.pixelArtBackground
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair

/**
 * Shared catalogue-detail hero, used by the source-neutral [CataloguePackDetailScreen]
 * so a pack looks the same across sources and matches the installed-instance hero in
 * Library. Real banner
 * over a [seed]-stable pixel-art fallback (never a flat gradient), a dark scrim so
 * white text stays legible over any image, an icon avatar (letter fallback), and
 * an overlaid title + tagline. [onBack] draws the corner back affordance.
 */
@Composable
fun CatalogueHero(
    title: String,
    tagline: String,
    iconUrl: String?,
    bannerUrl: String?,
    seed: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val (hueA, hueB) = NxTheme.colors.decorativePair(seed)
    val bannerIsVideo = bannerUrl != null && isVideoUrl(bannerUrl)
    var bannerFullscreen by remember(bannerUrl) { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth().height(200.dp)) {
        Box(Modifier.fillMaxSize().pixelArtBackground(seed, hueA, hueB))
        if (bannerUrl != null) {
            if (bannerIsVideo) {
                // Ambient banner: autoplays muted on a loop, no chrome; the expand
                // button opens it with sound in fullscreen.
                VideoMedia(
                    url          = bannerUrl,
                    modifier     = Modifier.fillMaxSize(),
                    autoPlay     = true,
                    loop         = true,
                    audio        = false,
                    startMuted   = true,
                    showControls = false,
                    scale        = VideoScale.Cover,
                )
            } else {
                AsyncImage(
                    model              = bannerUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }
        // Flat tint + bottom-weighted gradient: keeps the title readable over a
        // bright banner without washing the whole image out.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (bannerUrl != null) 0.30f else 0.22f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.68f))))

        if (onBack != null) {
            Box(
                modifier         = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(NxIcon.ArrowBack, contentDescription = null, tint = Color.White, size = 20.dp)
            }
        }

        if (bannerIsVideo) {
            Box(
                modifier         = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable { bannerFullscreen = true },
                contentAlignment = Alignment.Center,
            ) {
                Symbol(NxIcon.OpenInFull, contentDescription = null, tint = Color.White, size = 18.dp)
            }
        }

        Row(
            modifier              = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
            verticalAlignment     = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroAvatar(iconUrl = iconUrl, title = title, hue = hueA)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.headlineMedium,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                )
                // Mirror summaries sometimes ship tagline == name; don't echo the title.
                if (tagline.isNotBlank() && !tagline.equals(title, ignoreCase = true)) {
                    Text(
                        text     = tagline,
                        style    = MaterialTheme.typography.bodyLarge,
                        color    = Color.White.copy(alpha = 0.88f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Optional primary action (e.g. the install glyph), pinned bottom-end.
            if (action != null) action()
        }
    }
    if (bannerFullscreen && bannerUrl != null) {
        FullscreenVideo(url = bannerUrl, onDismiss = { bannerFullscreen = false })
    }
}

/**
 * Primary install affordance for the catalogue detail heroes -- a glyph, no label.
 * [busy] swaps the icon for a spinner; [enabled] dims and disables it.
 */
@Composable
fun InstallGlyphButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier         = modifier
            .size(44.dp)
            .then(if (enabled && !busy) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        } else {
            Symbol(NxIcon.Download, contentDescription = null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f), size = 30.dp)
        }
    }
}

@Composable
private fun HeroAvatar(iconUrl: String?, title: String, hue: Color) {
    Box(
        modifier         = Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)).background(hue),
        contentAlignment = Alignment.Center,
    ) {
        if (iconUrl != null) {
            AsyncImage(model = iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text       = title.firstOrNull()?.uppercase() ?: "?",
                style      = MaterialTheme.typography.headlineSmall,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
