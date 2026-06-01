package hivens.launcher.smrt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientSyncCoordinatorTest {

    @Test
    fun `withClientLock serializes concurrent blocks on the same dir`() = runBlocking {
        val dir = Path.of("/tmp/aura-sync-coord-test/clients/Industrial")
        var active = 0
        var maxActive = 0

        val jobs = (1..12).map {
            launch(Dispatchers.Default) {
                ClientSyncCoordinator.withClientLock(dir) {
                    // Under the mutex only one block runs at a time, so these
                    // non-atomic reads/writes never observe overlap.
                    active++
                    maxActive = maxOf(maxActive, active)
                    delay(5)
                    active--
                }
            }
        }
        jobs.joinAll()

        assertEquals(1, maxActive, "same-dir sync must never run concurrently")
    }

    @Test
    fun `different dirs are allowed to run concurrently`() = runBlocking {
        val a = Path.of("/tmp/aura-sync-coord-test/clients/A")
        val b = Path.of("/tmp/aura-sync-coord-test/clients/B")
        var active = 0
        var maxActive = 0

        val jobs = listOf(a, b, a, b).map { dir ->
            launch(Dispatchers.Default) {
                ClientSyncCoordinator.withClientLock(dir) {
                    synchronized(this@ClientSyncCoordinatorTest) {
                        active++
                        maxActive = maxOf(maxActive, active)
                    }
                    delay(20)
                    synchronized(this@ClientSyncCoordinatorTest) { active-- }
                }
            }
        }
        jobs.joinAll()

        assertEquals(2, maxActive, "distinct dirs should not block each other")
    }
}
