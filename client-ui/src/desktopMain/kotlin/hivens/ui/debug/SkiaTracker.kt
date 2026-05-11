package hivens.ui.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue

// ─── Global tracker — register Skia objects from anywhere ────────────────────

object SkiaTracker {
    private val refs = ConcurrentLinkedQueue<Pair<String, WeakReference<Any>>>()

    fun track(label: String, obj: Any) {
        refs.add(label to WeakReference(obj))
    }

    fun snapshot(): Map<String, Int> {
        // Prune dead refs
        refs.removeIf { it.second.get() == null }
        return refs.groupingBy { it.first }.eachCount()
    }

    fun total() = refs.count { it.second.get() != null }
}

// ─── Overlay composable ───────────────────────────────────────────────────────

/**
 * Debug overlay — shows:
 *  - JVM heap used / max
 *  - Process RSS from /proc/self/status (actual native + heap)
 *  - Tracked Skia objects via [SkiaTracker]
 *  - Force GC button
 *
 * Toggle visibility with [visible]. Keep out of release builds.
 */
@Composable
fun SkiaDebugOverlay(
    visible: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    var heapUsed   by remember { mutableStateOf(0L) }
    var heapMax    by remember { mutableStateOf(0L) }
    var rss        by remember { mutableStateOf(0L) }
    var skiaTotal  by remember { mutableStateOf(0) }
    var skiaByType by remember { mutableStateOf(emptyMap<String, Int>()) }
    var expanded   by remember { mutableStateOf(true) }
    var gcCount    by remember { mutableStateOf(0) }

    LaunchedEffect(gcCount) {
        while (true) {
            val rt = Runtime.getRuntime()
            heapUsed  = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            heapMax   = rt.maxMemory() / (1024 * 1024)
            rss       = readRssMb()
            skiaByType = SkiaTracker.snapshot()
            skiaTotal  = SkiaTracker.total()
            delay(2000)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xDD000000))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val rssColor = when {
                    rss > 1500 -> Color(0xFFEF5350)
                    rss > 800  -> Color(0xFFFFD54F)
                    else       -> Color(0xFF4CAF50)
                }
                Text(
                    "⬤ MEM  RSS: ${rss} MB   Heap: ${heapUsed}/${heapMax} MB   ${if (expanded) "▲" else "▼"}",
                    color      = rssColor,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // ── Skia tracked objects ──────────────────────────────────
                    if (skiaByType.isEmpty()) {
                        Text(
                            "No tracked objects  (add SkiaTracker.track() calls)",
                            color      = Color.Gray,
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            "Tracked Skia objects: $skiaTotal",
                            color      = Color.White,
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        skiaByType.forEach { (label, count) ->
                            val color = when {
                                count > 50 -> Color(0xFFEF5350)
                                count > 20 -> Color(0xFFFFD54F)
                                else       -> Color(0xFF4CAF50)
                            }
                            Text(
                                "  %-20s %d".format(label, count),
                                color      = color,
                                fontSize   = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // ── RSS trend hint ────────────────────────────────────────
                    val nativeEst = rss - heapUsed
                    if (nativeEst > 0) {
                        Text(
                            "Native est. ${nativeEst} MB  (RSS − heap)",
                            color      = if (nativeEst > 500) Color(0xFFFFD54F) else Color.Gray,
                            fontSize   = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // ── Buttons ───────────────────────────────────────────────
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // System.runFinalization() removed — deprecated in Java 18 and
                        // a no-op since Java 9 (finalizers aren't guaranteed to run
                        // synchronously regardless). Two-shot GC is the modern recipe.
                        DebugButton("Force GC") {
                            System.gc()
                            System.gc()
                            gcCount++
                        }
                        DebugButton("GC + wait") {
                            System.gc()
                            Thread.sleep(500)
                            System.gc()
                            gcCount++
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

// ─── /proc/self/status RSS reader ────────────────────────────────────────────

private fun readRssMb(): Long = try {
    File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmRSS:") }
        ?.replace(Regex("[^0-9]"), "")
        ?.toLongOrNull()
        ?.div(1024) // kB → MB
        ?: 0L
} catch (_: Exception) { 0L }
