package hivens.core.update

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionChannelTest {

    @Test
    fun `known wire values resolve regardless of case`() {
        assertEquals(VersionChannel.Release, VersionChannel.of("release", "1.0.0"))
        assertEquals(VersionChannel.Beta, VersionChannel.of("Beta", "1.0.0"))
        assertEquals(VersionChannel.Alpha, VersionChannel.of("alpha", "1.0.0"))
    }

    @Test
    fun `absent wire value derives from the version string`() {
        assertEquals(VersionChannel.Beta, VersionChannel.of(null, "SNAPSHOT-0.0.0-2026.07.18.7"))
        assertEquals(VersionChannel.Release, VersionChannel.of(null, "0.1.2"))
    }

    @Test
    fun `unknown future wire value falls back to the derive, not a crash`() {
        assertEquals(VersionChannel.Beta, VersionChannel.of("nightly", "SNAPSHOT-1"))
        assertEquals(VersionChannel.Release, VersionChannel.of("nightly", "2.0.0"))
    }
}
