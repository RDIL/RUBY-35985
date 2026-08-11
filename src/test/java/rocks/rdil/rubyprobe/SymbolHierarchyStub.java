package rocks.rdil.rubyprobe;

import java.util.Collections;
import java.util.List;

/**
 * Stands in for {@code SymbolHierarchy}, mirroring the real signatures: static methods taking
 * {@code (Symbol, PsiElement)} and returning {@code List}.
 *
 * It reproduces the *defect*, not merely a deep call. The recursion alternates between an anonymous
 * symbol and its singleton and builds a FRESH Symbol instance at every step, which is exactly why
 * RubyMine's two guards miss it: {@code doPreventingRecursion} keys on
 * {@code Pair(symbol, invocationPoint)} (Symbol identity), and the {@code visited} set in
 * {@code getAncestorsFromAnonymousDefiningCalls} is allocated per {@code getAncestorsCaching} call.
 *
 * Without the patch this recurses until {@link #RUNAWAY_DEPTH}, which the tests rely on to prove the
 * patch is what stops it.
 */
public final class SymbolHierarchyStub {

    static final String ANON = "$$ANON$COTE0NTUwOTUz$$";
    static final String SINGLETON = ANON + "::$$SINGLETON$$";

    static final int RUNAWAY_DEPTH = 400;

    /** Mirrors {@code FQN}. */
    public static final class FQN {
        private final String path;

        FQN(String path) {
            this.path = path;
        }

        public String getFullPath() {
            return path;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /** Mirrors {@code Symbol}. Identity-equal only: two Symbols for one FQN are NOT equal. */
    public static final class Symbol {
        private final String fqn;

        public Symbol(String fqn) {
            this.fqn = fqn;
        }

        public String getName() {
            return fqn;
        }

        public FQN getFQNWithNesting() {
            return new FQN(fqn);
        }
    }

    static final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> Integer.valueOf(0));

    private static volatile int deepest;
    private static volatile int bodyRuns;

    static int deepest() {
        return deepest;
    }

    static void resetObserved() {
        deepest = 0;
        bodyRuns = 0;
        depth.set(Integer.valueOf(0));
    }

    /** The cycle. Woven by the fixture. */
    public static List<Object> getAncestorsCaching(Object symbol, Object psi) {
        bodyRuns++;
        int d = depth.get().intValue() + 1;
        depth.set(Integer.valueOf(d));
        if (d > deepest) {
            deepest = d;
        }
        try {
            if (d > RUNAWAY_DEPTH) {
                throw new IllegalStateException("runaway recursion: the cycle was not cut");
            }
            String fqn = ((Symbol) symbol).getName();
            String next = ANON.equals(fqn) ? SINGLETON : ANON;
            getAncestorsCaching(new Symbol(next), psi);
        } finally {
            depth.set(Integer.valueOf(d - 1));
        }
        return Collections.emptyList();
    }

    /** A second woven entry point with no recursion of its own, for the non-cycle cases. */
    public static List<Object> getAncestorsCachingAlt(Object symbol, Object psi) {
        return Collections.emptyList();
    }

    /** Woven, and always throws: stands in for the routine ProcessCanceledException. */
    public static List<Object> thrower(Object symbol, Object psi) {
        throw new RuntimeException("simulated ProcessCanceledException");
    }

    /** A deep chain of NAMED symbols, which the patch must leave completely alone. */
    public static void namedChain(Object psi, int remaining) {
        if (remaining == 0) {
            return;
        }
        getAncestorsCachingAlt(new Symbol("Rap::Album" + remaining), psi);
        namedChain(psi, remaining - 1);
    }
}
