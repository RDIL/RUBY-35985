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
import static rocks.rdil.rubyprobe.ProbeFixture.ANON;

/**
 * Anonymous stub keys measured to return nothing stop reaching the index.
 *
 * This covers the cheaper of the two anonymous-FQN failures: a merged symbol that resolves to
 * nothing. For the expensive one -- a merged symbol that resolves to a large collision set -- see
 * {@link BurstCutTest}, which this layer provably cannot help with.
 */
class NegativeLookupCacheTest {

    private static final int MIN_ZEROS = 3;

    private StubIndexStub index;

    @BeforeAll
    static void installAgent() {
        ProbeFixture.install();
    }

    @BeforeEach
    void reset() {
        ProbeFixture.resetCounters();
        ProbeFixture.setNegativeCache(true);
        // Off for this class: the burst breaker would also serve these lookups empty, and then the
        // counts below would no longer isolate the layer under test.
        ProbeFixture.setCutBursts(false);
        StubIndexStub.resetObserved();
        index = new StubIndexStub();
    }

    @AfterEach
    void restoreDefaults() {
        ProbeFixture.setNegativeCache(true);
        ProbeFixture.setCutBursts(true);
    }

    @Test
    @DisplayName("repeated empty results for an anonymous key stop reaching the index")
    void emptyAnonymousLookupsGetSuppressed() {
        for (int i = 0; i < 500; i++) {
            Collection<Object> result = index.getElements(null, null, ANON);
            assertNotNull(result, "a suppressed lookup must not return null");
            assertTrue(result.isEmpty());
        }

        assertEquals(MIN_ZEROS, StubIndexStub.realLookups(),
            "only the confirming lookups should reach the index");
        assertEquals(500L - MIN_ZEROS, ProbeFixture.suppressedLookups());
    }

    @Test
    @DisplayName("ordinary keys always reach the index")
    void ordinaryKeysNeverSuppressed() {
        for (int i = 0; i < 200; i++) {
            index.getElements(null, null, "ActiveRecord::Base");
        }

        assertEquals(200, StubIndexStub.realLookups(),
            "suppression must be confined to synthesized $$ANON keys");
        assertEquals(0L, ProbeFixture.suppressedLookups());
    }

    @Test
    @DisplayName("with the negative cache disabled every lookup reaches the index")
    void disabledMeansNoSuppression() {
        ProbeFixture.setNegativeCache(false);

        for (int i = 0; i < 100; i++) {
            index.getElements(null, null, ANON);
        }

        assertEquals(100, StubIndexStub.realLookups());
        assertEquals(0L, ProbeFixture.suppressedLookups());
    }
}
