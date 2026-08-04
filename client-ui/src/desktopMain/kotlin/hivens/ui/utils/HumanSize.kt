package hivens.ui.utils

/**
 * A byte count as a person reads it: binary units, one decimal, the unit that
 * keeps the number under four digits. Bytes below a kilobyte stay bytes -- "0.0
 * KB" for a 300-byte file says less than "300 B".
 */
internal fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}
