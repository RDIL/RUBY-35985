package rocks.rdil.rubyprobe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared probe state. Appended to the BOOTSTRAP classloader search at runtime so that instrumented
 * code inside the Ruby plugin's classloader can reach it, and so the status bar widget (in our own
 * plugin classloader) can read it reflectively.
 *
 * Everything here must depend on nothing but the JDK.
 *
 * Two independent sources of truth, deliberately:
 *   - the woven hooks (exact, but only as good as the weaving), and
 *   - a stack sampler (approximate, but works even when weaving silently fails).
 * When the two disagree, the sampler is the one to trust.
 */
public final class ProbeState {

    private ProbeState() {
    }

    public static volatile boolean enabled = true;

    /** Resolve fully qualified symbol names at most this often per thread. */
    private static final long NAME_SAMPLE_INTERVAL_NS = 50_000_000L;

    /** A thread is "active" if a hook or the sampler saw it this recently. */
    private static final long STALE_NS = 2_000_000_000L;

    /** Cap on distinct stub keys tracked, so a high-cardinality workload cannot grow unbounded. */
    private static final int MAX_TRACKED_KEYS = 16384;

    private static final String ANCESTORS_METHOD = "getAncestorsCaching";
    /** Overridable so the sampler can be exercised against stand-ins under test. */
    private static final String RUBY_PKG =
        System.getProperty("rubyprobe.pkg", "org.jetbrains.plugins.ruby");

    public static final class Rec {
        final String threadName;
        volatile int depth;
        volatile int maxDepth;
        volatile int peakDepth;
        volatile String rootName = "";
        volatile String rootLocation = "";
        volatile String deepestName = "";
        volatile String rootSymbol = "";
        volatile String deepestSymbol = "";
        volatile int deepestAt;
        volatile String lastStubKey = "";
        volatile long stubQueries;
        volatile long ancestorCalls;
        volatile long lastNameNs;
        volatile long lastActivityNs;

        // ---- filled in by the stack sampler, independent of any weaving ----
        volatile int stackAncestorDepth = -1;
        volatile String stackDeepestRubyFrame = "";
        volatile String stackTopFrame = "";
        volatile long lastSampledNs;
        /** When this thread was first seen continuously inside Ruby frames; 0 when it is not. */
        volatile long rubySinceNs;

        boolean describing;

        Rec(String threadName) {
            this.threadName = threadName;
        }
    }

    private static final ConcurrentHashMap<Long, Rec> RECS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> KEY_COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method[]> ACCESSORS = new ConcurrentHashMap<>();
    /** How often each top-level symbol has been resolved. Cumulative, so it is stable to read. */
    private static final ConcurrentHashMap<String, AtomicLong> ROOT_COUNTS = new ConcurrentHashMap<>();
    /** Source location per root symbol -- the only way to pin down an anonymous ($$ANON$..$$) one. */
    private static final ConcurrentHashMap<String, String> ROOT_LOCATIONS = new ConcurrentHashMap<>();
    /** Declaration sites per stub key, recovered from what the index actually returned. */
    private static final ConcurrentHashMap<String, String> KEY_LOCATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> ZERO_ARG = new ConcurrentHashMap<>();

    private static final ThreadLocal<Rec> TL = new ThreadLocal<Rec>() {
        @Override
        protected Rec initialValue() {
            Thread t = Thread.currentThread();
            Rec r = new Rec(t.getName());
            RECS.put(Long.valueOf(t.getId()), r);
            return r;
        }
    };

    private static Rec recFor(Thread t) {
        Long key = Long.valueOf(t.getId());
        Rec r = RECS.get(key);
        if (r == null) {
            r = new Rec(t.getName());
            Rec prev = RECS.putIfAbsent(key, r);
            if (prev != null) {
                r = prev;
            }
        }
        return r;
    }

    // Note: an earlier version routed the advice through a java.util.function.BiConsumer parked in
    // System.getProperties(), on the theory that the Ruby module classloader could not resolve
    // rocks.rdil.rubyprobe.*. It recorded nothing. Direct references from inlined advice do resolve
    // (StubKeyAdvice has always worked that way), and a Properties table is not a dependable place
    // to leave a non-String value, so the indirection is gone rather than fixed.

