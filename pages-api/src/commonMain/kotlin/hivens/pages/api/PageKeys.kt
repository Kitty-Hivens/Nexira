package hivens.pages.api

import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/**
 * What opens a page, and what a composition shows of its pages, at the composition's level, so any widget of the
 * composition reads it: one in the frame around the pages host as well as one on a page.
 */
object PagesKeys {
    /**
     * Shows a screen, with the values to open its page with. One the composition has no screen for shows nothing new,
     * and what is shown stays.
     */
    val Show = CommandKey<Show>("nav.show", ScopeLevel.Composition)

    /** The page shown now, null before anything is. Answered by whatever keeps what is shown, the history module. */
    val Shown = SourceKey<HistoryEntry?>("pages.shown", ScopeLevel.Composition)

    /**
     * The pages something can still bring back, by [HistoryEntry.page]: each keeps what it made and saved while it is
     * among them, and lets go of it once it is not. Answered with what is shown, by the history module.
     */
    val Kept = SourceKey<Set<String>>("pages.kept", ScopeLevel.Composition)

    /** The screens the composition can show: those modules bring and those a person made, in that order. */
    val Screens = SourceKey<List<ScreenView>>("pages.screens", ScopeLevel.Composition)

    /** The screen the composition starts on, null where it has none. */
    val Start = SourceKey<ScreenId?>("pages.start", ScopeLevel.Composition)
}

/** What every page answers for the widgets drawn in it. */
object PageKeys {
    /**
     * What the page is drawn for. Provided by whoever draws the page, to the page's level rather than over it, so the
     * factories of that level read it too.
     */
    val Entry = SourceKey<HistoryEntry>("page.entry", ScopeLevel.Page)
}
