package hivens.core.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule that decides whether an empty roster means "no servers" or "the
 * upstream is down". It lived inside a hundred-line startup effect in the
 * shell, where it could not be reached, and it is the kind of rule that is
 * argued about rather than observed -- so it is worth having the argument
 * written down as assertions.
 */
class RosterOutageTest {

    @Test
    fun `a real answer wins`() {
        assertEquals(listOf("a", "b"), rosterAfterFetch(fetched = listOf("a", "b"), cached = listOf("old")))
    }

    @Test
    fun `an empty answer over an empty cache is the truth`() {
        assertEquals(emptyList(), rosterAfterFetch(fetched = emptyList<String>(), cached = emptyList()))
    }

    @Test
    fun `an empty answer over a real cache is treated as an outage`() {
        assertEquals(
            listOf("kept"),
            rosterAfterFetch(fetched = emptyList(), cached = listOf("kept")),
            "wiping a live roster during a transient outage costs more than keeping a stale one",
        )
    }

    @Test
    fun `a shrinking roster is still an answer`() {
        // Only *empty* is ambiguous. One server left is a statement, not a symptom.
        assertEquals(listOf("a"), rosterAfterFetch(fetched = listOf("a"), cached = listOf("a", "b", "c")))
    }
}