    // ---------------------------------------------------------------- hot path

    public static void enterAncestors(Object symbol) {
        if (!enabled) {
            return;
        }
        Rec r = TL.get();
        if (r.describing) {
            return;
        }
        int d = r.depth + 1;
        r.depth = d;
        r.ancestorCalls++;
        long now = System.nanoTime();
        r.lastActivityNs = now;
        if (d == 1) {
            r.maxDepth = 1;
            String root = cheapName(r, symbol);
            r.rootName = root;
            AtomicLong rc = ROOT_COUNTS.get(root);
            if (rc != null) {
                rc.incrementAndGet();
            } else if (ROOT_COUNTS.size() < MAX_TRACKED_KEYS) {
                ROOT_COUNTS.computeIfAbsent(root, k -> new AtomicLong()).incrementAndGet();
            }
            // An anonymous symbol has no usable name, so its source location is the only handle on
            // it. Resolve that once per distinct root rather than on every call.
            if (!ROOT_LOCATIONS.containsKey(root) && ROOT_LOCATIONS.size() < MAX_TRACKED_KEYS) {
                String loc = location(r, symbol);
                ROOT_LOCATIONS.put(root, loc.isEmpty() ? "?" : loc);
                r.rootLocation = loc;
            } else {
                String known = ROOT_LOCATIONS.get(root);
                if (known != null) {
                    r.rootLocation = known;
                }
            }
        }
        if (d > r.maxDepth) {
            r.maxDepth = d;
        }
        if (d > r.peakDepth) {
            r.peakDepth = d;
        }
        if (now - r.lastNameNs > NAME_SAMPLE_INTERVAL_NS) {
            r.lastNameNs = now;
            String desc = describe(r, symbol);
            if (d == 1) {
                r.rootSymbol = desc;
            }
            r.deepestSymbol = desc;
            r.deepestName = cheapName(r, symbol);
            r.deepestAt = d;
        }
    }

    public static void exitAncestors() {
        if (!enabled) {
            return;
        }
        Rec r = TL.get();
        if (r.describing || r.depth <= 0) {
            return;
        }
        r.depth--;
    }

