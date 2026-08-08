package hivens.launcher.diag

/**
 * The parts of a report body its author reads while filling it in.
 *
 * Everything else the body carries -- section headings, the collected fields,
 * the stack trace -- stays English on purpose: it is read by whoever picks the
 * issue up, and a tracker where the same section is called three things is a
 * tracker nobody can search. These are the opposite case. They are addressed to
 * one person, in the moment they are describing what happened, so they are in
 * the language that person chose.
 *
 * Carries text only. The markdown around it lives in [IssueReporter], so a
 * translation cannot break the document it lands in.
 *
 * Defaults are English and complete, which keeps `client-launcher` able to
 * compose a report on its own -- the crash path must not depend on the UI module
 * having resolved a locale, since a crash early enough can precede it.
 */
data class ReportPrompts(
    /** Heading of the section the author writes in. */
    val describeHeading: String = "Description",
    /** Asked after a crash. */
    val crashHint: String = "What were you doing when the launcher crashed?",
    /** Asked when reporting with a diagnostic bundle. */
    val bundleHint: String = "Describe the problem.",
    /**
     * Nudge toward the tracker's working language. The author is free to ignore
     * it -- a report in the wrong language beats a report not filed.
     */
    val languageNudge: String = "Please write in English if you can.",
    /** Where the bundle went. `$bundle` is replaced with its file name. */
    val bundleCreated: String =
        $$"The diagnostic bundle `$bundle` was created in the launcher's data directory, and its full path is on your clipboard.",
    /** The one step the launcher cannot do for the author. */
    val bundleAttach: String = "**Drag the ZIP into this window before submitting** (GitHub accepts drag-and-drop).",
) {
    /** Fills the bundle file name into [bundleCreated]. */
    fun bundleCreatedFor(bundleName: String): String = bundleCreated.replace($$"$bundle", bundleName)

    companion object {
        val English = ReportPrompts()
    }
}
