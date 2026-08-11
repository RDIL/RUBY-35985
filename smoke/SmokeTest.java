import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Exercises exactly the plumbing ProbeInstaller uses -- self attach, bootstrap injection of
 * ProbeState, Advice weaving onto a static 2-arg method and an instance 3-arg method, recursion
 * depth accounting, and the reflective name lookup -- against stand-ins that mirror the real
 * RubyMine signatures.
 */
public class SmokeTest {

    // ---- stand-ins mirroring the real shapes -----------------------------

    public static final class FQN {
        private final String path;
        FQN(String path) { this.path = path; }
        public String getFullPath() { return path; }
    }

    /** stand-ins for the PSI chain location() walks reflectively */
    public static final class VFile {
        public String getPath() { return "/proj/app/models/song.rb"; }
    }

    public static final class PsiFileStub {
        public VFile getVirtualFile() { return new VFile(); }
        public String getName() { return "song.rb"; }
    }

    public static final class Doc {
        public int getTextLength() { return 90000; }
        public int getLineNumber(int offset) { return offset / 100; }
    }

    public static final class ViewProvider {
        public Doc getDocument() { return new Doc(); }
    }

    public static final class PsiFileStub2 {
        public VFile getVirtualFile() { return new VFile(); }
        public String getName() { return "song.rb"; }
        public ViewProvider getViewProvider() { return new ViewProvider(); }
    }

    public static final class Psi {
        public PsiFileStub2 getContainingFile() { return new PsiFileStub2(); }
        public int getTextOffset() { return 41203; }
    }

    public static final class Symbol {
        private final String name;
        public Symbol(String name) { this.name = name; }
        public String getName() { return name; }
        public FQN getFQNWithNesting() { return new FQN("Rap::" + name); }
        public Psi getPsiElement() { return new Psi(); }
    }

    /** mirrors SymbolHierarchy.getAncestorsCaching(Symbol, PsiElement) -- static, 2 args */
    public static final class SymbolHierarchyStub {
        public static List<Object> getAncestorsCaching(Object symbol, Object psi) {
            int d = depth.get();
            if (d < 13) {
                depth.set(d + 1);
                try {
                    new StubIndexStub().getElements(null, null, "Rap::Album::level" + (d + 1));
                    getAncestorsCaching(new Symbol("Concern" + (d + 1)), psi);
                } finally {
                    depth.set(d);
                }
            }
            return Collections.emptyList();
        }
        static final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    }

    /** mirrors RubyStringStubIndexExtension.getElements(Project, SearchScope, String) -- 3 args */
    /** Returns PSI stand-ins so the OnMethodExit location-recovery path is exercised. */
    public static class StubIndexStub {
        public java.util.Collection<Object> getElements(Object project, Object scope, String key) {
            return Collections.singletonList((Object) new Psi());
        }
    }

    // ---- advice, referencing ProbeState by name only ---------------------

