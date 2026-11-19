package hivens.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.look.shape.api.Shape
import hivens.look.shape.api.ShapeAnswers
import hivens.ui.surface.PolygonShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The bridge fills Material's corners from the shape axis's roles, so a raw Material component and a
 * call site still reading `MaterialTheme.shapes` round as nx-ui rounded them before the axis: 4, 8, 12,
 * 16 and 24 under nx. A role answered in another family than corners leaves its slot at the base
 * answer, and the answers are made once.
 */
class NxShapeTest {

    @Test
    fun `under nx, Material's corners are the ones nx-ui set before the shape axis`() {
        assertEquals(
            Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
            materialShapes(nxShapeAnswers()),
        )
    }

    @Test
    fun `each slot is the role it stands for, in any family of corners`() {
        val answers = ShapeAnswers.build {
            tooltip = CutCornerShape(2.dp)
            control = CutCornerShape(3.dp)
            card = RoundedCornerShape(5.dp)
            sheet = RoundedCornerShape(7.dp)
            dialog = CutCornerShape(9.dp)
        }
        val shapes = materialShapes(answers)
        assertSame(answers.tooltip, shapes.extraSmall)
        assertSame(answers.control, shapes.small)
        assertSame(answers.card, shapes.medium)
        assertSame(answers.sheet, shapes.large)
        assertSame(answers.dialog, shapes.extraLarge)
    }

    @Test
    fun `a role that is not a corner shape leaves its slot at the base answer`() {
        val answers = ShapeAnswers.build {
            card = PolygonShape(points = 6, innerRadius = 1f, rounding = 0f, smoothing = 0f)
            dialog = RectangleShape
        }
        val shapes = materialShapes(answers)
        assertSame(ShapeAnswers.BASE.card, shapes.medium)
        assertSame(ShapeAnswers.BASE.dialog, shapes.extraLarge)
        assertSame(ShapeAnswers.BASE.control, shapes.small)
    }

    @Test
    fun `the answers are made once, so a role is the same value wherever it is read`() {
        assertSame(nxShapeAnswers(), nxShapeAnswers())
        assertEquals(RoundedCornerShape(0.dp), ShapeAnswers.BASE.card, "the base is not nx")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a component drawn in an nx theme reads nx's corners, and the bridge follows the look around it`() {
        val seen = LinkedHashMap<String, Any?>()
        val own = ShapeAnswers.build { card = RoundedCornerShape(3.dp) }
        val scene = ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
            NxThemed(NeutralTheme) {
                seen["card"] = Shape.card
                seen["plate"] = plateOutline
                seen["medium"] = MaterialTheme.shapes.medium
            }
            CompositionLocalProvider(Shape.local provides own) {
                NxTheme {
                    seen["bridged"] = MaterialTheme.shapes.medium
                }
            }
            seen["outside"] = Shape.card
        }
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
        val nx = nxShapeAnswers()
        assertSame(nx.card, seen["card"])
        assertSame(nx.plate, seen["plate"])
        assertSame(nx.card, seen["medium"])
        assertSame(own.card, seen["bridged"])
        assertSame(ShapeAnswers.BASE.card, seen["outside"], "outside any look the base answers")
    }
}
