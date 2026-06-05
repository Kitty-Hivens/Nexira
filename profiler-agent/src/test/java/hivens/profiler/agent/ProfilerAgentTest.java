package hivens.profiler.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks {@link ProfilerAgent#isWholeHeapCollection} against the (gcName, gcAction)
 * pairs observed from the real GC notifications on JDK 25. If a future JDK renames
 * these, re-probe the collectors and update the classifier and this table together.
 */
class ProfilerAgentTest {

    @Test
    void generationalOldGenCounts() {
        assertTrue(ProfilerAgent.isWholeHeapCollection("end of major GC", "G1 Old Generation"));
        assertTrue(ProfilerAgent.isWholeHeapCollection("end of major GC", "PS MarkSweep"));
    }

    @Test
    void concurrentWholeHeapCyclesCount() {
        assertTrue(ProfilerAgent.isWholeHeapCollection("end of GC cycle", "ZGC Major Cycles"));
        assertTrue(ProfilerAgent.isWholeHeapCollection("end of GC cycle", "Shenandoah Cycles"));
    }

    @Test
    void youngAndMinorCollectionsDoNotCount() {
        assertFalse(ProfilerAgent.isWholeHeapCollection("end of minor GC", "G1 Young Generation"));
        assertFalse(ProfilerAgent.isWholeHeapCollection("end of minor GC", "PS Scavenge"));
        assertFalse(ProfilerAgent.isWholeHeapCollection("end of GC cycle", "ZGC Minor Cycles"));
    }

    @Test
    void stwPauseSubEventsDoNotCount() {
        assertFalse(ProfilerAgent.isWholeHeapCollection("end of GC pause", "ZGC Major Pauses"));
        assertFalse(ProfilerAgent.isWholeHeapCollection("Init Mark", "Shenandoah Pauses"));
        assertFalse(ProfilerAgent.isWholeHeapCollection("end of concurrent GC pause", "G1 Concurrent GC"));
    }

    @Test
    void nullActionIsSafeAndNullNameIsAllowed() {
        assertFalse(ProfilerAgent.isWholeHeapCollection(null, "anything"));
        assertTrue(ProfilerAgent.isWholeHeapCollection("end of major GC", null));
    }
}
