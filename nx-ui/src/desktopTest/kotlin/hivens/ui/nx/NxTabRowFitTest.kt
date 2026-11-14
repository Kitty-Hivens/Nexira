package hivens.ui.nx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tabs wider together than the row keep every label whole and scroll instead. A
 * pack's settings on a phone held upright showed its last tab as "D..." and the
 * ones before it hardly better.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NxTabRowFitTest {

    private val labels = listOf("Основное", "Запуск", "Версия", "Содержимое", "Данные")

    @Test
    fun `labels too wide for the row keep their width and the row scrolls`() {
        val widths = labels.associateWith { naturalWidth(it) }
        rowOf(selected = 0) { scene ->
            labels.forEach { label ->
                val tab = assertNotNull(scene.tab(label), "no tab says $label")
                val natural = widths.getValue(label)
                assertTrue(tab.size.width >= natural, "$label was squeezed to ${tab.size.width} of its $natural")
            }
        }
    }

    @Test
    fun `the selected tab is scrolled into view`() {
        rowOf(selected = labels.lastIndex) { scene ->
            // Where it is drawn, not clipped to the row, which would always lie inside it.
            val last = assertNotNull(scene.tab(labels.last()))
            val left = last.positionInRoot.x
            val right = left + last.size.width
            assertTrue(left >= 0f && right <= ROW_WIDTH + 1f, "the selected tab sits at $left..$right, outside the row")
        }
    }

    // A tabs widget in a horizontal slot must not push what comes after it away.
    @Test
    fun `tabs that fit take only their own width`() {
        var neighbourAt = -1f
        val scene = ImageComposeScene(1200, 120, density = Density(1f)) {
            NxTheme(dark = true) {
                Row {
                    NxTabRow(tabs = labels.take(2), selected = 0, onSelect = {})
                    Box(Modifier.size(10.dp).onGloballyPositioned { neighbourAt = it.positionInRoot().x })
                }
            }
        }
        try {
            repeat(4) { scene.render() }
            val last = assertNotNull(scene.tab(labels[1]))
            val tabsEnd = last.positionInRoot.x + last.size.width
            assertTrue(neighbourAt in (tabsEnd - 1f)..(tabsEnd + 1f), "the neighbour sits at $neighbourAt, the tabs end at $tabsEnd")
        } finally {
            scene.close()
        }
    }

    /** Draws the five tabs in a row narrower than they are together and runs [check] on it. */
    private fun rowOf(selected: Int, check: (ImageComposeScene) -> Unit) {
        val scene = ImageComposeScene(400, 120, density = Density(1f)) {
            NxTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    NxTabRow(tabs = labels, selected = selected, onSelect = {}, modifier = Modifier.width(ROW_WIDTH.dp))
                }
            }
        }
        try {
            var time = 0L
            repeat(30) {
                Snapshot.sendApplyNotifications()
                time += 16_000_000L
                scene.render(time)
            }
            check(scene)
        } finally {
            scene.close()
        }
    }

    /** How wide [label] draws as a tab with nothing holding it in. */
    private fun naturalWidth(label: String): Int {
        var width = -1
        val scene = ImageComposeScene(1000, 120, density = Density(1f)) {
            NxTheme(dark = true) {
                NxSteadyText(
                    text = label,
                    weight = FontWeight.Normal,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.onGloballyPositioned { width = it.size.width },
                )
            }
        }
        scene.render()
        scene.close()
        assertTrue(width > 0, "nothing was measured")
        return width
    }

    private fun ImageComposeScene.tab(label: String): SemanticsNode? =
        semanticsOwners.asSequence()
            .flatMap { it.rootSemanticsNode.withDescendants() }
            .firstOrNull { node ->
                SemanticsActions.OnClick in node.config &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
            }

    private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }

    private companion object {
        /** Narrower than the five labels side by side, at density one. */
        const val ROW_WIDTH = 180f
    }
}
