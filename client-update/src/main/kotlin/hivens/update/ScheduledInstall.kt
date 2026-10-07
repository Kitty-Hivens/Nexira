package hivens.update

/**
 * The one install a launcher has scheduled for its exit.
 *
 * Scheduling again replaces the hook instead of adding another. Shutdown hooks run
 * concurrently, so two of them would each swap the same install at once, and a
 * registered hook cannot be taken back by anything that does not hold it.
 */
internal class ScheduledInstall(
    private val add: (Thread) -> Unit = Runtime.getRuntime()::addShutdownHook,
    private val remove: (Thread) -> Boolean = Runtime.getRuntime()::removeShutdownHook,
) {
    private var hook: Thread? = null

    @Synchronized
    fun replace(name: String, action: () -> Unit) {
        // Throws once shutdown has begun, and then the hook already armed is the
        // one that runs either way.
        hook?.let { runCatching { remove(it) } }
        val next = Thread(action, name)
        add(next)
        hook = next
    }
}
