package hivens.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun SheetBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled = enabled, onBack = onBack)
