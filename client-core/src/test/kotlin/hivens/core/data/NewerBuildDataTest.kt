package hivens.core.data

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewerBuildDataTest {

    @BeforeTest fun clean() = NewerBuildData.reset()
    @AfterTest fun tidy() = NewerBuildData.reset()

    @Test
    fun `a session with nothing to report stays quiet`() {
        assertTrue(NewerBuildData.affected().isEmpty())
    }

    @Test
    fun `each store is reported once, in the order it was found`() {
        NewerBuildData.record(ReadOnlyStore.Layout)
        NewerBuildData.record(ReadOnlyStore.PackLibrary)
        // Both stores re-read on a reload; the notice must not grow a duplicate.
        NewerBuildData.record(ReadOnlyStore.Layout)
        assertEquals(
            listOf(ReadOnlyStore.Layout, ReadOnlyStore.PackLibrary),
            NewerBuildData.affected().toList(),
        )
    }

    @Test
    fun `the reported set is a copy, so a later find cannot mutate it behind a reader`() {
        NewerBuildData.record(ReadOnlyStore.Layout)
        val seen = NewerBuildData.affected()
        NewerBuildData.record(ReadOnlyStore.PackLibrary)
        assertEquals(setOf(ReadOnlyStore.Layout), seen)
    }
}
