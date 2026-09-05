package hivens.profiler.agent;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import java.lang.instrument.Instrumentation;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * Nexira heap-profiler agent. Loads into the game JVM and only OBSERVES: it
 * sums post-GC heap usage (the live set) on whole-heap collections and
 * accumulates GC pause time, then writes a small JSON summary. No bytecode
 * transform, no contact with mods -- the server's integrity checks see an
 * ordinary client.
 * <p>
 * Deliberately plain Java targeting Java 8 bytecode: the same jar attaches to
 * legacy 1.7.10 / 1.12.2 packs (Java 8) and modern packs (Java 17+). It writes
 * nothing to stdout/stderr (the launcher scrapes the game console) and swallows
 * every error -- a profiler must never take the game down.
 * <p>
 * The summary is written periodically (every {@link #FLUSH_INTERVAL_SECONDS} s)
 * as well as on shutdown, so a hard exit that skips the shutdown hook (Forge
 * {@code Runtime.halt}, a native crash) still leaves a recent file behind.
 */
public final class ProfilerAgent {

    private ProfilerAgent() {}

    private static final long START_MS = System.currentTimeMillis();
    private static final AtomicLong liveSetMaxBytes = new AtomicLong(0L);
    private static final AtomicLong peakHeapBytes = new AtomicLong(0L);
    private static final AtomicLong gcCount = new AtomicLong(0L);
    private static final AtomicLong gcPauseTotalMs = new AtomicLong(0L);
    private static final AtomicBoolean liveSetReliable = new AtomicBoolean(false);
    private static final Set<String> heapPools = new HashSet<>();

    /** Serializes the periodic flush against the final shutdown write so the two
     *  threads never race on the shared {@code <out>.tmp} staging file. */
    private static final Object WRITE_LOCK = new Object();

    /** Snapshot cadence. A halted JVM still leaves an at-most-this-old file even
     *  when the shutdown hook never runs. */
    private static final long FLUSH_INTERVAL_SECONDS = 30L;

    /** JVM agent entry point (Premain-Class in the jar manifest). */
    // The flush executor is a session-lifetime daemon shut down via the shutdown hook
    // below, not a scoped resource -- try-with-resources would close it immediately,
    // and ScheduledExecutorService has no close() on this agent's Java 8 target.
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            String out = System.getProperty("nexira.profiler.out");
            if (out == null || out.trim().isEmpty()) {
                return; // no sink -> stay completely inert, never touch the launch
            }
            final Path outPath = Paths.get(out);

            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    heapPools.add(pool.getName());
                }
            }
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc instanceof NotificationEmitter) {
                    ((NotificationEmitter) gc).addNotificationListener((n, handback) -> {
                        if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) {
                            try {
                                onGc(GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData()));
                            } catch (Throwable ignored) {
                                // a malformed notification must never propagate into the game
                            }
                        }
                    }, null, null);
                }
            }

            ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nexira-profiler-flush");
                t.setDaemon(true); // a profiler thread must never hold the game JVM open
                return t;
            });
            flusher.scheduleWithFixedDelay(
                () -> writeSnapshot(outPath), FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                flusher.shutdownNow();
                writeSnapshot(outPath); // final, up-to-the-moment snapshot on a clean exit
            }, "nexira-profiler-writer"));
        } catch (Throwable ignored) {
            // An agent must never take the game down. Stay silent.
        }
    }

    private static void onGc(GarbageCollectionNotificationInfo info) {
        GcInfo gc = info.getGcInfo();
        if (gc == null) return;

        gcCount.incrementAndGet();
        gcPauseTotalMs.addAndGet(gc.getDuration());

        Map<String, MemoryUsage> before = gc.getMemoryUsageBeforeGc();
        if (before != null) {
            updateMax(peakHeapBytes, sumHeapUsed(before));
        }

        // Live set = heap retained after a WHOLE-HEAP (old-gen-inclusive) collection.
        // Minor GCs leave old-gen garbage uncollected, so their after-usage over-counts.
        // A collector that never reports a whole-heap collection simply leaves the live
        // set unreliable, and the launcher then leans on the peak term instead (safe).
        if (isWholeHeapCollection(info.getGcAction(), info.getGcName())) {
            Map<String, MemoryUsage> after = gc.getMemoryUsageAfterGc();
            if (after != null) {
                long live = sumHeapUsed(after);
                if (live > 0L) {
                    liveSetReliable.set(true);
                    updateMax(liveSetMaxBytes, live);
                }
            }
        }
    }

    /**
     * True when a GC notification ends a whole-heap (old-gen-inclusive) collection,
     * where post-GC heap usage approximates the live set. The generational
     * collectors say so in the action ("end of major GC"); the concurrent ones whose
     * action never carries "major" are matched by name instead -- a ZGC *major* cycle
     * or a single-generation Shenandoah cycle ends with "end of GC cycle", while a ZGC
     * *minor* cycle (young only) carries "Minor" in its name and is excluded, as are
     * the STW pause sub-events ("end of GC pause"). Verified against the
     * G1 / Parallel / ZGC / Shenandoah notifications on JDK 25.
     */
    static boolean isWholeHeapCollection(String action, String name) {
        if (action == null) return false;
        String a = action.toLowerCase(Locale.ROOT);
        if (a.contains("major")) return true;   // G1 / Parallel / CMS / Serial old gen
        if (!a.contains("cycle")) return false;  // young GCs + STW pause sub-events
        // A whole-heap concurrent cycle (ZGC Major Cycles, Shenandoah Cycles) -- but not
        // a ZGC Minor cycle, whose after-usage is young-only.
        return name == null || !name.toLowerCase(Locale.ROOT).contains("minor");
    }

    private static long sumHeapUsed(Map<String, MemoryUsage> usage) {
        long total = 0L;
        for (Map.Entry<String, MemoryUsage> e : usage.entrySet()) {
            if (!heapPools.contains(e.getKey())) continue;
            MemoryUsage u = e.getValue();
            if (u != null && u.getUsed() > 0L) total += u.getUsed();
        }
        return total;
    }

    private static void updateMax(AtomicLong target, long candidate) {
        long current;
        while (candidate > (current = target.get())) {
            if (target.compareAndSet(current, candidate)) return;
        }
    }

    /**
     * Writes the current metrics to {@code outPath} atomically (tmp + ATOMIC_MOVE), so a
     * reader never sees a partial file. Repeatable -- the periodic flusher and the
     * shutdown hook both call it; {@link #WRITE_LOCK} serializes them against the shared
     * tmp file.
     */
    private static void writeSnapshot(Path outPath) {
        synchronized (WRITE_LOCK) {
            try {
                long sessionMs = System.currentTimeMillis() - START_MS;
                long ranXmxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
                long liveMb = liveSetMaxBytes.get() / (1024L * 1024L);
                long peakMb = peakHeapBytes.get() / (1024L * 1024L);
                String json = "{\n"
                    + "  \"schema_version\": 1,\n"
                    + "  \"liveSetMb\": " + liveMb + ",\n"
                    + "  \"gcPauseTotalMs\": " + gcPauseTotalMs.get() + ",\n"
                    + "  \"gcCount\": " + gcCount.get() + ",\n"
                    + "  \"sessionMs\": " + sessionMs + ",\n"
                    + "  \"peakHeapMb\": " + peakMb + ",\n"
                    + "  \"ranXmxMb\": " + ranXmxMb + ",\n"
                    + "  \"liveSetReliable\": " + liveSetReliable.get() + "\n"
                    + "}\n";
                Path parent = outPath.getParent();
                if (parent != null) Files.createDirectories(parent);
                Path tmp = outPath.resolveSibling(outPath.getFileName().toString() + ".tmp");
                Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, outPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, outPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable ignored) {
                // best-effort: a failed metrics write must never surface to the user
            }
        }
    }
}
