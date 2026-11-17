package hivens.look.manager.api

/**
 * The few meanings a look carries by convention, the ones a reader knows without a label: something
 * failed or would destroy, something needs care, something went well, something is said for its own
 * sake. Closed on purpose, since a new one is a convention every look would have to learn.
 *
 * A shared word and no axis of its own: the axes that answer by it (colour, and later sound, haptics
 * and how long a notice stays) all compile against this API already, so none of them has to depend on
 * another to name it. The manager itself never reads it.
 */
enum class Status { Error, Warning, Success, Info }
