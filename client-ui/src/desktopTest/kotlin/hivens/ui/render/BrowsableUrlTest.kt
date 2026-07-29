package hivens.ui.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Links reaching the platform opener come out of pack descriptions and mod
 * metadata, so the label a user clicks and the target it carries are written by
 * someone else.
 */
class BrowsableUrlTest {

    @Test
    fun `ordinary web links open`() {
        assertTrue(isBrowsableUrl("https://modrinth.com/mod/sodium"))
        assertTrue(isBrowsableUrl("http://example.com/page?q=1#frag"))
        // Scheme comparison is case-insensitive per RFC 3986.
        assertTrue(isBrowsableUrl("HTTPS://example.com"))
    }

    @Test
    fun `a local file is not opened`() {
        assertFalse(isBrowsableUrl("file:///home/user/.ssh/id_rsa"))
        assertFalse(isBrowsableUrl("file:///etc/passwd"))
    }

    @Test
    fun `schemes the platform would hand to some other application are refused`() {
        // Each of these has a registered handler on a normal desktop, and none
        // of them is what a link in a mod description is for.
        assertFalse(isBrowsableUrl("smb://server/share"))
        assertFalse(isBrowsableUrl("javascript:alert(1)"))
        assertFalse(isBrowsableUrl("data:text/html,<script>1</script>"))
        assertFalse(isBrowsableUrl("mailto:someone@example.com"))
        assertFalse(isBrowsableUrl("ssh://host"))
    }

    @Test
    fun `text that is not a URL at all is refused`() {
        assertFalse(isBrowsableUrl(""))
        assertFalse(isBrowsableUrl("   "))
        assertFalse(isBrowsableUrl("modrinth.com/mod/sodium"), "no scheme means nothing decides the handler")
        assertFalse(isBrowsableUrl("not a url"))
    }
}
