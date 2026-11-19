package hivens.ui.theme

import androidx.compose.runtime.Composable
import hivens.look.colour.api.ColourAnswers

@Composable
internal actual fun ThemedContextMenu(answers: ColourAnswers, content: @Composable () -> Unit) = content()
