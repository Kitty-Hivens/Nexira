package hivens.ui

import androidx.compose.runtime.Composable

/** Lets the system back gesture shut something that sits over the page. A window with no such gesture does nothing. */
@Composable
internal expect fun SheetBackHandler(enabled: Boolean, onBack: () -> Unit)
