package rocks.rdil.rubyprobe;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The runtime fix, as opposed to the measurement in {@link ProbeState}.
 *
 * Background: {@code AnonymousDefiningCallType#getAnonymousClassName} derives an anonymous
 * class/module's FQN from nothing but {@code (textRange.hashCode() << 15) ^ text.hashCode()}. The
 * containing file is not part of the identity, so N files whose anonymous defining call has the same
 * text at the same offset collapse into ONE symbol.
 *
 * That merged symbol resolves to zero index elements, and the resulting ancestor resolution does not
 * converge. RubyMine already tries to stop it twice, and both guards miss:
 *
 * <ul>
 *   <li>{@code SymbolHierarchy.getAncestorsCaching} wraps the computation in
 *       {@code RecursionManager.doPreventingRecursion(Pair.create(symbol, invocationPoint), ...)}.
 *       The key is Symbol <em>identity</em> plus invocation point, so re-entering for the same FQN
 *       through a freshly built Symbol instance is not recognised as recursion.</li>
 *   <li>{@code SymbolHierarchy.getAncestorsFromAnonymousDefiningCalls} checks
 *       {@code symbol instanceof AnonymousClassModuleSymbol && visited.contains(symbol)}, but
 *       {@code visited} is allocated inside {@code computeAncestors}, i.e. per
 *       {@code getAncestorsCaching} invocation -- and the cycle crosses that boundary.</li>
 * </ul>
 *
 * So the guard here is what those two intend, with the two gaps closed: it is keyed on the FQN
 * <em>string</em> rather than Symbol identity, and it lives on the thread rather than inside one
 * invocation. When {@code getAncestorsCaching} is entered for an anonymous FQN already being
 * resolved further up the same thread's stack, the body is skipped and an empty list is returned --
 * which is exactly what the method itself returns when {@code doPreventingRecursion} trips.
 *
 * One deliberate side effect: because the cut happens before RecursionManager is consulted, the
 * outer frame's {@code RecursionGuard.StackStamp.mayCacheNow()} is no longer forced to false, so
 * {@code RubySymbolsLookupCache.registerAncestors} can actually memoize the result. That is the
 * difference between "recomputed on every daemon pass" and "computed once". The trade-off is that a
 * cycle-cut ancestor list may be incomplete and is now cacheable; see {@code cutCycles} to turn it
 * off.
 *
 * JDK-only, like ProbeState -- this is loaded by the bootstrap classloader.
 */
public final class ProbePatch {

    private ProbePatch() {
    }

    /** Marks a synthesized anonymous class/module FQN. The literal in RubyMine's own code. */
    private static final String ANON = "$$ANON";

    /** Skip re-entrant ancestor resolution of an anonymous FQN already on this thread's stack. */
    public static volatile boolean cutCycles = boolProperty("rubyprobe.cutCycles", true);

    /** Serve an empty result for anonymous stub keys that have been measured to return nothing. */
    public static volatile boolean negativeCache = boolProperty("rubyprobe.negativeCache", true);

    /**
     * Only start cycle-checking at this depth. 1 (the default) checks every frame, which is both the
     * safest and the cheapest place to cut -- the cost of a cut is bounded by how much of the
     * exponential tree was already expanded before it fired.
     */
    private static final int MIN_DEPTH = intProperty("rubyprobe.cutMinDepth", 1);

    /** Per-thread ceiling on tracked FQNs. Well above any legitimate hierarchy. */
    private static final int MAX_TRACKED = 256;

    /** A frame depth beyond this means our accounting was lost; rebuild it rather than mis-cut. */
    private static final int SANITY_DEPTH = 4096;

    private static final long NEG_TTL_NS =
        intProperty("rubyprobe.negTtlMillis", 5000) * 1_000_000L;
    private static final int NEG_MIN_ZEROS = intProperty("rubyprobe.negMinZeros", 3);
    /**
     * Sized for a codebase that generates anonymous symbols in bulk. The first field measurement hit
     * 387 armed keys against an earlier cap of 512 while the report's key table was full at 2048
     * distinct keys, i.e. the cache was rationing itself out of usefulness on the workload it exists
     * for. Each entry is a String plus a two-element long[].
     */
    private static final int NEG_MAX_KEYS = intProperty("rubyprobe.negMaxKeys", 16384);

    private static final AtomicLong ENTERS = new AtomicLong();
    private static final AtomicLong ANON_ENTERS = new AtomicLong();
    private static final AtomicLong CUTS = new AtomicLong();
    private static final AtomicLong FRAME_RESETS = new AtomicLong();
    private static final AtomicLong NEG_SERVED = new AtomicLong();
    private static final AtomicLong NEG_ARMED = new AtomicLong();
    private static final AtomicLong DEEPEST = new AtomicLong();
    private static volatile String lastCut = "";

    // ------------------------------------------------------------ cycle guard

    /**
     * Anonymous FQNs currently being resolved on this thread, each tagged with the depth it was
     * pushed at. Tagging by depth means unwinding needs no symbol and no reflection, and self-heals
     * if an exception (ProcessCanceledException is routine here) skips some of the pops.
     */
    static final class Frame {
        int depth;
        final String[] fqn = new String[MAX_TRACKED];
        final int[] at = new int[MAX_TRACKED];
        int n;
    }

    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<Frame>() {
        @Override
        protected Frame initialValue() {
            return new Frame();
        }
    };

    /**
     * @return true when the caller should skip the method body and return an empty list, i.e. when
     *         this anonymous FQN is already being resolved further up this thread's stack.
     */
    public static boolean enterAncestors(Object symbol) {
        Frame f = FRAME.get();
        ENTERS.incrementAndGet();
        int d = ++f.depth;
        if (d > SANITY_DEPTH) {
            f.depth = 1;
            f.n = 0;
            d = 1;
            FRAME_RESETS.incrementAndGet();
        }
        if (!cutCycles || d < MIN_DEPTH || symbol == null) {
            return false;
        }
        String fqn = anonFqn(symbol);
        if (fqn == null) {
            return false;
        }
        ANON_ENTERS.incrementAndGet();
        for (int i = 0; i < f.n; i++) {
            if (fqn.equals(f.fqn[i])) {
                CUTS.incrementAndGet();
                lastCut = fqn;
                DEEPEST.accumulateAndGet(d, Math::max);
                return true;
            }
        }
        if (f.n < MAX_TRACKED) {
            f.fqn[f.n] = fqn;
            f.at[f.n] = d;
            f.n++;
        }
        DEEPEST.accumulateAndGet(d, Math::max);
        return false;
    }

    /** Symmetric with {@link #enterAncestors}, including when the body was skipped or threw. */
    public static void leaveAncestors() {
        Frame f = FRAME.get();
        int d = f.depth - 1;
        if (d < 0) {
            d = 0;
        }
        f.depth = d;
        while (f.n > 0 && f.at[f.n - 1] > d) {
            f.n--;
        }
    }

    /**
     * The symbol's FQN, but only when it is an anonymous one -- null otherwise, which is the common
     * case and keeps ordinary ancestor resolution entirely untouched by this patch.
     *
     * Restricting to {@code $$ANON} is what bounds the blast radius: real Ruby constant paths cannot
     * contain '$', so this matches the synthesized family and nothing else. Non-anonymous cycles are
     * left to RecursionManager exactly as they are today.
     */
    private static String anonFqn(Object symbol) {
        Object fqn = ProbeState.invoke0(symbol, "getFQNWithNesting");
        if (fqn == null) {
            return null;
        }
        Object path = ProbeState.invoke0(fqn, "getFullPath");
        String s;
        if (path instanceof String) {
            s = (String) path;
        } else {
            try {
                s = fqn.toString();
            } catch (Throwable t) {
                return null;
            }
        }
        return (s != null && s.contains(ANON)) ? s : null;
    }

    // --------------------------------------------------------- negative cache

    /** key -> {consecutive empty results, nanos when armed} */
    private static final ConcurrentHashMap<String, long[]> ZEROS = new ConcurrentHashMap<>();

    /**
     * @return true when this lookup is known to return nothing and should be skipped outright.
     *
     * Second line of defence, and the only one that helps if the SymbolHierarchy weaving fails:
     * roughly half of all CPU during a stall was measured inside this one method, returning empty.
     * Restricted to anonymous keys, and armed only after {@code NEG_MIN_ZEROS} consecutive measured
     * empty results, with a short TTL so a key that starts resolving is not suppressed for long.
     */
    public static boolean shouldSkipLookup(Object key) {
        if (!negativeCache || !(key instanceof String)) {
            return false;
        }
        String k = (String) key;
        if (!k.contains(ANON)) {
            return false;
        }
        long[] e = ZEROS.get(k);
        if (e == null) {
            return false;
        }
        synchronized (e) {
            if (e[0] < NEG_MIN_ZEROS) {
                return false;
            }
            if (System.nanoTime() - e[1] > NEG_TTL_NS) {
                // Expired: let one real lookup through to re-confirm before suppressing again.
                e[0] = NEG_MIN_ZEROS - 1;
                return false;
            }
        }
        NEG_SERVED.incrementAndGet();
        return true;
    }

    /** Feeds the negative cache with what the index actually returned. */
    public static void recordLookup(Object key, Object result) {
        if (!negativeCache || !(key instanceof String)) {
            return;
        }
        String k = (String) key;
        if (!k.contains(ANON)) {
            return;
        }
        boolean empty = result == null
            || (result instanceof Collection && ((Collection<?>) result).isEmpty());
        long[] e = ZEROS.get(k);
        if (e == null) {
            if (!empty || ZEROS.size() >= NEG_MAX_KEYS) {
                return;
            }
            e = new long[]{0L, 0L};
            long[] prev = ZEROS.putIfAbsent(k, e);
            if (prev != null) {
                e = prev;
            }
        }
        synchronized (e) {
            if (!empty) {
                e[0] = 0L;
                return;
            }
            e[0]++;
            if (e[0] >= NEG_MIN_ZEROS) {
                if (e[1] == 0L) {
                    NEG_ARMED.incrementAndGet();
                }
                e[1] = System.nanoTime();
            }
        }
    }

    // -------------------------------------------------------------- reporting

    public static long cuts() {
        return CUTS.get();
    }

    public static long suppressedLookups() {
        return NEG_SERVED.get();
    }

    public static long ancestorEntries() {
        return ENTERS.get();
    }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nruntime patch\n");
        sb.append("  cut anonymous cycles : ").append(cutCycles ? "ON" : "off")
          .append("      negative lookup cache : ").append(negativeCache ? "ON" : "off")
          .append('\n');
        long enters = ENTERS.get();
        sb.append("  getAncestorsCaching entered : ").append(enters);
        if (enters == 0L) {
            sb.append("   [advice NOT woven -- cycle cutting is INACTIVE]");
        }
        sb.append('\n');
        sb.append("  anonymous symbols seen      : ").append(ANON_ENTERS.get()).append('\n');
        sb.append("  cycles cut                  : ").append(CUTS.get()).append('\n');
        sb.append("  deepest tracked recursion   : ").append(DEEPEST.get()).append('\n');
        if (!lastCut.isEmpty()) {
            sb.append("  last cut                    : ").append(lastCut).append('\n');
        }
        sb.append("  empty lookups suppressed    : ").append(NEG_SERVED.get())
          .append("   (keys armed: ").append(NEG_ARMED.get())
          .append(" of ").append(NEG_MAX_KEYS).append(" max, ").append(ZEROS.size())
          .append(" tracked)\n");
        String adviceError = null;
        try {
            adviceError = System.getProperty("rubyprobe.adviceError");
        } catch (Throwable ignored) {
            // best effort
        }
        if (adviceError != null) {
            sb.append("  ADVICE LINKAGE ERROR        : ").append(adviceError).append('\n');
        } else if (enters == 0L) {
            sb.append("  (no linkage error recorded, so the advice is absent, not failing)\n");
        }
        if (FRAME_RESETS.get() > 0L) {
            sb.append("  frame resets                : ").append(FRAME_RESETS.get()).append('\n');
        }
        return sb.toString();
    }

    public static void resetCounters() {
        ENTERS.set(0L);
        ANON_ENTERS.set(0L);
        CUTS.set(0L);
        FRAME_RESETS.set(0L);
        NEG_SERVED.set(0L);
        NEG_ARMED.set(0L);
        DEEPEST.set(0L);
        lastCut = "";
        ZEROS.clear();
    }

    // -------------------------------------------------------------- properties

    private static boolean boolProperty(String name, boolean defaultValue) {
        try {
            String v = System.getProperty(name);
            return v == null ? defaultValue : Boolean.parseBoolean(v);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private static int intProperty(String name, int defaultValue) {
        try {
            String v = System.getProperty(name);
            return v == null ? defaultValue : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return defaultValue;
        }
    }
}
