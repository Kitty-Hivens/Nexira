package hivens.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fixed component dimensions shared across more than one call site. A number
 * that two composables must agree on (the pack card height, the pack avatar
 * size) lives here once instead of being copied as a magic literal into each,
 * where the copies silently diverge.
 *
 * Unlike [Spacing], these are not a scale -- each is a specific component
 * measurement. Add one only when a dimension is genuinely shared; a value used
 * in a single place stays local to it.
 */
object Dimens {
    /** Minimum height of a pack row (Library + Browse). Content may grow past it. */
    val packCardHeight: Dp = 132.dp
    /** Square pack avatar edge on those rows. */
    val packAvatar: Dp = 64.dp
    /** Corner rounding on the pack avatar. */
    val packAvatarCorner: Dp = 12.dp
}
