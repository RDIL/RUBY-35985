package rocks.rdil.rubyprobe;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installs the instrumentation once per test JVM, the same way {@link ProbeInstaller} does inside the
 * IDE: self-attach, append the boot jar to the BOOTSTRAP classloader, then weave.
 *
 * The bootstrap classes are deliberately absent from the test runtime classpath (the build puts them
 * on compileClasspath only), so {@code ProbeState}/{@code ProbePatch} can only resolve through the
 * bootstrap loader. If that ever regresses, {@link #bootstrapLoaded()} fails rather than the tests
 * quietly exercising a second copy of the statics.
 */
final class ProbeFixture {

    private ProbeFixture() {
    }

    private static boolean installed;
    private static Class<?> probePatch;
    private static Class<?> probeState;

    static synchronized void install() {
        if (installed) {
            return;
        }
        String bootJar = System.getProperty("rubyprobe.bootJar");
        assertNotNull(bootJar, "rubyprobe.bootJar system property not set by the build");
        File jar = new File(bootJar);
        assertTrue(jar.isFile(), "boot jar missing: " + bootJar);

        try {
            Instrumentation inst = ByteBuddyAgent.install();
            inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));

            probeState = Class.forName("rocks.rdil.rubyprobe.ProbeState", true, null);
            probePatch = Class.forName("rocks.rdil.rubyprobe.ProbePatch", true, null);

            new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named(SymbolHierarchyStub.class.getName()))
                .transform((b, t, cl, m, pd) -> b.visit(
                    Advice.to(AncestorsCutAdvice.class)
                        .on(ElementMatchers.namedOneOf(
                                "getAncestorsCaching", "getAncestorsCachingAlt", "thrower")
                            .and(ElementMatchers.takesArguments(2)))))
                .type(ElementMatchers.named(StubIndexStub.class.getName()))
                .transform((b, t, cl, m, pd) -> b.visit(
                    Advice.to(StubKeyAdvice.class)
                        .on(ElementMatchers.named("getElements")
                            .and(ElementMatchers.takesArgument(2, String.class)))))
                .installOn(inst);

            installed = true;
        } catch (Exception e) {
            throw new IllegalStateException("could not install the probe fixture", e);
        }
    }

    /** True when the bootstrap classloader (and only it) provides the shared state. */
    static boolean bootstrapLoaded() {
        return probeState != null && probeState.getClassLoader() == null
            && probePatch != null && probePatch.getClassLoader() == null;
    }

    // ---- reflective access, because these types live on the bootstrap loader ----

    private static Object call(Class<?> owner, String method, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] instanceof Boolean ? boolean.class : args[i].getClass();
            }
            return owner.getMethod(method, types).invoke(null, args);
        } catch (Exception e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + method + " failed", e);
        }
    }

    static long cuts() {
        return (Long) call(probePatch, "cuts");
    }

    static long ancestorEntries() {
        return (Long) call(probePatch, "ancestorEntries");
    }

    static long suppressedLookups() {
        return (Long) call(probePatch, "suppressedLookups");
    }

    static String report() {
        return String.valueOf(call(probePatch, "report"));
    }

    static void resetCounters() {
        call(probePatch, "resetCounters");
    }

    static void setCutCycles(boolean value) {
        call(probeState, "setCutCycles", Boolean.valueOf(value));
    }

    static void setCutBursts(boolean value) {
        call(probeState, "setCutBursts", Boolean.valueOf(value));
    }

    static long burstSuppressed() {
        return (Long) call(probePatch, "burstSuppressedLookups");
    }

    static long burstTrips() {
        return (Long) call(probePatch, "burstTrips");
    }

    static void setNegativeCache(boolean value) {
        call(probeState, "setNegativeCache", Boolean.valueOf(value));
    }
}
