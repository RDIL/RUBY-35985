package rocks.rdil.rubyprobe;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The runtime fix, as opposed to the measurement in {@link ProbeState}.
 *
 * Background: {@code AnonymousDefiningCallType#getAnonymousClassName} derives an anonymous
 * class/module's FQN as
 * {@code "$$ANON" + ("$C"|"$M") + Base64(Integer.toString(((31*start + end) << 15) ^ text.hashCode())) + "$$"}.
 * The containing file is not part of the identity, so N files whose anonymous defining call has the
 * same text at the same offset collapse into ONE symbol. In the project this was built for, eleven
 * files open with {@code ActiveRecord::Base.class_eval} at offset 0 and collapse into
 * {@code $$ANON$COTE0NTUwOTUz$$} (hash 914550953).
 *
 * That merged symbol resolves to ELEVEN elements, not zero -- which is the crux.
 * {@code getAncestorsFromAnonymousDefiningCalls} looks the FQN up, then loops over the result and
 * re-enters ancestor resolution once per element. Eleven ways wide, ~13 deep, with memoization
 * defeated because {@code RecursionManager} holds the stack stamp: the tree does not finish.
 * Measured at 33,669,997 lookups of that one key in a single stall, ~113k/s.
 *
 * <p><b>Two mechanisms, both keyed on {@code $$ANON} so ordinary resolution is untouched:</b>
 *
 * <ul>
 *   <li>{@link #cutBursts} -- the cure. When one anonymous FQN is requested past a threshold on one
 *       thread with no pause, its lookup is served empty. Emptying the lookup empties the caller's
 *       element loop, so no element is expanded and the recursion ends.</li>
 *   <li>{@link #negativeCache} -- for keys that genuinely resolve to nothing, which is a different
 *       (and cheaper) failure. It cannot help the case above: a non-empty lookup never arms it.</li>
 * </ul>
 *
 * <p><b>Why this lives at the stub index and not at {@code SymbolHierarchy}.</b> A previous version
 * cut the cycle in {@code SymbolHierarchy.getAncestorsCaching} via woven advice. That advice never
 * executed in a real IDE: the tool window reported {@code wove SymbolHierarchy (on load)} and
 * {@code getAncestorsCaching entered : 0} at the same time, with no linkage error, while the root
 * symbol table stayed empty. It was not a matcher fault -- applying
 * {@code named("getAncestorsCaching").and(takesArguments(2))} to the shipped
 * {@code intellij.ruby.backend.jar} matches 1 of 44 methods and inlines the calls at bytecode
 * offsets 1 and 5 -- so the break is between the transformer and the executing class, most likely
 * the Ruby plugin's classloader being rebuilt after the agent attaches. It has been removed rather
 * than left in place reporting "ON" while doing nothing.
 *
 * <p><b>Trade-off:</b> a cut lookup means an anonymous class's ancestor list can come back
 * incomplete. See {@link #cutBursts} to turn it off.
 *
 * JDK-only, like ProbeState -- this is loaded by the bootstrap classloader.
 */
public final class ProbePatch {

    private ProbePatch() {
    }

    /** Marks a synthesized anonymous class/module FQN. The literal in RubyMine's own code. */
    private static final String ANON = "$$ANON";

    /**
     * Serve an empty result for an anonymous stub key that one thread keeps requesting with no pause.
     *
     * This is the cut that actually fires. It rides {@link StubKeyAdvice} on
     * {@code RubyStringStubIndexExtension.getElements}, which is the one instrumentation point in
     * this plugin demonstrated to be live in a real IDE.
     */
    public static volatile boolean cutBursts = boolProperty("rubyprobe.cutBursts", true);

    /** Serve an empty result for anonymous stub keys that have been measured to return nothing. */
    public static volatile boolean negativeCache = boolProperty("rubyprobe.negativeCache", true);

    private static final long NEG_TTL_NS =
        intProperty("rubyprobe.negTtlMillis", 5000) * 1_000_000L;
    private static final int NEG_MIN_ZEROS = intProperty("rubyprobe.negMinZeros", 3);
    /**
     * Sized for a codebase that generates anonymous symbols in bulk. The first field measurement hit
     * 387 armed keys against an earlier cap of 512 while the report's key table was full at 2048
     * distinct keys, i.e. the cache was rationing itself out of usefulness on the workload it exists
     * for. Each entry is a String plus a four-element long[].
     */
    private static final int NEG_MAX_KEYS = intProperty("rubyprobe.negMaxKeys", 16384);

    /**
     * Lookups of one anonymous FQN, on one thread, with no {@link #BURST_QUIET_NS} gap, before the
     * breaker trips. There are five orders of magnitude of headroom here: during the measured stall
     * the runaway key was requested 33,669,997 times, while the busiest *other* key in the entire
     * session was requested 225 times. Legitimate repeat resolution of one FQN is bounded by
     * RubySymbolsLookupCache; a rate this high is itself the signature of that caching being defeated.
     */
    private static final int BURST_MAX = intProperty("rubyprobe.burstMax", 512);
    /** A gap this long means the previous burst ended -- in practice, a new daemon pass. */
    private static final long BURST_QUIET_NS =
        intProperty("rubyprobe.burstQuietMillis", 250) * 1_000_000L;
    /** Distinct anonymous keys tracked per thread. Well above any real hierarchy's fan-out. */
    private static final int BURST_TRACKED = 64;

    private static final AtomicLong NEG_SERVED = new AtomicLong();
    private static final AtomicLong NEG_ARMED = new AtomicLong();
    private static final AtomicLong BURST_SERVED = new AtomicLong();
    private static final AtomicLong BURST_TRIPS = new AtomicLong();
    private static volatile String lastBurst = "";

    // ------------------------------------------------------------ burst guard

    /**
     * Per-thread lookup counts for anonymous FQNs, hottest first. Plain arrays rather than a map:
     * this runs on a path measured at 113.6k calls/s, so it must not allocate.
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
            System.arraycopy(b.key, 0, b.key, 1, i);
            System.arraycopy(b.count, 0, b.count, 1, i);
            System.arraycopy(b.last, 0, b.last, 1, i);
            b.key[0] = k;
            b.count[0] = c;
            b.last[0] = now;
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
     * @return true when this lookup should be skipped outright and served empty.
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

    /** Feeds the negative cache, and the live verdict, with what the index actually returned. */
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
            // Non-empty keys are tracked too. They can never arm the negative cache (their
            // consecutive-empty count stays 0), but slots 2 and 3 give the report a LIVE
            // empty/non-empty tally -- the fact that was missing when a stale
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

    public static long suppressedLookups() {
        return NEG_SERVED.get();
    }

    public static long burstSuppressedLookups() {
        return BURST_SERVED.get();
    }

    public static long burstTrips() {
        return BURST_TRIPS.get();
    }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nruntime patch\n");
        sb.append("  cut runaway bursts   : ").append(cutBursts ? "ON" : "off")
          .append("      (threshold ").append(BURST_MAX)
          .append(" lookups/key/thread/burst)\n");
        sb.append("  negative lookup cache: ").append(negativeCache ? "ON" : "off").append('\n');
        sb.append("  runaway bursts cut          : ").append(BURST_TRIPS.get())
          .append("   (lookups served empty: ").append(BURST_SERVED.get()).append(")\n");
        if (!lastBurst.isEmpty()) {
            sb.append("  last burst key              : ").append(lastBurst).append('\n');
        }
        sb.append("  empty lookups suppressed    : ").append(NEG_SERVED.get())
          .append("   (keys armed: ").append(NEG_ARMED.get())
          .append(" of ").append(NEG_MAX_KEYS).append(" max, ").append(ZEROS.size())
          .append(" tracked)\n");
        return sb.toString();
    }

    public static void resetCounters() {
        NEG_SERVED.set(0L);
        NEG_ARMED.set(0L);
        BURST_SERVED.set(0L);
        BURST_TRIPS.set(0L);
        lastBurst = "";
        ZEROS.clear();
        Burst b = BURST.get();          // this thread only; per-thread state has no global handle
        java.util.Arrays.fill(b.key, null);
        b.n = 0;
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
