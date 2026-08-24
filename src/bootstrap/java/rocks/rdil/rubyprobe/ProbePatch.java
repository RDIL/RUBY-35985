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
     * Cut a runaway anonymous-lookup burst: when one anonymous FQN is requested over and over on a
     * single thread with no pause, serve it empty for the rest of the burst.
     *
     * This is the same cut as {@link #cutCycles}, applied from the stub index instead of from
     * SymbolHierarchy, and it exists because the SymbolHierarchy advice has been observed in the
     * field as woven-but-never-entered: the tool window reported {@code wove SymbolHierarchy
     * (on load)} and {@code getAncestorsCaching entered : 0} simultaneously, with no linkage error.
     * The matcher and the advice are both correct -- applying
     * {@code named("getAncestorsCaching").and(takesArguments(2))} to the shipped SymbolHierarchy
     * offline matches 1 of 44 methods and inlines the calls at offsets 1 and 5 -- so the failure is
     * somewhere between the transformer and the executing class, and not something this plugin can
     * reach. StubKeyAdvice, by contrast, is demonstrably live: it counted 33,669,997 queries for a
     * single key during the stall this was written for.
     *
     * Why cutting the lookup terminates the recursion: getAncestorsFromAnonymousDefiningCalls looks
     * the anonymous FQN up (line 553 in build 262.10315.29), then loops over the result and re-enters
     * ancestor resolution once per element (line 556). Returning nothing for the lookup empties that
     * loop, so no element is expanded and the cycle ends -- the same place the ancestor advice aimed
     * for, reached from the other side.
     *
     * Same trade-off as {@link #cutCycles}: an anonymous class's hierarchy may come back incomplete.
     */
    public static volatile boolean cutBursts = boolProperty("rubyprobe.cutBursts", true);

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

    /**
     * Lookups of one anonymous FQN, on one thread, with no {@link #BURST_QUIET_NS} gap, before the
     * breaker trips. There are five orders of magnitude of headroom here: during the measured stall
     * the runaway key was requested 33,669,997 times, while the busiest *other* key in the entire
     * session was requested 225 times. Legitimate repeat resolution of one FQN is bounded by
     * RubySymbolsLookupCache; a rate this high is itself the signature of the caching being defeated.
     */
    private static final int BURST_MAX = intProperty("rubyprobe.burstMax", 512);
    /** A gap this long means the previous burst ended -- in practice, a new daemon pass. */
    private static final long BURST_QUIET_NS =
        intProperty("rubyprobe.burstQuietMillis", 250) * 1_000_000L;
    /** Distinct anonymous keys tracked per thread. Well above any real hierarchy's fan-out. */
    private static final int BURST_TRACKED = 64;

    private static final AtomicLong ENTERS = new AtomicLong();
    private static final AtomicLong ANON_ENTERS = new AtomicLong();
    private static final AtomicLong CUTS = new AtomicLong();
    private static final AtomicLong FRAME_RESETS = new AtomicLong();
    private static final AtomicLong NEG_SERVED = new AtomicLong();
    private static final AtomicLong NEG_ARMED = new AtomicLong();
    private static final AtomicLong DEEPEST = new AtomicLong();
    private static final AtomicLong BURST_SERVED = new AtomicLong();
    private static final AtomicLong BURST_TRIPS = new AtomicLong();
    private static volatile String lastCut = "";
    private static volatile String lastBurst = "";

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

    // ------------------------------------------------------------ burst guard

    /**
     * Per-thread lookup counts for anonymous FQNs, newest-hottest first. Plain arrays rather than a
     * map: this runs on a path measured at 113.6k calls/s, so it must not allocate.
     */
    static final class Burst {
        final String[] key = new String[BURST_TRACKED];
        final int[] count = new int[BURST_TRACKED];
        final long[] last = new long[BURST_TRACKED];
        int n;
    }

    private static final ThreadLocal<Burst> BURST = new ThreadLocal<Burst>() {
        @Override
        protected Burst initialValue() {
            return new Burst();
        }
    };

    /**
     * @return true when this anonymous key has been requested more than {@link #BURST_MAX} times on
     *         this thread without a {@link #BURST_QUIET_NS} pause.
     *
     * Move-to-front linear scan: the runaway key is by definition the one being asked for, so it
     * settles at index 0 and the common case is one reference comparison.
     */
    private static boolean burstTrip(String k) {
        Burst b = BURST.get();
        long now = System.nanoTime();
        int i = 0;
        for (; i < b.n; i++) {
            String candidate = b.key[i];
            if (candidate == k || candidate.equals(k)) {
                break;
            }
        }
        if (i == b.n) {
            if (b.n < BURST_TRACKED) {
                b.n++;
            } else {
                i = b.n - 1;                    // evict the coldest slot
            }
            b.key[i] = k;
            b.count[i] = 0;
        } else if (now - b.last[i] > BURST_QUIET_NS) {
            b.count[i] = 0;                     // the previous burst ended
        }
        b.last[i] = now;
        int c = ++b.count[i];
        if (i > 0) {
            int mc = b.count[i];
            long ml = b.last[i];
            System.arraycopy(b.key, 0, b.key, 1, i);
            System.arraycopy(b.count, 0, b.count, 1, i);
            System.arraycopy(b.last, 0, b.last, 1, i);
            b.key[0] = k;
            b.count[0] = mc;
            b.last[0] = ml;
        }
        if (c <= BURST_MAX) {
            return false;
        }
        if (c == BURST_MAX + 1) {
            BURST_TRIPS.incrementAndGet();
            lastBurst = k;
        }
        return true;
    }

    // --------------------------------------------------------- negative cache

    /** key -> {consecutive empty results, nanos when armed, total empty, total non-empty} */
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
        if (!(key instanceof String)) {
            return false;
        }
        String k = (String) key;
        if (!k.contains(ANON)) {
            return false;
        }
        // Ahead of the negative cache on purpose: the runaway key is NOT a negative lookup -- it
        // resolves to a real, non-empty collision set -- so the negative cache correctly declines to
        // arm on it and cannot be what stops it.
        if (cutBursts && burstTrip(k)) {
            BURST_SERVED.incrementAndGet();
            return true;
        }
        if (!negativeCache) {
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
        if (!(key instanceof String)) {
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
            // Non-empty keys are tracked too now. They can never arm the negative cache (their
            // consecutive-empty count stays 0), but slots 2 and 3 give the report a LIVE
            // empty/non-empty tally -- which is the fact that was missing when a stale
            // "(no elements returned)" label was read as "the index found nothing".
            if (ZEROS.size() >= NEG_MAX_KEYS) {
                return;
            }
            e = new long[]{0L, 0L, 0L, 0L};
            long[] prev = ZEROS.putIfAbsent(k, e);
            if (prev != null) {
                e = prev;
            }
        }
        synchronized (e) {
            if (!empty) {
                e[3]++;
                e[0] = 0L;
                return;
            }
            e[2]++;
            if (!negativeCache) {
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

    /**
     * Live empty/non-empty tally for an anonymous key, or "" when untracked.
     *
     * The report's "declared at" line is a first-sighting snapshot that is never revised, so for a
     * key first seen during indexing it can say "(no elements returned)" forever while every
     * subsequent lookup returns a full collision set. This is measured on every lookup instead.
     */
    public static String verdict(String key) {
        if (key == null) {
            return "";
        }
        long[] e = ZEROS.get(key);
        if (e == null) {
            return "";
        }
        long emptyCount;
        long nonEmptyCount;
        synchronized (e) {
            emptyCount = e[2];
            nonEmptyCount = e[3];
        }
        if (emptyCount == 0L && nonEmptyCount == 0L) {
            return "";
        }
        return emptyCount + " empty / " + nonEmptyCount + " non-empty";
    }

    // -------------------------------------------------------------- reporting

    public static long cuts() {
        return CUTS.get();
    }

    public static long suppressedLookups() {
        return NEG_SERVED.get();
    }

    public static long burstSuppressedLookups() {
        return BURST_SERVED.get();
    }

    public static long burstTrips() {
        return BURST_TRIPS.get();
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
        sb.append("  cut runaway bursts   : ").append(cutBursts ? "ON" : "off")
          .append("      (threshold ").append(BURST_MAX).append(" lookups/key/thread/burst)\n");
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
        sb.append("  runaway bursts cut          : ").append(BURST_TRIPS.get())
          .append("   (lookups served empty: ").append(BURST_SERVED.get()).append(")\n");
        if (!lastBurst.isEmpty()) {
            sb.append("  last burst key              : ").append(lastBurst).append('\n');
        }
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
        BURST_SERVED.set(0L);
        BURST_TRIPS.set(0L);
        lastCut = "";
        lastBurst = "";
        Burst b = BURST.get();          // this thread only; per-thread state has no global handle
        java.util.Arrays.fill(b.key, null);
        b.n = 0;
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
