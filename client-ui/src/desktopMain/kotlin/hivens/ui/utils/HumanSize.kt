package hivens.ui.utils

import hivens.ui.i18n.AppStrings

/**
 * A byte count as a person reads it: binary units, one decimal, the unit that
 * keeps the number under four digits. Bytes below a kilobyte stay bytes -- "0.0
 * KB" for a 300-byte file says less than "300 B".
 *
 * Both the unit and the decimal mark come from [strings], i.e. the language the
 * interface is in -- not from the machine's locale, which is a different setting
 * and renders "1.5 GB" in the middle of a Russian sentence (and "1,5 GB" in the
 * middle of an English one) whenever the two disagree.
 */
internal fun humanSize(bytes: Long, strings: AppStrings): String {
    val units = strings.byteUnits
    if (bytes < 1024) return "$bytes ${units.first()}"
    var value = bytes.toDouble() / 1024.0
    var unit = 1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(strings.locale, "%.1f %s", value, units[unit])
}
