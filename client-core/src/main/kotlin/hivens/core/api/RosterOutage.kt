package hivens.core.api

/**
 * Which roster to believe when a fetch comes back empty.
 *
 * The upstream server list swallows network failures and answers with an empty
 * roster rather than throwing, so an outage and a genuinely empty account look
 * identical at the call site. The disk cache is the only thing that tells them
 * apart: a roster that was non-empty last time and is empty now is far more
 * likely a dead upstream than a user whose servers all vanished at once.
 *
 * The bias is deliberate and asymmetric. Keeping a stale roster through a real
 * emptying costs the user a list that corrects itself on the next successful
 * fetch; wiping a real roster during a five-minute outage costs them their
 * servers on the screen they opened the launcher to use.
 *
 * Generic over the entry type so the rule can be tested without constructing a
 * server profile, and so it reads as what it is: a decision about lists, not
 * about servers.
 */
fun <T> rosterAfterFetch(fetched: List<T>, cached: List<T>): List<T> = when {
    fetched.isNotEmpty() -> fetched
    // Nothing cached either: an empty answer is simply the truth.
    cached.isEmpty() -> emptyList()
    // Probable outage -- keep what we had.
    else -> cached
}
