package hivens.ui.feature.catalogue.browse

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.themes.Themes
import hivens.ui.TypingScene
import hivens.ui.onEventThread
import hivens.ui.theme.NxThemed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Browse's search box typed into while the window's browse keeps the words and answers
 * a few frames late, as the catalogue module does: every letter stays, and words the
 * browse changes itself replace what the box shows.
 */
class BrowseSearchTypingTest {

    /** The words as the window's browse keeps them, each change landing [LAG] frames after it was sent. */
    private class LaggingBrowse {
        var query by mutableStateOf("")
        val asked = mutableListOf<String>()
        private val inFlight = ArrayDeque<Pair<String, Int>>()

        fun send(words: String) {
            asked += words
            inFlight.addLast(words to LAG)
        }

        fun frame() {
            for (i in inFlight.indices) inFlight[i] = inFlight[i].first to inFlight[i].second - 1
            while (inFlight.firstOrNull()?.second?.let { it <= 0 } == true) query = inFlight.removeFirst().first
        }
    }

    @Composable
    private fun Search(browse: LaggingBrowse) {
        LaunchedEffect(browse) {
            while (true) {
                withFrameNanos { }
                browse.frame()
            }
        }
        val (text, type) = rememberEchoedText(browse.query, browse::send)
        SearchField(value = text, onValueChange = type, placeholder = "Search", modifier = Modifier.width(400.dp))
    }

    private fun search(body: TypingScene.(LaggingBrowse) -> Unit) = onEventThread {
        val browse = LaggingBrowse()
        TypingScene(420, 80) {
            NxThemed(Themes.default, dark = true) { Search(browse) }
        }.use { scene ->
            scene.settle()
            scene.focusField()
            scene.body(browse)
        }
    }

    @Test
    fun `letters typed faster than the browse answers all stay`() = search { browse ->
        "sodium".forEach {
            commit(it.toString())
            settle(1)
        }
        settle(20)
        assertEquals("sodium", fieldText())
        assertEquals("sodium", browse.query)
        assertEquals("sodium", browse.asked.last())
    }

    @Test
    fun `words the browse changes itself replace what the box shows`() = search { browse ->
        commit("iris")
        settle(20)
        assertEquals("iris", browse.query)
        // A tag search clears the words.
        browse.query = ""
        settle()
        assertEquals("", fieldText())
        commit("lithium")
        settle(20)
        assertEquals("lithium", browse.query)
        assertEquals("lithium", fieldText())
    }

    @Test
    fun `a word an input method holds open is searched once it is committed`() = search { browse ->
        compose("そ")
        settle(20)
        assertTrue(composing, "the open word stays open while the browse catches up")
        commit("ソディウム")
        settle(20)
        assertEquals("ソディウム", browse.query)
        assertEquals("ソディウム", fieldText())
    }

    private companion object {
        const val LAG = 3
    }
}
