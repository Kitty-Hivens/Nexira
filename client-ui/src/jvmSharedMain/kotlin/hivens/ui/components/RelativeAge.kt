package hivens.ui.components

import hivens.ui.i18n.AppStrings
import java.time.Duration
import java.time.Instant

/**
 * How long ago an instant was, in the coarsest unit that still says something.
 *
 * A build list wants this and not a date: the question a reader runs down the
 * column with is "how old is this", and a full timestamp makes them do the
 * arithmetic in their head on every row. The exact moment belongs in the tooltip,
 * where it is one hover away for the times they actually want it.
 *
 * Months and years are approximated at 30 and 365 days, which is right to within a
 * day or two at a scale where the answer is already rounded to a whole unit.
 * Anything unparseable comes back as the raw string rather than as nothing: a
 * stamp this build does not understand is still the only thing anyone knows.
 */
fun relativeAge(iso: String?, s: AppStrings, now: Instant = Instant.now()): String {
    val raw = iso?.takeIf { it.isNotBlank() } ?: return ""
    val at = runCatching { Instant.parse(raw) }.getOrNull() ?: return raw
    val seconds = Duration.between(at, now).seconds
    if (seconds < 0) return s.ageJustNow
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> s.ageJustNow
        minutes < 60 -> s.ageMinutes(minutes)
        hours < 24 -> s.ageHours(hours)
        days < 30 -> s.ageDays(days)
        days < 365 -> s.ageMonths(days / 30)
        else -> s.ageYears(days / 365)
    }
}
