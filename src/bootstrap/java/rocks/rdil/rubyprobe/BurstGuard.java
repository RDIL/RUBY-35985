package rocks.rdil.rubyprobe;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The whole fix.
 *
 * <p><b>The defect.</b> {@code AnonymousDefiningCallType#getAnonymousClassName} builds an anonymous
 * class/module's fully-qualified name as
 * {@code "$$ANON" + ("$C"|"$M") + Base64(Integer.toString(((31*start + end) << 15) ^ text.hashCode())) + "$$"}.
 * The containing file is not part of the identity, so every file whose anonymous defining call has
 * the same text at the same offset collapses into ONE symbol. Eleven files in the project this was
 * written for open with {@code ActiveRecord::Base.class_eval} at offset 0, and all eleven collapse
 * into {@code $$ANON$COTE0NTUwOTUz$$}.
 *
 * <p><b>Why that wedges analysis.</b> The merged symbol resolves to eleven real elements -- not to
 * nothing. {@code SymbolHierarchy.getAncestorsFromAnonymousDefiningCalls} looks the FQN up, then
 * loops over the result and re-enters ancestor resolution once per element. Eleven ways wide, about
 * thirteen deep, and nothing can be memoized because {@code RecursionManager} holds the stack stamp
 * for the whole cycle. The tree does not finish: one measured stall spent 33,669,997 lookups on that
 * single key at roughly 113k/s, with 53% of all CPU samples inside that one call tree.
 *
 * <p><b>The cut.</b> When one anonymous FQN is requested past {@link #MAX} times on one thread with
 * no {@link #QUIET_NS} pause, its lookup is served empty. An empty lookup empties the caller's
 * element loop, so no element is expanded and the recursion ends. There are five orders of magnitude
 * of headroom in that threshold: the busiest key in the same session that was NOT the runaway was
 * requested 225 times. Legitimate repeat resolution of one FQN is bounded by
 * RubySymbolsLookupCache; a rate this high is itself the signature of that caching being defeated.
 *
 * <p><b>Trade-off.</b> A cut lookup means an anonymous class's ancestor list can come back
 * incomplete. Set {@code -Drubyprobe.disabled=true} to turn the whole thing off.
 *
 * <p>Restricted to {@code $$ANON} keys, so ordinary constant paths are never touched. JDK-only: this
 * is appended to the bootstrap classloader so the advice inlined into the Ruby plugin's own
 * classloader can reach exactly one copy of it.
 */
public final class BurstGuard {

    private BurstGuard() {
    }

    /** Marks a synthesized anonymous class/module FQN. The literal in RubyMine's own code. */
    private static final String ANON = "$$ANON";

    private static final boolean ENABLED = !Boolean.getBoolean("rubyprobe.disabled");
    private static final int MAX = intProperty("rubyprobe.burstMax", 512);
    private static final long QUIET_NS = intProperty("rubyprobe.burstQuietMillis", 250) * 1_000_000L;
    /** Distinct anonymous keys tracked per thread. Well above any real hierarchy's fan-out. */
    private static final int TRACKED = 64;

    /**
     * Per-thread lookup counts, hottest first. Plain arrays rather than a map: this sits on a path
     * measured at 113.6k calls/s, so it must not allocate.
     */
    private static final class Burst {
        final String[] key = new String[TRACKED];
        final int[] count = new int[TRACKED];
        final long[] last = new long[TRACKED];
        int n;
    }

    private static final ThreadLocal<Burst> BURST = new ThreadLocal<Burst>() {
        @Override
        protected Burst initialValue() {
            return new Burst();
        }
    };

    private static volatile boolean logged;

    /**
     * @return true when the caller should skip the index lookup entirely and return an empty
     *         collection.
     */
    public static boolean shouldSkipLookup(Object key) {
        if (!ENABLED || !(key instanceof String)) {
            return false;
        }
        String k = (String) key;
        if (!k.contains(ANON)) {
            return false;
        }
        return trip(k);
    }

    /**
     * Move-to-front linear scan: the runaway key is by definition the one being asked for, so it
     * settles at index 0 and the common case is a single reference comparison.
     */
    private static boolean trip(String k) {
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
            if (b.n < TRACKED) {
                b.n++;
            } else {
                i = b.n - 1;                    // evict the coldest slot
            }
            b.key[i] = k;
            b.count[i] = 0;
        } else if (now - b.last[i] > QUIET_NS) {
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
        if (c <= MAX) {
            return false;
        }
        if (!logged) {
            logged = true;
            Logger.getLogger("rubyprobe").log(Level.INFO,
                "ruby-probe: cutting runaway anonymous stub lookups for {0} (past {1} on one thread)",
                new Object[]{k, Integer.valueOf(MAX)});
        }
        return true;
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
