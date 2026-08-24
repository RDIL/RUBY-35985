package rocks.rdil.rubyprobe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour only: what reaches the index, and what comes back. Nothing here reads the guard's
 * internals, because in the IDE nothing does either.
 *
 * Each test uses its own key. The guard's counters are per thread and per key with no reset API by
 * design, so distinct keys are what keeps the tests independent.
 */
class BurstGuardTest {

    /** Matches the BURST_MAX default. */
    private static final int MAX = 512;

    private StubIndexStub index;

    @BeforeAll
    static void installAgent() {
        TestAgent.install();
    }

    @BeforeEach
    void reset() {
        StubIndexStub.reset();
        index = new StubIndexStub();
    }

    @Test
    @DisplayName("a runaway burst on a non-empty anonymous key stops reaching the index")
    void runawayBurstIsCut() {
        String key = "$$ANON$Crunaway$$";

        Collection<Object> last = null;
        for (int i = 0; i < 5_000; i++) {
            last = index.getElements(null, null, key);
            assertNotNull(last, "a cut lookup must return empty, never null");
        }

        assertEquals(MAX, StubIndexStub.realLookups(),
            "only the pre-threshold lookups should reach the index");
        assertTrue(last.isEmpty(),
            "emptying the result is what stops getAncestorsFromAnonymousDefiningCalls "
                + "from expanding another level");
    }

    @Test
    @DisplayName("an anonymous key below the threshold is left alone")
    void belowThresholdUntouched() {
        String key = "$$ANON$Cmodest$$";

        for (int i = 0; i < MAX; i++) {
            assertEquals(11, index.getElements(null, null, key).size(),
                "normal anonymous resolution must not be disturbed");
        }

        assertEquals(MAX, StubIndexStub.realLookups());
    }

    @Test
    @DisplayName("ordinary constant paths are never touched, however often they are asked for")
    void ordinaryKeysUntouched() {
        StubIndexStub.setElements(3);

        for (int i = 0; i < 5_000; i++) {
            assertEquals(3, index.getElements(null, null, "ActiveRecord::Base").size());
        }

        assertEquals(5_000, StubIndexStub.realLookups());
    }
}