    /** Mirrors the shipped advice: direct references to the bootstrap-loaded ProbeState. */
    public static final class AncestorsAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Argument(0) Object symbol) {
            rocks.rdil.rubyprobe.ProbeState.enterAncestors(symbol);
        }
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void exit() {
            rocks.rdil.rubyprobe.ProbeState.exitAncestors();
        }
    }

    public static final class StubKeyAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Argument(2) Object key) {
            rocks.rdil.rubyprobe.ProbeState.stubQuery(key);
        }
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Argument(2) Object key, @Advice.Return Object result) {
            rocks.rdil.rubyprobe.ProbeState.stubResult(key, result);
        }
    }

    // ---- run -------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Instrumentation inst = ByteBuddyAgent.install();
        System.out.println("[ok] self-attach: " + inst);

        File bootJar = new File(args[0]);
        inst.appendToBootstrapClassLoaderSearch(new JarFile(bootJar));
        Class<?> ps = Class.forName("rocks.rdil.rubyprobe.ProbeState", true, null);
        System.out.println("[ok] ProbeState loader = " + ps.getClassLoader() + " (null == bootstrap)");

        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(ElementMatchers.named(SymbolHierarchyStub.class.getName()))
            .transform((b, t, cl, m, pd) -> b.visit(Advice.to(AncestorsAdvice.class)
                .on(ElementMatchers.named("getAncestorsCaching"))))
            .type(ElementMatchers.named(StubIndexStub.class.getName()))
            .transform((b, t, cl, m, pd) -> b.visit(Advice.to(StubKeyAdvice.class)
                .on(ElementMatchers.named("getElements")
                    .and(ElementMatchers.takesArgument(2, String.class)))))
            .installOn(inst);
        System.out.println("[ok] agent installed");

        Method render = ps.getMethod("render");
        Method details = ps.getMethod("details");
        // Exercise the weaving-independent stack sampler too. The stand-ins live outside the real
        // Ruby package, so the run sets -Drubyprobe.pkg to point the sampler at them.
        ps.getMethod("startSampler").invoke(null);

        // Drive it from a thread named like the real daemon pool, in a loop, and sample mid-flight.
        final boolean[] stop = {false};
        // Alternate a named root with an anonymous one, mirroring what the real IDE showed.
        final String anon = "$$ANON$COTE0NTUwOTUz$$";
        Thread worker = new Thread(() -> {
            long i = 0;
            while (!stop[0]) {
                String root = (i++ % 2 == 0) ? "Album" : anon;
                SymbolHierarchyStub.getAncestorsCaching(new Symbol(root), new Object());
            }
        }, "JobScheduler FJ pool 0/7");
        worker.setDaemon(true);
        worker.start();

        String sample = "";
        for (int i = 0; i < 12; i++) {
            Thread.sleep(250);
            sample = String.valueOf(render.invoke(null));
            System.out.println("  render -> " + sample);
        }
        System.out.println();
        System.out.println(details.invoke(null));
        stop[0] = true;

        boolean named = sample.contains("Album") || sample.contains("Concern")
            || sample.contains("$$ANON$");
        boolean deep = sample.matches(".*\\bd\\d+.*");
        String finalDetails = details.invoke(null).toString();
        boolean peaked = sample.matches(".*↑1[0-9].*") || finalDetails.contains("peak 1");
        // The sampler must independently observe recursion depth, without relying on the weaving.
        boolean sampled = finalDetails.matches("(?s).*stack ([1-9]\\d*).*")
            && finalDetails.contains("in (stack)");
        // The whole point of the anonymous path: name it, locate it, and quantify it.
        boolean rootsRanked = finalDetails.contains("most-resolved root symbols")
            && finalDetails.contains(anon);
        // offset 41203 / 100 = line index 412, displayed 1-based as 413
        boolean located = finalDetails.contains("defined at") && finalDetails.contains("song.rb:413");
        boolean declaredAt = finalDetails.contains("declared at") && finalDetails.contains("song.rb:413");
        boolean anonPct =
            finalDetails.matches("(?s).*anonymous roots: \\d+ of \\d+ resolutions \\(\\d+%\\).*");
        System.out.println();
        System.out.println(named ? "[PASS] widget text names the symbol" : "[FAIL] no symbol name");
        System.out.println(deep ? "[PASS] recursion depth reported" : "[FAIL] no depth");
        System.out.println(peaked ? "[PASS] peak depth tracked" : "[FAIL] peak depth not tracked");
        System.out.println(sampled
            ? "[PASS] stack sampler observed depth independently"
            : "[FAIL] stack sampler saw nothing");
        System.out.println(rootsRanked
            ? "[PASS] root histogram ranks the anonymous symbol"
            : "[FAIL] root histogram missing the anonymous symbol");
        System.out.println(located
            ? "[PASS] anonymous symbol resolved to source location"
            : "[FAIL] no source location for anonymous symbol");
        System.out.println(anonPct
            ? "[PASS] anonymous share quantified"
            : "[FAIL] anonymous share not reported");
        System.out.println(declaredAt
            ? "[PASS] stub-index return value yields declaration line"
            : "[FAIL] no declaration site recovered from index results");
        if (!named || !deep || !peaked || !sampled || !rootsRanked || !located || !anonPct
            || !declaredAt) {
            System.exit(1);
        }
    }
}
