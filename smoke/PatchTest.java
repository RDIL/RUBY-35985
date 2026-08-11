import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Verifies the runtime PATCH, not the measurement.
 *
 * The stand-ins reproduce the real defect rather than merely a deep call: SymbolHierarchyStub
 * alternates between an anonymous symbol and its singleton, building a FRESH Symbol instance every
 * time, which is exactly why RubyMine's own guards (keyed on Symbol identity, and on a visited set
 * scoped to one getAncestorsCaching invocation) fail to break the cycle. Without the patch this
 * recurses until the stack overflows.
 */
public class PatchTest {

    static final String ANON = "$$ANON$COTE0NTUwOTUz$$";
    static final String SINGLETON = ANON + "::$$SINGLETON$$";

    // ---- stand-ins mirroring the real signatures --------------------------

    public static final class FQN {
        private final String path;
        FQN(String path) { this.path = path; }
        public String getFullPath() { return path; }
        @Override public String toString() { return path; }
    }

    /** Deliberately identity-equal only: two Symbols for the same FQN are NOT equal. */
    public static final class Symbol {
        private final String fqn;
        public Symbol(String fqn) { this.fqn = fqn; }
        public String getName() { return fqn; }
        public FQN getFQNWithNesting() { return new FQN(fqn); }
    }

    static int bodyRuns = 0;
    static int deepest = 0;

    /** mirrors SymbolHierarchy.getAncestorsCaching(Symbol, PsiElement) -- static, 2 args, List */
    public static final class SymbolHierarchyStub {
        static final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

        public static List<Object> getAncestorsCaching(Object symbol, Object psi) {
            bodyRuns++;
            int d = depth.get() + 1;
            depth.set(d);
            deepest = Math.max(deepest, d);
            try {
                if (d > 400) {
                    throw new IllegalStateException("runaway recursion: patch did not cut");
                }
                String fqn = ((Symbol) symbol).getName();
                // anon -> its singleton -> anon -> ... each with a brand new Symbol instance
                String next = fqn.equals(ANON) ? SINGLETON : ANON;
                getAncestorsCaching(new Symbol(next), psi);
            } finally {
                depth.set(d - 1);
            }
            return Collections.emptyList();
        }

        /** Named symbols must be left alone entirely. */
        public static List<Object> namedChain(Object symbol, Object psi, int remaining) {
            if (remaining == 0) {
                return Collections.emptyList();
            }
            getAncestorsCaching2(new Symbol("Rap::Album" + remaining), psi);
            return namedChain(symbol, psi, remaining - 1);
        }

        /** Separate 2-arg entry point woven the same way, used for the named + throwing cases. */
        public static List<Object> getAncestorsCaching2(Object symbol, Object psi) {
            return Collections.emptyList();
        }

        public static List<Object> thrower(Object symbol, Object psi) {
            throw new RuntimeException("simulated ProcessCanceledException");
        }
    }

    /** mirrors RubyStringStubIndexExtension.getElements(Project, SearchScope, String) */
    public static class StubIndexStub {
        static int realLookups = 0;
        public Collection<Object> getElements(Object project, Object scope, String key) {
            realLookups++;
            return Collections.emptyList();
        }
    }

    // ---- run -------------------------------------------------------------

    static final List<String> failures = new ArrayList<>();

