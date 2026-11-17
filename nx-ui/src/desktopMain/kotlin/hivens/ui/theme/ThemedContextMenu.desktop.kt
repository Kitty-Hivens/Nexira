package hivens.ui.theme

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import hivens.look.colour.api.ColourAnswers

@Composable
internal actual fun ThemedContextMenu(answers: ColourAnswers, content: @Composable () -> Unit) {
    val contextMenu = remember(answers) {
        val top = answers.groundDepth(answers.groundFloating)
        DefaultContextMenuRepresentation(
            backgroundColor = top,
            textColor       = answers.inkMain,
            itemHoverColor  = lerp(top, answers.paintLead, 0.14f),
        )
    }
    CompositionLocalProvider(LocalContextMenuRepresentation provides contextMenu) {
        content()
    }
}
