package hivens.ui.feature.catalogue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import java.util.UUID

/**
 * One showing of a catalogue page, named for the catalogue module: shown as the screen
 * comes into the composition, so the page it reads is the one for this visit from the
 * first frame, and hidden as the screen leaves it, so the rail goes back to what it
 * showed before. A screen drawn again for another entry is another visit.
 *
 * [show] and [hide] are the page's own commands, null where no catalogue module is
 * brought in, which shows nothing and hides nothing.
 */
@Composable
internal fun rememberPageVisit(key: Any, show: ((String) -> Unit)?, hide: ((String) -> Unit)?): String =
    remember(key, show != null) { PageVisit(UUID.randomUUID().toString(), show, hide) }.id

private class PageVisit(val id: String, show: ((String) -> Unit)?, private val hide: ((String) -> Unit)?) : RememberObserver {
    init {
        show?.invoke(id)
    }

    private var hidden = false

    private fun leave() {
        if (hidden) return
        hidden = true
        hide?.invoke(id)
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = leave()
    override fun onAbandoned() = leave()
}
