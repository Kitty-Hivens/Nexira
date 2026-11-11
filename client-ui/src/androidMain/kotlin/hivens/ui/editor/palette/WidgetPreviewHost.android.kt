package hivens.ui.editor.palette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.WidgetKind

// Compose on Android has no off-screen scene to compose a widget into outside the
// surface it belongs to, so every tile shows its letter, which is what a widget that
// cannot be drawn there already gets.
@Composable
actual fun rememberWidgetPreviewHost(): WidgetPreviewHost = remember { LetterPreviewHost }

private object LetterPreviewHost : WidgetPreviewHost {
    override fun peek(kind: WidgetKind): WidgetPreview = WidgetPreview.Refused
    override suspend fun draw(kind: WidgetKind, descriptor: WidgetDescriptor) = Unit
    override fun close() = Unit
}
