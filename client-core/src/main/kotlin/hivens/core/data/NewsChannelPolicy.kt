package hivens.core.data

/**
 * In what order a surface presents a channel's entries.
 */
enum class NewsOrder {
    /** Newest first, the way the source published them. */
    Feed,

    /**
     * A handful drawn at random out of everything loaded, re-drawn each time the
     * surface opens.
     *
     * For a channel whose entries are not news in the sense of "the latest": a
     * fixed newest-first order over an unchanging set means the same few lines sit
     * at the top of the rail for weeks and everything below them is never read.
     * The draw is over the whole loaded set, not a shuffle of the visible window,
     * so what appears changes as well as the order.
     */
    Sample,
}

/**
 * What a surface may do with a channel's entries.
 *
 * A channel is not only a place to fetch from: it also decides what the reader is
 * allowed to do with what arrives. The launcher's own upstream is a site the
 * launcher is a client of, so its entries open there. A feed the user pointed at
 * something else is not vetted by anyone, and the reasonable default for one of
 * those is that it stays text on a rail: nothing to click through into, and no
 * remote asset fetched to render it.
 *
 * Carried by the feed rather than set at the call site so a surface cannot be
 * configured into opening a channel that should not be opened.
 */
data class NewsChannelPolicy(
    /**
     * Whether a row may be opened at its source. False makes the rows inert text
     * and removes the affordance that says otherwise.
     */
    val opensSource: Boolean = true,
    val order: NewsOrder = NewsOrder.Feed,
)