    /** The String key handed to the Ruby stub index -- literally "the thing it is looking for". */
    public static void stubQuery(Object key) {
        if (!enabled) {
            return;
        }
        Rec r = TL.get();
        if (r.describing) {
            return;
        }
        r.stubQueries++;
        r.lastActivityNs = System.nanoTime();
        if (key == null) {
            return;
        }
        String s = key.toString();
        r.lastStubKey = s;
        AtomicLong c = KEY_COUNTS.get(s);
        if (c != null) {
            c.incrementAndGet();
        } else if (KEY_COUNTS.size() < MAX_TRACKED_KEYS) {
            KEY_COUNTS.computeIfAbsent(s, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /**
     * Records where the elements a stub lookup returned are actually declared.
     *
     * Runs at most once per distinct key -- the lookup itself fires >100k/s, but resolving a
     * location is only needed the first time a key is seen.
     */
    public static void stubResult(Object key, Object result) {
        if (!enabled || !(key instanceof String)) {
            return;
        }
        String k = (String) key;
        if (KEY_LOCATIONS.containsKey(k) || KEY_LOCATIONS.size() >= MAX_TRACKED_KEYS) {
            return;
        }
        if (!(result instanceof Iterable)) {
            return;
        }
        Rec r = TL.get();
        if (r.describing) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        int elements = 0;
        boolean truncated = false;
        try {
            for (Object element : (Iterable<?>) result) {
                if (element == null) {
                    continue;
                }
                if (++elements > 256) {
                    truncated = true;           // bounded: this only informs a label
                    break;
                }
                if (n >= 3) {
                    continue;                   // keep counting elements, stop resolving locations
                }
                String loc = location(r, element);
                if (!loc.isEmpty()) {
                    if (n > 0) {
                        sb.append("  |  ");
                    }
                    sb.append(loc);
                    n++;
                }
            }
        } catch (Throwable ignored) {
            // a partial answer is still useful
        }
        // These two used to collapse into one string. They are opposites: "the index found nothing"
        // versus "the index found plenty and none of it could be rendered". Reading the second as
        // the first is what makes a non-empty runaway key look like a negative-lookup problem.
        String label;
        if (elements == 0) {
            label = "(no elements returned)";
        } else if (n == 0) {
            label = "(" + elements + (truncated ? "+" : "") + " element(s), no location resolved)";
        } else if (elements > n) {
            label = sb + "   [" + elements + (truncated ? "+" : "") + " elements total]";
        } else {
            label = sb.toString();
        }
        KEY_LOCATIONS.put(k, label);
    }

    // ------------------------------------------------------------- stack sampler

    private static volatile Thread sampler;

    public static synchronized void startSampler() {
        if (sampler != null) {
            return;
        }
        Thread t = new Thread(ProbeState::sampleLoop, "ruby-probe-sampler");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        sampler = t;
        t.start();
    }

    private static void sampleLoop() {
        while (true) {
            try {
                Thread.sleep(250L);
                if (enabled) {
                    sampleOnce();
                    dumpIfStalled();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                // never let the sampler die on a transient error
            }
        }
    }

    private static void sampleOnce() {
        long now = System.nanoTime();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            String name = t.getName();
            if (!isInteresting(name)) {
                continue;
            }
            StackTraceElement[] st = e.getValue();
            if (st == null || st.length == 0) {
                continue;
            }
            int ancestorFrames = 0;
            String deepestRuby = null;
            for (StackTraceElement f : st) {
                String cn = f.getClassName();
                if (cn.startsWith(RUBY_PKG)) {
                    if (deepestRuby == null) {
                        deepestRuby = shortFrame(f);
                    }
                    if (ANCESTORS_METHOD.equals(f.getMethodName())) {
                        ancestorFrames++;
                    }
                }
            }
            if (deepestRuby == null) {
                Rec existing = RECS.get(Long.valueOf(t.getId()));
                if (existing != null) {
                    existing.rubySinceNs = 0L; // streak broken
                }
                continue; // thread is busy with something unrelated to Ruby
            }
            Rec r = recFor(t);
            if (r.rubySinceNs == 0L) {
                r.rubySinceNs = now;
            }
            r.stackAncestorDepth = ancestorFrames;
            r.stackDeepestRubyFrame = deepestRuby;
            r.stackTopFrame = shortFrame(st[0]);
            r.lastSampledNs = now;
            if (r.lastActivityNs == 0L) {
                r.lastActivityNs = now;
            }
        }
    }

    /** How long a thread must sit continuously in Ruby frames before we consider it stalled. */
    private static final long STALL_NS = secondsProperty("rubyprobe.stallSeconds", 10L);
    /** Minimum gap between dumps. */
    private static final long DUMP_GAP_NS = secondsProperty("rubyprobe.dumpGapSeconds", 15L);

    private static long secondsProperty(String name, long defaultSeconds) {
        try {
            String v = System.getProperty(name);
            if (v != null) {
                return Long.parseLong(v.trim()) * 1_000_000_000L;
            }
        } catch (Throwable ignored) {
            // fall through to the default
        }
        return defaultSeconds * 1_000_000_000L;
    }

    private static volatile long lastDumpNs;

    /**
     * Writes a snapshot to ~/ruby-probe-stall.log when analysis has been wedged for a while.
     *
     * The widget repaints on the EDT, so when the IDE locks up the tooltip stops updating exactly
     * when it matters. This runs on our own daemon thread and keeps recording regardless.
     */
    private static void dumpIfStalled() {
        long now = System.nanoTime();
        long worst = 0L;
        for (Rec r : RECS.values()) {
            long since = r.rubySinceNs;
            if (since != 0L) {
                worst = Math.max(worst, now - since);
            }
        }
        if (worst < STALL_NS) {
            return;
        }
        if (now - lastDumpNs < DUMP_GAP_NS) {
            return;
        }
        lastDumpNs = now;
        try {
            String path = System.getProperty("rubyprobe.stallLog");
            if (path == null) {
                path = System.getProperty("user.home") + "/ruby-probe-stall.log";
            }
            String text = "===== " + java.time.LocalDateTime.now()
                + "  stalled " + (worst / 1_000_000_000L) + "s =====\n"
                + details() + "\n" + roots() + "\n" + keys() + "\n";
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(path), text,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // diagnostics must never break the IDE
        }
    }

    private static boolean isInteresting(String name) {
        return name.startsWith("JobScheduler FJ pool")
            || name.startsWith("DefaultDispatcher-worker")
            || name.startsWith("ApplicationImpl pooled thread")
            || name.equals("AWT-EventQueue-0");
    }

    private static String shortFrame(StackTraceElement f) {
        String cn = f.getClassName();
        int dot = cn.lastIndexOf('.');
        return (dot < 0 ? cn : cn.substring(dot + 1)) + "." + f.getMethodName();
    }

    // ------------------------------------------------------------ name lookup

    private static String describe(Rec r, Object symbol) {
        if (symbol == null) {
            return "(null)";
        }
        r.describing = true;
        try {
            Method[] acc = ACCESSORS.computeIfAbsent(symbol.getClass(), ProbeState::findAccessors);
            if (acc[0] != null) {
                Object fqn = acc[0].invoke(symbol);
                if (fqn != null) {
                    if (acc[2] != null) {
                        Object path = acc[2].invoke(fqn);
                        if (path != null && !path.toString().isEmpty()) {
                            return path.toString();
                        }
                    }
                    if (!fqn.toString().isEmpty()) {
                        return fqn.toString();
                    }
                }
            }
            if (acc[1] != null) {
                Object n = acc[1].invoke(symbol);
                if (n != null && !n.toString().isEmpty()) {
                    return n.toString();
                }
            }
            return "<" + symbol.getClass().getSimpleName() + ">";
        } catch (Throwable t) {
            return "<" + symbol.getClass().getSimpleName() + ">";
        } finally {
            r.describing = false;
        }
    }

    private static String cheapName(Rec r, Object symbol) {
        if (symbol == null) {
            return "(null)";
        }
        r.describing = true;
        try {
            Method[] acc = ACCESSORS.computeIfAbsent(symbol.getClass(), ProbeState::findAccessors);
            if (acc[1] != null) {
                Object n = acc[1].invoke(symbol);
                if (n != null && !n.toString().isEmpty()) {
                    return n.toString();
                }
            }
            return "<" + symbol.getClass().getSimpleName() + ">";
        } catch (Throwable t) {
            return "<" + symbol.getClass().getSimpleName() + ">";
        } finally {
            r.describing = false;
        }
    }

    /**
     * Best-effort {@code path@offset} for the PSI element behind a symbol.
     *
     * For an anonymous symbol -- {@code $$ANON$...$$}, whose name is only an identity hash -- this
     * is the sole way to find the {@code Class.new} / {@code Module.new} in the project that
     * produced it. Reflective throughout so this class stays JDK-only.
     */
    private static String location(Rec r, Object target) {
        if (target == null) {
            return "";
        }
        boolean outer = r.describing;
        r.describing = true;
        try {
            // Accepts either a Symbol (which wraps a PSI element) or a PSI element directly, since
            // the stub index hands back the latter.
            Object psi = target;
            Object file = invoke0(psi, "getContainingFile");
            if (file == null) {
                psi = invoke0(target, "getPsiElement");
                if (psi == null) {
                    return "";
                }
                file = invoke0(psi, "getContainingFile");
            }
            String path = null;
            if (file != null) {
                Object vf = invoke0(file, "getVirtualFile");
                if (vf != null) {
                    Object p = invoke0(vf, "getPath");
                    if (p != null) {
                        path = p.toString();
                    }
                }
                if (path == null) {
                    Object n = invoke0(file, "getName");
                    if (n != null) {
                        path = n.toString();
                    }
                }
            }
            StringBuilder sb = new StringBuilder(path == null ? "?" : path);
            Object off = invoke0(psi, "getTextOffset");
            if (off instanceof Integer) {
                Integer line = lineNumber(file, ((Integer) off).intValue());
                if (line != null) {
                    sb.append(':').append(line.intValue() + 1);
                } else {
                    sb.append('@').append(off);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            r.describing = outer;
        }
    }

    /** PsiFile -> FileViewProvider -> Document -> line, all reflectively. Null if unavailable. */
    private static Integer lineNumber(Object file, int offset) {
        try {
            Object provider = invoke0(file, "getViewProvider");
            Object doc = provider == null ? null : invoke0(provider, "getDocument");
            if (doc == null) {
                return null;
            }
            Object len = invoke0(doc, "getTextLength");
            if (len instanceof Integer && offset > ((Integer) len).intValue()) {
                return null;
            }
            Method m = doc.getClass().getMethod("getLineNumber", int.class);
            m.setAccessible(true);
            Object line = m.invoke(doc, Integer.valueOf(offset));
            return line instanceof Integer ? (Integer) line : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Package-private so {@link ProbePatch} can reach FQNs without a second reflection cache. */
    static Object invoke0(Object target, String method) {
        if (target == null) {
            return null;
        }
        String cacheKey = target.getClass().getName() + '#' + method;
        Method m = ZERO_ARG.get(cacheKey);
        if (m == null) {
            try {
                m = target.getClass().getMethod(method);
                m.setAccessible(true);
                ZERO_ARG.put(cacheKey, m);
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method[] findAccessors(Class<?> c) {
        Method fqn = null;
        Method name = null;
        Method fullPath = null;
        try {
            fqn = c.getMethod("getFQNWithNesting");
            fqn.setAccessible(true);
            try {
                fullPath = fqn.getReturnType().getMethod("getFullPath");
                fullPath.setAccessible(true);
            } catch (Throwable ignored) {
                // fall back to FQN.toString()
            }
        } catch (Throwable ignored) {
            // not a Symbol, or the API moved
        }
        try {
            name = c.getMethod("getName");
            name.setAccessible(true);
        } catch (Throwable ignored) {
            // ignore
        }
        return new Method[]{fqn, name, fullPath};
    }

    // ------------------------------------------------------------- reporting

    private static volatile long lastSampleNs = System.nanoTime();
    private static volatile long lastStubTotal;
    private static volatile long lastAncestorTotal;
    private static volatile double stubRate;
    private static volatile double ancestorRate;

    private static void refreshRates() {
        long now = System.nanoTime();
        long dt = now - lastSampleNs;
        if (dt < 200_000_000L) {
            return;
        }
        long stubs = 0;
        long anc = 0;
        for (Rec r : RECS.values()) {
            stubs += r.stubQueries;
            anc += r.ancestorCalls;
        }
        double secs = dt / 1_000_000_000.0;
        stubRate = (stubs - lastStubTotal) / secs;
        ancestorRate = (anc - lastAncestorTotal) / secs;
        lastStubTotal = stubs;
        lastAncestorTotal = anc;
        lastSampleNs = now;
    }

    /**
     * Threads with recent activity from EITHER source. Deliberately not gated on depth > 0: the
     * ancestor hook may not be woven, and the stub keys are worth showing regardless.
     */
    private static List<Rec> activeRecs() {
        long now = System.nanoTime();
        List<Rec> out = new ArrayList<>();
        for (Rec r : RECS.values()) {
            long seen = Math.max(r.lastActivityNs, r.lastSampledNs);
            if (seen != 0L && (now - seen) < STALE_NS) {
                out.add(r);
            }
        }
        out.sort(Comparator
            .comparingInt((Rec r) -> Math.max(r.stackAncestorDepth, r.maxDepth)).reversed()
            .thenComparing(Comparator.comparingLong((Rec r) -> r.stubQueries).reversed()));
        return out;
    }

    /** Immutable key/count pair so the ranking cannot shift while it is being sorted or printed. */
    public static final class KeyCount {
        public final String key;
        public final long count;

        KeyCount(String key, long count) {
            this.key = key;
            this.count = count;
        }
    }

    private static List<KeyCount> topKeys(int n) {
        return rank(KEY_COUNTS, n);
    }

    private static List<KeyCount> topRoots(int n) {
        return rank(ROOT_COUNTS, n);
    }

    private static List<KeyCount> rank(ConcurrentHashMap<String, AtomicLong> src, int n) {
        List<KeyCount> all = new ArrayList<>(src.size());
        for (Map.Entry<String, AtomicLong> e : src.entrySet()) {
            all.add(new KeyCount(e.getKey(), e.getValue().get()));
        }
        all.sort((a, b) -> Long.compare(b.count, a.count));
        return all.subList(0, Math.min(n, all.size()));
    }

    /** Short status bar text. */
    public static String render() {
        if (!enabled) {
            return "Ruby probe: off";
        }
        refreshRates();
        List<Rec> active = activeRecs();
        if (active.isEmpty()) {
            return "Ruby analysis: idle";
        }
        Rec r = active.get(0);
        StringBuilder sb = new StringBuilder("Ruby: ");

        // Prefer the woven root symbol; fall back to the sampled frame, then the stub key.
        if (!r.rootName.isEmpty()) {
            sb.append(shorten(r.rootName, 28));
        } else if (!r.lastStubKey.isEmpty()) {
            sb.append(shorten(r.lastStubKey, 34));
        } else {
            sb.append(shorten(r.stackDeepestRubyFrame, 30));
        }

        int d = Math.max(r.stackAncestorDepth, r.maxDepth);
        if (d > 0) {
            sb.append("  d").append(d);
            if (r.peakDepth > d) {
                sb.append('↑').append(r.peakDepth);
            }
        }
        if (active.size() > 1) {
            sb.append(" ×").append(active.size());
        }
        if (stubRate > 1) {
            sb.append("  ").append(rate(stubRate)).append(" q/s");
        }
        long cuts = ProbePatch.cuts();
        if (cuts > 0) {
            sb.append("  ✂").append(rate(cuts));
        }
        return sb.toString();
    }

    /** Multi-line tooltip / detail dump. */
    public static String details() {
        refreshRates();
        StringBuilder sb = new StringBuilder();
        sb.append("Ruby Analysis Probe\n");
        sb.append("stub-index queries: ").append(rate(stubRate)).append("/s   ");
        sb.append("ancestor calls: ").append(rate(ancestorRate)).append("/s");
        if (ancestorRate < 1 && stubRate > 1) {
            sb.append(ProbePatch.ancestorEntries() == 0L
                ? "   [ancestor advice never invoked -- using stack sampler]"
                : "   [ancestor advice invoked " + ProbePatch.ancestorEntries()
                    + "x but not counted]");
        }
        sb.append('\n');
        sb.append(ProbePatch.report());

        List<KeyCount> roots = topRoots(10);
        if (!roots.isEmpty()) {
            sb.append("\nmost-resolved root symbols (cumulative)\n");
            for (KeyCount kc : roots) {
                String loc = ROOT_LOCATIONS.get(kc.key);
                sb.append(String.format("  %12d  %-34s %s%n",
                    Long.valueOf(kc.count), shorten(kc.key, 34),
                    loc == null ? "" : shorten(loc, 60)));
            }
            long anon = 0;
            long total = 0;
            for (KeyCount kc : topRoots(Integer.MAX_VALUE)) {
                total += kc.count;
                if (kc.key.startsWith("$$ANON$")) {
                    anon += kc.count;
                }
            }
            if (total > 0) {
                sb.append(String.format(
                    "  anonymous roots: %d of %d resolutions (%.0f%%)%n",
                    Long.valueOf(anon), Long.valueOf(total), Double.valueOf(100.0 * anon / total)));
            }
        }

        List<KeyCount> keys = topKeys(8);
        if (!keys.isEmpty()) {
            sb.append("\nmost-requested stub keys (cumulative)\n");
            for (KeyCount kc : keys) {
                sb.append(String.format("  %12d  %s%n",
                    Long.valueOf(kc.count), shorten(kc.key, 74)));
                String loc = KEY_LOCATIONS.get(kc.key);
                if (loc != null) {
                    sb.append("                declared at ").append(shorten(loc, 100)).append('\n');
                }
                // KEY_LOCATIONS is written once, on a key's first sighting, and never revised. For a
                // key first seen while indexing, it can read "(no elements returned)" forever while
                // every lookup since has returned a full collision set. This line is measured live.
                String verdict = ProbePatch.verdict(kc.key);
                if (!verdict.isEmpty()) {
                    sb.append("                live        ").append(verdict).append('\n');
                }
            }
            if (KEY_COUNTS.size() >= MAX_TRACKED_KEYS) {
                sb.append("  (key table full at ").append(MAX_TRACKED_KEYS)
                  .append(" distinct keys -- counts below are a subset)\n");
            } else {
                sb.append("  distinct keys seen: ").append(KEY_COUNTS.size()).append('\n');
            }
        }

        List<Rec> active = activeRecs();
        if (active.isEmpty()) {
            sb.append("\nNo Ruby analysis activity seen.");
            return sb.toString();
        }
        sb.append('\n');
        for (Rec r : active) {
            sb.append(r.threadName).append('\n');
            if (!r.rootName.isEmpty()) {
                sb.append("    resolving  : ").append(r.rootName);
                if (!r.rootSymbol.isEmpty() && !r.rootSymbol.equals(r.rootName)) {
                    sb.append("   (").append(r.rootSymbol).append(')');
                }
                sb.append('\n');
                String loc = r.rootLocation.isEmpty()
                    ? ROOT_LOCATIONS.get(r.rootName) : r.rootLocation;
                if (loc != null && !loc.isEmpty()) {
                    sb.append("    defined at : ").append(loc).append('\n');
                }
            }
            if (!r.stackDeepestRubyFrame.isEmpty()) {
                sb.append("    in (stack) : ").append(r.stackDeepestRubyFrame).append('\n');
                sb.append("    top frame  : ").append(r.stackTopFrame).append('\n');
            }
            int sd = r.stackAncestorDepth;
            sb.append("    depth      : hook ").append(r.maxDepth)
              .append("  peak ").append(r.peakDepth)
              .append("  stack ").append(sd < 0 ? "n/a" : String.valueOf(sd)).append('\n');
            if (!r.lastStubKey.isEmpty()) {
                sb.append("    last key   : ").append(shorten(r.lastStubKey, 74)).append('\n');
            }
            sb.append("    queries    : ").append(r.stubQueries).append('\n');
        }
        return sb.toString();
    }

    /** Tab-separated dump of the key histogram, for pasting into a report. */
    public static String keys() {
        StringBuilder sb = new StringBuilder("count\tkey\tdeclared_at\n");
        for (KeyCount kc : topKeys(200)) {
            String loc = KEY_LOCATIONS.get(kc.key);
            sb.append(kc.count).append('\t').append(kc.key).append('\t')
              .append(loc == null ? "" : loc).append('\n');
        }
        return sb.toString();
    }

    /** Tab-separated root-symbol histogram with source locations, for pasting into a report. */
    public static String roots() {
        StringBuilder sb = new StringBuilder("count\troot\tlocation\n");
        for (KeyCount kc : topRoots(200)) {
            String loc = ROOT_LOCATIONS.get(kc.key);
            sb.append(kc.count).append('\t').append(kc.key).append('\t')
              .append(loc == null ? "" : loc).append('\n');
        }
        return sb.toString();
    }

    public static void reset() {
        for (Rec r : RECS.values()) {
            r.stubQueries = 0;
            r.ancestorCalls = 0;
            r.maxDepth = r.depth;
            r.peakDepth = r.depth;
        }
        KEY_COUNTS.clear();
        KEY_LOCATIONS.clear();
        ROOT_COUNTS.clear();
        ROOT_LOCATIONS.clear();
        lastStubTotal = 0;
        lastAncestorTotal = 0;
        lastSampleNs = System.nanoTime();
        ProbePatch.resetCounters();
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // The patch toggles are routed through here so the plugin needs only one reflective handle on
    // the bootstrap classloader.

    public static void setCutCycles(boolean value) {
        ProbePatch.cutCycles = value;
    }

    public static boolean isCutCycles() {
        return ProbePatch.cutCycles;
    }

    public static void setNegativeCache(boolean value) {
        ProbePatch.negativeCache = value;
    }

    public static boolean isNegativeCache() {
        return ProbePatch.negativeCache;
    }

    public static void setCutBursts(boolean value) {
        ProbePatch.cutBursts = value;
    }

    public static boolean isCutBursts() {
        return ProbePatch.cutBursts;
    }

    private static String rate(double v) {
        if (v >= 10_000) {
            return String.format("%.1fk", v / 1000.0);
        }
        return String.valueOf((long) v);
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return "…" + s.substring(s.length() - (max - 1));
    }
}
