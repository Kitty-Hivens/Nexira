package hivens.ui.chrome

import hivens.config.Branding
import hivens.i18n.api.Strings
import hivens.widget.model.Frame
import hivens.window.RESTORE_WINDOW_SIZE
import java.awt.Dimension

// What a desktop window makes of the frame its composition asks for, in AWT user space, which is the dp value 1:1 for
// frame geometry. An axis the frame says nothing of is the window's own.

/** The size the window has while it is not maximised. */
val Frame.size: Dimension
    get() = Dimension(width.takeIf { it > 0 } ?: RESTORE_WINDOW_SIZE.width, height.takeIf { it > 0 } ?: RESTORE_WINDOW_SIZE.height)

/** The least the window may be made, or null where the frame names none. */
val Frame.minimum: Dimension?
    get() = Dimension(minWidth.coerceAtLeast(0), minHeight.coerceAtLeast(0)).takeIf { it.width > 0 || it.height > 0 }

/** The window's title: the launcher's own words for it where they have some, the title as written otherwise, the launcher's name where there is none. */
fun Frame.titleIn(strings: Strings): String = if (title.isBlank()) Branding.TITLE else strings.text(title)
