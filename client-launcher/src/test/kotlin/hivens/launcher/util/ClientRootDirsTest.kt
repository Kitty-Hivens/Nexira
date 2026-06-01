package hivens.launcher.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * isKnown decides whether a manifest entry's first path segment is a real root
 * dir (kept) or a server-name prefix (stripped). FileDownloadService and
 * ClasspathProvider both rely on the same answer, so the prefix-match contract
 * is pinned here.
 */
class ClientRootDirsTest {

    @Test
    fun `every declared root is recognised`() {
        for (root in ClientRootDirs.ALL) {
            assertTrue(ClientRootDirs.isKnown(root), "should recognise root: $root")
        }
    }

    @Test
    fun `versioned root variants match by prefix`() {
        for (seg in listOf("libraries-1.12.2", "natives-linux", "assets-1.20", "modsNew")) {
            assertTrue(ClientRootDirs.isKnown(seg), "prefix-match should keep: $seg")
        }
    }

    @Test
    fun `server-name first segments are not known roots`() {
        for (name in listOf("Industrial", "RPG", "SkyBlock", "Nexira")) {
            assertFalse(ClientRootDirs.isKnown(name), "server name must not look like a root: $name")
        }
    }

    @Test
    fun `the empty segment is not a known root`() {
        assertFalse(ClientRootDirs.isKnown(""))
    }

    @Test
    fun `matching is case-sensitive`() {
        assertFalse(ClientRootDirs.isKnown("Mods"))
        assertFalse(ClientRootDirs.isKnown("CONFIG"))
    }

    @Test
    fun `the known set is exactly the documented ten roots`() {
        assertTrue(
            ClientRootDirs.ALL == setOf(
                "mods", "config", "bin", "assets", "libraries", "resources",
                "saves", "resourcepacks", "shaderpacks", "natives",
            ),
        )
    }
}