    static void check(boolean ok, String what) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) {
            failures.add(what);
        }
    }

    public static void main(String[] args) throws Exception {
        Instrumentation inst = ByteBuddyAgent.install();
        inst.appendToBootstrapClassLoaderSearch(new JarFile(new File(args[0])));

        Class<?> patch = Class.forName("rocks.rdil.rubyprobe.ProbePatch", true, null);
        check(patch.getClassLoader() == null, "ProbePatch loads from the bootstrap classloader");

        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(ElementMatchers.named(SymbolHierarchyStub.class.getName()))
            .transform((b, t, cl, m, pd) -> b.visit(
                Advice.to(rocks.rdil.rubyprobe.AncestorsCutAdvice.class)
                    .on(ElementMatchers.namedOneOf("getAncestorsCaching", "getAncestorsCaching2",
                            "thrower")
                        .and(ElementMatchers.takesArguments(2)))))
            .type(ElementMatchers.named(StubIndexStub.class.getName()))
            .transform((b, t, cl, m, pd) -> b.visit(
                Advice.to(rocks.rdil.rubyprobe.StubKeyAdvice.class)
                    .on(ElementMatchers.named("getElements")
                        .and(ElementMatchers.takesArgument(2, String.class)))))
            .installOn(inst);

        Method report = patch.getMethod("report");
        Method cuts = patch.getMethod("cuts");
        Method entries = patch.getMethod("ancestorEntries");
        Method suppressed = patch.getMethod("suppressedLookups");
        Method resetCounters = patch.getMethod("resetCounters");

        // 1. the cycle terminates, and returns a usable empty list rather than null
        List<Object> result = null;
        Throwable thrown = null;
        try {
            result = SymbolHierarchyStub.getAncestorsCaching(new Symbol(ANON), new Object());
        } catch (Throwable t) {
            thrown = t;
        }
        check(thrown == null, "anonymous cycle terminates instead of overflowing the stack"
            + (thrown == null ? "" : " -- got " + thrown));
        check(result != null, "cut returns non-null (callers do result.addAll on it)");
        check(result != null && result.isEmpty(), "cut returns an empty list");
        check(((Long) cuts.invoke(null)) > 0L, "at least one cycle was cut");
        check(((Long) entries.invoke(null)) > 0L, "enter advice actually fired (weaving works)");
        // The cycle is anon -> singleton -> anon, so the repeat is detectable on the 3rd frame.
        check(deepest <= 4, "cut fires at the first repeat, not after deep expansion (depth "
            + deepest + ")");

        // 2. named symbols are never cut
        long cutsBefore = (Long) cuts.invoke(null);
        SymbolHierarchyStub.namedChain(new Symbol("Rap::Album"), new Object(), 30);
        check(((Long) cuts.invoke(null)) == cutsBefore,
            "non-anonymous symbols are never cut");

        // 3. depth accounting survives an exception unwinding past the exit advice
        for (int i = 0; i < 50; i++) {
            try {
                SymbolHierarchyStub.thrower(new Symbol(ANON), new Object());
            } catch (RuntimeException expected) {
                // ProcessCanceledException stands in for the routine case
            }
        }
        // If depth leaked, the frame would still hold ANON and this fresh call would be mis-cut.
        long cutsBefore2 = (Long) cuts.invoke(null);
        SymbolHierarchyStub.getAncestorsCaching2(new Symbol(ANON), new Object());
        check(((Long) cuts.invoke(null)) == cutsBefore2,
            "50 exceptions do not leak depth into the next unrelated call");

        // Captured before the reset below, or the report would read "cycles cut: 0".
        String cycleReport = String.valueOf(report.invoke(null));

        // 4. negative cache: repeated empty results for an anonymous key stop reaching the index
        resetCounters.invoke(null);
        StubIndexStub idx = new StubIndexStub();
        StubIndexStub.realLookups = 0;
        for (int i = 0; i < 500; i++) {
            Collection<Object> c = idx.getElements(null, null, ANON);
            if (c == null) {
                check(false, "suppressed lookup returned null");
                break;
            }
        }
        check(StubIndexStub.realLookups < 10,
            "empty anonymous lookups get suppressed (" + StubIndexStub.realLookups
                + "/500 reached the index)");
        check(((Long) suppressed.invoke(null)) > 400L, "suppression is counted");

        // 5. a non-anonymous key is never suppressed
        StubIndexStub.realLookups = 0;
        for (int i = 0; i < 200; i++) {
            idx.getElements(null, null, "ActiveRecord::Base");
        }
        check(StubIndexStub.realLookups == 200, "ordinary keys always reach the index");

        // 6. toggling off restores stock behaviour
        Class<?> ps = Class.forName("rocks.rdil.rubyprobe.ProbeState", true, null);
        ps.getMethod("setCutCycles", boolean.class).invoke(null, Boolean.FALSE);
        boolean overflowed = false;
        try {
            SymbolHierarchyStub.getAncestorsCaching(new Symbol(ANON), new Object());
        } catch (Throwable t) {
            overflowed = true;
        }
        check(overflowed, "with the patch off, the cycle runs away again (proves the patch is "
            + "what stopped it, not the stand-in)");
        ps.getMethod("setCutCycles", boolean.class).invoke(null, Boolean.TRUE);

        System.out.println();
        System.out.println("---- after the cycle tests ----");
        System.out.println(cycleReport);
        System.out.println("---- after the lookup tests (counters were reset between) ----");
        System.out.println(report.invoke(null));

        if (!failures.isEmpty()) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("all patch assertions passed");
    }
}
