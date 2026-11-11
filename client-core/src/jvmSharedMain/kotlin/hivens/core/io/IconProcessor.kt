package hivens.core.io

/**
 * Normalizes an archive-extracted icon before it is held in memory or cached.
 * A mod's declared logo can be a multi-megabyte PNG while every consumer renders
 * it at list-row size, so the processor bounds both the scan cache entry and the
 * resident content list. Returns the bytes to keep (possibly the input itself)
 * or null to drop the icon entirely.
 *
 * Lives in core as a seam: the engine module stays free of java.desktop, the
 * AWT-backed implementation is bound by the UI module, and a headless consumer
 * simply runs without one (the scan cache still enforces its own size floor).
 */
fun interface IconProcessor {
    fun process(bytes: ByteArray): ByteArray?
}
