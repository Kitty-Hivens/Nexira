package hivens.launcher.update

import hivens.core.data.PackInstance
import java.io.IOException

/**
 * The instance a change was asked for is no longer installed.
 *
 * An update or a rollback waits for the instance's mutation lock, and a delete can
 * hold it first. Carried on with the record read before the wait, the change wrote
 * the whole build back into the directory the delete had just removed, with no
 * record left to show it in the Library.
 */
class InstanceRemovedException(instance: PackInstance) :
    IOException("${instance.displayName} is no longer installed")

/**
 * The instance a change was asked for moved between Local and a source while the
 * change waited for its lock: a detach landed, or a reattach did. Carried on with
 * the record read before, an update wrote its source's build and server binding
 * onto a pack that had just become its player's own. Nothing was written.
 */
class InstanceChangedException(instance: PackInstance) :
    IllegalStateException("${instance.displayName} was detached or reattached while the change waited; nothing was written")

/** Throws [InstanceChangedException] when [fresh] no longer comes from where [asked] did. */
internal fun requireSameProvenance(asked: PackInstance, fresh: PackInstance) {
    if (fresh.packRef.origin != asked.packRef.origin || fresh.forkedFrom != asked.forkedFrom) throw InstanceChangedException(asked)
}
