package hivens.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The relative-placement library is on trial because of one open upstream report:
 * wrong positions once the density changes. Interface scale here is exactly that, a
 * density overridden for a subtree, so the library is held to it before it is used.
 */
class ConstraintLayoutDensityTest {

    private class Placed {
        var anchor: Rect = Rect.Zero
        var follower: Rect = Rect.Zero
    }

    /** A 50dp box 10dp in from the top start, and a 30dp box 5dp below and after it. */
    @androidx.compose.runtime.Composable
    private fun TwoLinkedBoxes(placed: Placed) {
        ConstraintLayout(Modifier.fillMaxSize()) {
            val (anchor, follower) = createRefs()
            Box(
                Modifier.size(50.dp)
                    .constrainAs(anchor) {
                        top.linkTo(parent.top, 10.dp)
                        start.linkTo(parent.start, 10.dp)
                    }
                    .onGloballyPositioned { placed.anchor = it.boundsInRoot() },
            )
            Box(
                Modifier.size(30.dp)
                    .constrainAs(follower) {
                        top.linkTo(anchor.bottom, 5.dp)
                        start.linkTo(anchor.end, 5.dp)
                    }
                    .onGloballyPositioned { placed.follower = it.boundsInRoot() },
            )
        }
    }

    private fun assertAt(scale: Float, placed: Placed) {
        fun near(actual: Float, expectedDp: Float, what: String) =
            assertTrue(abs(actual - expectedDp * scale) <= 1f, "$what at ${scale}x: $actual px, expected ${expectedDp * scale}")
        near(placed.anchor.left, 10f, "anchor left")
        near(placed.anchor.top, 10f, "anchor top")
        near(placed.anchor.width, 50f, "anchor width")
        near(placed.follower.left, 65f, "follower left")
        near(placed.follower.top, 65f, "follower top")
        near(placed.follower.width, 30f, "follower width")
    }

    @Test
    fun `placement follows a density overridden for the subtree`() {
        for (scale in listOf(1f, 1.5f, 2f)) {
            val placed = Placed()
            val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
                CompositionLocalProvider(LocalDensity provides Density(scale)) { TwoLinkedBoxes(placed) }
            }
            try {
                scene.render()
                assertAt(scale, placed)
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `a plain layout follows a density that changes while it is on screen`() {
        var density by mutableStateOf(Density(1f))
        var bounds = Rect.Zero
        val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
            CompositionLocalProvider(LocalDensity provides density) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.padding(start = 10.dp, top = 10.dp).size(50.dp)
                            .onGloballyPositioned { bounds = it.boundsInRoot() },
                    )
                }
            }
        }
        try {
            scene.render()
            density = Density(2f)
            Snapshot.sendApplyNotifications()
            scene.render()
            assertTrue(abs(bounds.left - 20f) <= 1f && abs(bounds.width - 100f) <= 1f, "plain layout at 2x: $bounds")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `placement follows a changing density once the layout is rebuilt for it`() {
        var density by mutableStateOf(Density(1f))
        val placed = Placed()
        val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
            CompositionLocalProvider(LocalDensity provides density) {
                // The library converts dp once, when the layout is created, so a density
                // that changes under it leaves every position where it was. Rebuilding
                // the layout on each density is the way around it.
                key(LocalDensity.current) { TwoLinkedBoxes(placed) }
            }
        }
        try {
            scene.render()
            assertAt(1f, placed)
            for (scale in listOf(2f, 1.5f, 1f)) {
                density = Density(scale)
                Snapshot.sendApplyNotifications()
                scene.render()
                assertAt(scale, placed)
            }
        } finally {
            scene.close()
        }
    }
}
