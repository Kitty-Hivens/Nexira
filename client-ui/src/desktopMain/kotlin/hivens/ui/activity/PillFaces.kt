package hivens.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.Form
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor

/**
 * One face of a subject stack, and the tile standing for the ones that did not
 * fit.
 *
 * Shared by both bodies of the activity surface on purpose. What the launcher is
 * doing and what the user has picked are different sentences, but they are drawn
 * by the same object, and a stack that looked subtly different between the two
 * would say they are two objects taking turns.
 *
 * [icon] is whatever the image loader can take: a URL for something fetched, or
 * the raw bytes a mod carries inside its own jar. Most content has the second
 * and no address at all, so a face typed to a URL showed initials for nearly
 * everything.
 *
 * Rounded squares rather than discs: at this size and overlap circles read as one
 * smear, while a square corner keeps each face a separate thing. The radius still
 * follows the form axis, so a square style squares these too.
 */
@Composable
internal fun StackFace(key: String, title: String, icon: Any?, onClick: (() -> Unit)? = null) {
    val tint = NxTheme.colors.decorativeColor(key)
    val initials = title.take(2).uppercase()
    val shape = RoundedCornerShape(faceCorner())
    // The ring takes the body colour, so overlapping faces stay separate objects
    // rather than merging into a single blob.
    val ring = NxTheme.colors.surfaceContainerHigh
    SubcomposeAsyncImage(
        model = icon,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(FACE_SIZE)
            .clip(shape)
            .border(1.5.dp, ring, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        loading = { Box(Modifier.fillMaxSize().background(tint)) },
        error = {
            Box(Modifier.fillMaxSize().background(tint), contentAlignment = Alignment.Center) {
                Text(
                    initials,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

/** The tail of a stack: one more of the same object, counting the rest. */
@Composable
internal fun StackOverflow(count: Int) {
    val shape = RoundedCornerShape(faceCorner())
    Box(
        Modifier
            .size(FACE_SIZE)
            .clip(shape)
            .background(NxTheme.colors.surfaceContainer)
            .border(1.5.dp, NxTheme.colors.surfaceContainerHigh, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            LocalStrings.current.activityPillMore(count),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NxTheme.colors.textSecondary,
        )
    }
}

/** Capped so a face never rounds into a disc, and still squares under a square form. */
@Composable
private fun faceCorner(): Dp = minOf(Form.panelCorner, 9.dp)

/** Diameter of one face in a subject stack. */
internal val FACE_SIZE = 36.dp
