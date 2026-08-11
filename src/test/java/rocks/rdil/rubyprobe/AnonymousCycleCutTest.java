package rocks.rdil.rubyprobe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rocks.rdil.rubyprobe.SymbolHierarchyStub.ANON;
import static rocks.rdil.rubyprobe.SymbolHierarchyStub.Symbol;

/** The fix: re-entrant resolution of an anonymous FQN is cut, and nothing else is touched. */
class AnonymousCycleCutTest {

    @BeforeAll
    static void installAgent() {
        ProbeFixture.install();
    }

    @BeforeEach
    void reset() {
        ProbeFixture.resetCounters();
        ProbeFixture.setCutCycles(true);
        ProbeFixture.setNegativeCache(true);
        SymbolHierarchyStub.resetObserved();
        StubIndexStub.resetObserved();
    }

    @AfterEach
    void restoreDefaults() {
        ProbeFixture.setCutCycles(true);
        ProbeFixture.setNegativeCache(true);
    }

    @Test
    @DisplayName("the shared state resolves only from the bootstrap classloader")
    void bootstrapOnly() {
        assertTrue(ProbeFixture.bootstrapLoaded(),
            "ProbeState/ProbePatch must come from the bootstrap loader, or the instrumented code and "
                + "the tool window would see different statics");
    }

    @Test
    @DisplayName("the advice is actually woven into the target")
    void adviceIsWoven() {
        SymbolHierarchyStub.getAncestorsCachingAlt(new Symbol("Rap::Album"), new Object());
        assertTrue(ProbeFixture.ancestorEntries() > 0L,
            "enter advice never ran, so the weaving silently did nothing");
    }

    @Test
    @DisplayName("an anonymous cycle terminates instead of recursing without bound")
    void cycleTerminates() {
        List<Object> result = SymbolHierarchyStub.getAncestorsCaching(new Symbol(ANON), new Object());

        assertNotNull(result, "callers do result.addAll(...) on this, so it must never be null");
        assertTrue(result.isEmpty(), "a cut must return an empty list");
        assertTrue(ProbeFixture.cuts() > 0L, "no cycle was cut");
    }

    @Test
    @DisplayName("the cut fires at the first repeat, not after the tree has expanded")
    void cutsEarly() {
        SymbolHierarchyStub.getAncestorsCaching(new Symbol(ANON), new Object());

        // anon -> singleton -> anon, so the repeat is detectable on the third frame.
        assertTrue(SymbolHierarchyStub.deepest() <= 4,
            "cut too late, at depth " + SymbolHierarchyStub.deepest()
                + "; the cost of a cut is bounded by how much was expanded before it fired");
    }

    @Test
    @DisplayName("with cutting disabled the cycle runs away again")
    void patchIsWhatStopsIt() {
        ProbeFixture.setCutCycles(false);

        // Proves the terminating behaviour above comes from the patch and not from the stand-in.
        assertThrows(IllegalStateException.class,
            () -> SymbolHierarchyStub.getAncestorsCaching(new Symbol(ANON), new Object()));
        assertEquals(0L, ProbeFixture.cuts(), "nothing should be cut while the patch is off");
    }

    @Test
    @DisplayName("non-anonymous symbols are never cut")
    void namedSymbolsUntouched() {
        SymbolHierarchyStub.namedChain(new Object(), 30);

        assertEquals(0L, ProbeFixture.cuts(),
            "ordinary resolution must be left to RecursionManager exactly as it is today");
    }

    @Test
    @DisplayName("exceptions unwinding past the exit advice do not leak the per-thread depth")
    void depthSurvivesExceptions() {
        for (int i = 0; i < 50; i++) {
            assertThrows(RuntimeException.class,
                () -> SymbolHierarchyStub.thrower(new Symbol(ANON), new Object()));
        }

        // If depth had leaked, the frame would still hold ANON and this unrelated call would be
        // mis-cut. ProcessCanceledException makes this the common path, not an edge case.
        long before = ProbeFixture.cuts();
        SymbolHierarchyStub.getAncestorsCachingAlt(new Symbol(ANON), new Object());
        assertEquals(before, ProbeFixture.cuts(), "a stale frame caused a spurious cut");
    }

    @Test
    @DisplayName("the report names what was cut")
    void reportIsUsable() {
        SymbolHierarchyStub.getAncestorsCaching(new Symbol(ANON), new Object());

        String report = ProbeFixture.report();
        assertTrue(report.contains(ANON), "report should name the FQN it cut:\n" + report);
        assertFalse(report.contains("advice NOT woven"),
            "report claims the advice is not woven:\n" + report);
    }
}
