package hivens.ui.scene3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Scene3DStateTest {

    @Test fun `update runs the block, returns its result and bumps the revision`() {
        val state = Scene3DState()
        val before = state.revision
        val result = state.update {
            root.attach(Node())
            "done"
        }
        assertEquals("done", result)
        assertEquals(before + 1, state.revision)
        assertEquals(1, state.root.children.size)
    }

    @Test fun `camera pitch clamps to the flip limit`() {
        val state = Scene3DState()
        state.cameraPitch = 9f
        assertTrue(state.cameraPitch <= 1.2f, "pitch=${state.cameraPitch}")
        state.cameraPitch = -9f
        assertTrue(state.cameraPitch >= -1.2f, "pitch=${state.cameraPitch}")
    }
}
