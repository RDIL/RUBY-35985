package rocks.rdil.rubyprobe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rocks.rdil.rubyprobe.SymbolHierarchyStub.ANON;

/**
 * The layer that stops the stall this plugin was rebuilt for.
 *
 * The collided anonymous FQN does NOT resolve to nothing. {@code $$ANON$COTE0NTUwOTUz$$} is
 * {@code ((31*0 + 29) << 15) ^ "ActiveRecord::Base.class_eval".hashCode()}, and eleven files in the
 * measured project open with that exact call at offset 0, so the merged symbol resolves to eleven
 * real elements. getAncestorsFromAnonymousDefiningCalls then re-enters ancestor resolution once per
 * element, eleven ways deep, with memoization defeated -- 33,669,997 lookups of that one key in a
 * single stall.
 *
 * Because the lookups are not empty, the negative cache cannot arm on them: that is the whole reason
 * this second mechanism exists.
 */
class BurstCutTest {

    private static final int BURST_MAX = 512;

    private StubIndexStub index;

    @BeforeAll
    static void installAgent() {
        ProbeFixture.install();
    }

    @BeforeEach
    void reset() {
        ProbeFixture.resetCounters();
        StubIndexStub.resetObserved();
        ProbeFixture.setCutBursts(true);
        ProbeFixture.setNegativeCache(true);
        index = new StubIndexStub();
    }

    @AfterEach
    void restoreDefaults() {
        StubIndexStub.resetObserved();
        ProbeFixture.setCutBursts(true);
        ProbeFixture.setNegativeCache(true);
    }

    @Test
    @DisplayName("a non-empty anonymous key is not suppressed by the negative cache")
    void negativeCacheCannotHelpANonEmptyKey() {
        StubIndexStub.setElements(11);
        ProbeFixture.setCutBursts(false);

        for (int i = 0; i < 5_000; i++) {
            index.getElements(null, null, ANON);
        }

        assertEquals(0L, ProbeFixture.suppressedLookups(),
            "the negative cache must never arm on a key that returns elements");
        assertEquals(5_000, StubIndexStub.realLookups(),
            "every lookup reaches the index, which is the runaway this fixes");
    }

    @Test
    @DisplayName("a runaway burst on a non-empty anonymous key gets cut")
    void runawayBurstIsCut() {
        StubIndexStub.setElements(11);

        int calls = 5_000;
        for (int i = 0; i < calls; i++) {
            Collection<Object> result = index.getElements(null, null, ANON);
            assertNotNull(result, "a cut lookup must return empty, never null");
        }

        assertEquals(1L, ProbeFixture.burstTrips(), "the breaker should trip exactly once");
        assertEquals(BURST_MAX, StubIndexStub.realLookups(),
            "only the pre-threshold lookups should reach the index");
        assertEquals(calls - BURST_MAX, ProbeFixture.burstSuppressed());
    }

    @Test
    @DisplayName("a cut lookup returns empty, so the caller's element loop does nothing")
    void cutLookupReturnsEmpty() {
        StubIndexStub.setElements(11);

        for (int i = 0; i < BURST_MAX; i++) {
            index.getElements(null, null, ANON);
        }
        Collection<Object> cut = index.getElements(null, null, ANON);

        assertNotNull(cut);
        assertTrue(cut.isEmpty(),
            "emptying this collection is what stops getAncestorsFromAnonymousDefiningCalls "
                + "from expanding another level");
    }

    @Test
    @DisplayName("ordinary (non-anonymous) keys are never touched")
    void ordinaryKeysUntouched() {
        StubIndexStub.setElements(3);

        for (int i = 0; i < 5_000; i++) {
            Collection<Object> result = index.getElements(null, null, "ActiveRecord::Base");
            assertEquals(3, result.size(), "a real constant path must resolve normally");
        }

        assertEquals(0L, ProbeFixture.burstTrips());
        assertEquals(5_000, StubIndexStub.realLookups());
    }
}
