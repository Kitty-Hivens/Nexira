package hivens.ui.layers

import androidx.compose.runtime.Composable

/**
 * Makes [stack] the window's back handler while composed, closing its top entry
 * on back when nothing registered later took it.
 *
 * On the desktop this is the window's own navigation event dispatcher, which a
 * Compose window feeds with an Escape nothing focused used, and where every Compose
 * popup and dialog registers its own back handler when it opens. On Android it does
 * nothing: popups and dialogs are windows of their own there and take back before
 * the activity sees it, and the shell asks the stack from its back handler, placed
 * after the navigation's so the stack is asked first.
 */
@Composable
internal expect fun RouteBackToStack(stack: DismissStack)
