package hivens.widget.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The words the launcher looks up, by whose they are, as they change: the host's own under the
 * namespace it reads them by, then those of every module compiled into the build, then those of every
 * jar loaded, in the order they load. A jar switched off, loaded again or taken away changes it.
 *
 * The host keeps one and hands it to the modules it trusts as one of its own services: the language
 * module answers its lookups from what is in it. Nothing registers into it: an owner is in it by
 * shipping words, and out of it by not being loaded.
 */
@HostApi
interface TakenWords {
    val all: StateFlow<Map<String, ModuleStrings>>
}
