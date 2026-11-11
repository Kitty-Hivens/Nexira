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
