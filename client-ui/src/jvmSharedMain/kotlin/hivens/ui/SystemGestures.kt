package hivens.ui

import androidx.compose.ui.Modifier

/**
 * Asks the system to leave swipes in this area to the app. Where the system keeps an edge for its
 * own gestures (going back), a swipe that starts there is its, and a control that wants one has to
 * ask. The system honours a limited height per edge, so it belongs on a catch, not a whole edge.
 */
internal expect fun Modifier.systemGestureExcluded(): Modifier
