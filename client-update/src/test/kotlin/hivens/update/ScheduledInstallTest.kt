package hivens.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledInstallTest {

    @Test
    fun `scheduling again replaces the hook instead of adding a second one`() {
        // Two armed hooks run at the same time at exit, each swapping one install.
        val armed = mutableListOf<Thread>()
        val install = ScheduledInstall(add = { armed += it }, remove = { armed.remove(it) })

        install.replace("launcher-update-install") {}
        install.replace("launcher-update-install") {}
        install.replace("launcher-update-install") {}

        assertEquals(1, armed.size)
    }

    @Test
    fun `a hook that can no longer be removed does not stop the new one being armed`() {
        // removeShutdownHook throws once shutdown has begun.
        val armed = mutableListOf<Thread>()
        val install = ScheduledInstall(add = { armed += it }, remove = { error("shutdown in progress") })

        install.replace("launcher-update-install") {}
        install.replace("launcher-update-install") {}

        assertEquals(2, armed.size)
    }
}
