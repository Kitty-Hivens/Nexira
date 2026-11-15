package hivens.ui.diag

import hivens.i18n.api.Strings
import hivens.launcher.diag.ReportPrompts

/**
 * The report prompts in the language the user picked.
 *
 * `client-launcher` composes the issue body and cannot see [Strings], since it is
 * below the UI in the module order, and deliberately so. This is the seam: the
 * text comes down from here, the assembly stays there.
 *
 * Only the bundle path uses it. A crash report keeps the English defaults,
 * because the crash dialog offering it is itself English and a crash can happen
 * before a locale has been resolved at all.
 */
fun Strings.reportPrompts(): ReportPrompts = ReportPrompts(
    describeHeading = text("reportDescribeHeading"),
    crashHint       = text("reportCrashHint"),
    bundleHint      = text("reportBundleHint"),
    languageNudge   = text("reportLanguageNudge"),
    bundleCreated   = text("reportBundleCreated"),
    bundleAttach    = text("reportBundleAttach"),
)
