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

    /**
     * How wide a reading column is allowed to get before the surplus becomes
     * margin instead of stretch.
     *
     * The shell's centre is the only weighted child of the frame, so it takes
     * every pixel a wider window gains -- 1586dp at 1920, 2226dp at 2560 -- and
     * a card, a button or a field that fills it keeps its height while its width
     * tracks the monitor. The pack row reaches 16:1 that way, and the search
     * field 52:1, at which point it reads as a divider rather than a field.
     *
     * Sits just above the centre width of a 1920 window on purpose, so nothing
     * changes at or below FHD and only a genuinely wide screen sees the cap.
     */
    val contentMaxWidth: Dp = 1600.dp
}
