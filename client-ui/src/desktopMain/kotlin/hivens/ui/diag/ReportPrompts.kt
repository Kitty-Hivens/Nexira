package hivens.ui.diag

import hivens.launcher.diag.ReportPrompts
import hivens.ui.i18n.AppStrings

/**
 * The report prompts in the language the user picked.
 *
 * `client-launcher` composes the issue body and cannot see [AppStrings] -- it is
 * below the UI in the module order, and deliberately so. This is the seam: the
 * text comes down from here, the assembly stays there.
 *
 * Only the bundle path uses it. A crash report keeps the English defaults,
 * because the crash dialog offering it is itself English and a crash can happen
 * before a locale has been resolved at all.
 */
fun AppStrings.reportPrompts(): ReportPrompts = ReportPrompts(
    describeHeading = reportDescribeHeading,
    crashHint       = reportCrashHint,
    bundleHint      = reportBundleHint,
    languageNudge   = reportLanguageNudge,
    bundleCreated   = reportBundleCreated,
    bundleAttach    = reportBundleAttach,
)
