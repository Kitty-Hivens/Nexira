package hivens.ui.easter
 
import kotlin.random.Random
 
/**
 * Wraps a raw download progress value and occasionally regresses it.
 *
 * Usage: replace every `progressUI` lambda value with
 *   AprilFoolsProgress.wrap(current, total)
 * before passing to [hivens.ui.components.LaunchControlPanel].
 *
 * On April Fools, ~15% of progress ticks will subtract a small random amount
 * instead of advancing -- the bar visually crawls backwards for a moment.
 * The underlying download is completely unaffected; this is display-only.
 */
object AprilFoolsProgress {
 
    // Internally tracked display value -- never exposed to actual download logic
    private var displayProgress = 0f
 
    fun reset() { displayProgress = 0f }
 
    /**
     * Returns a display progress value (0..1) that may occasionally regress.
     *
     * @param downloaded  Bytes actually downloaded so far (real value).
     * @param total       Total bytes expected (real value).
     */
    fun wrap(downloaded: Long, total: Long): Float {
        // Non-positive total is the "unknown size" signal the download
        // callback emits early; coerceIn does not rescue NaN since NaN
        // comparisons all return false, so naive `downloaded/total`
        // propagates into UI state and breaks the progress bar render.
        if (total <= 0L) return 0f
        if (!AprilFools.isActive()) {
            return (downloaded.toFloat() / total).coerceIn(0f, 1f)
        }
 
        val real = downloaded.toFloat() / total
 
        // 15% chance to regress display by a small random step
        displayProgress = if (Random.nextFloat() < 0.15f) {
            // Go backwards by 2–8%
            (displayProgress - Random.nextFloat() * 0.06f - 0.02f).coerceAtLeast(0f)
        } else {
            // Normal advance -- lerp toward real value so it eventually catches up
            val lerped = displayProgress + (real - displayProgress) * 0.25f
            lerped.coerceIn(0f, 1f)
        }
 
        return displayProgress
    }
}
