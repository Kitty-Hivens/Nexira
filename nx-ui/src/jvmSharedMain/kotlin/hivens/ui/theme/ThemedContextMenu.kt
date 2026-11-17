package hivens.ui.theme

import androidx.compose.runtime.Composable
import hivens.look.colour.api.ColourAnswers

/**
 * Gives the right-click menu a text field opens the look's colours.
 *
 * Only a pointer host has such a menu: foundation draws it, and it defaults to an
 * unthemed light popup, so one representation at the root reaches every field. A
 * touch host has the system's own selection toolbar and nothing to provide.
 */
@Composable
internal expect fun ThemedContextMenu(answers: ColourAnswers, content: @Composable () -> Unit)
