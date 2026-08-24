package rocks.rdil.rubyprobe;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/**
 * Installs the instrumentation. Self-attaches via ByteBuddyAgent, which works because RubyMine
 * already ships {@code -Djdk.attach.allowAttachSelf=true} in its VM options -- no vmoptions edit
 * is required.
 *
 * ProbeState is appended to the bootstrap classloader search rather than shipped on the plugin
 * classpath, so there is exactly one copy of it: the instrumented Ruby code and this plugin both
 * see the same statics.
 */
public final class ProbeInstaller {

    private static final String STRING_STUB_INDEX =
        "org.jetbrains.plugins.ruby.ruby.lang.psi.indexes.RubyStringStubIndexExtension";
    private static final String PROBE_STATE = "rocks.rdil.rubyprobe.ProbeState";
    private static final String PLUGIN_ID = "rocks.rdil.ruby-analysis-probe";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile String status = "not installed";
    private static volatile String bootJarPath = "-";
    private static volatile Class<?> probeState;
    private static volatile Method renderMethod;
    private static volatile Method detailsMethod;
    private static volatile Method resetMethod;
    private static volatile Method setEnabledMethod;
    private static volatile Method keysMethod;
    private static volatile Method negativeCacheMethod;
    private static volatile Method isNegativeCacheMethod;
    private static volatile Method cutBurstsMethod;
    private static volatile Method isCutBurstsMethod;

    private ProbeInstaller() {
    }

    public static String status() {
        return status;
    }

    public static synchronized void installOnce() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Instrumentation inst = ByteBuddyAgent.install();

            StringBuilder trace = new StringBuilder();
            File bootJar = materializeBootJar(trace);
            if (bootJar == null || !bootJar.isFile()) {
                status = "failed: boot jar unavailable [" + trace + "]";
                return;
            }
            bootJarPath = bootJar.getAbsolutePath();
            inst.appendToBootstrapClassLoaderSearch(new JarFile(bootJar));

            // Resolve from the bootstrap loader explicitly (null == bootstrap).
            probeState = Class.forName(PROBE_STATE, true, null);
            renderMethod = probeState.getMethod("render");
            detailsMethod = probeState.getMethod("details");
            resetMethod = probeState.getMethod("reset");
            setEnabledMethod = probeState.getMethod("setEnabled", boolean.class);
            keysMethod = probeState.getMethod("keys");

            negativeCacheMethod = probeState.getMethod("setNegativeCache", boolean.class);
            isNegativeCacheMethod = probeState.getMethod("isNegativeCache");

            cutBurstsMethod = probeState.getMethod("setCutBursts", boolean.class);
            isCutBurstsMethod = probeState.getMethod("isCutBursts");

            new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new WeaveListener())
                .type(ElementMatchers.named(STRING_STUB_INDEX))
                .transform((builder, type, loader, module, pd) -> builder.visit(
                    Advice.to(StubKeyAdvice.class)
                        .on(ElementMatchers.named("getElements")
                            .and(ElementMatchers.takesArgument(2, String.class)))))
                .installOn(inst);

            // RETRANSFORMATION is supposed to cover classes already loaded before we attached, but
            // it swallows failures. Do it explicitly so the outcome is reportable.
            forceRetransform(inst);

            // Independent of all weaving.
            probeState.getMethod("startSampler").invoke(null);

            status = "installed";
        } catch (Throwable t) {
            status = "failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Records what the agent actually did, so the tooltip can report weaving rather than guess. */
    private static final class WeaveListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onTransformation(net.bytebuddy.description.type.TypeDescription type,
                                     ClassLoader loader,
                                     net.bytebuddy.utility.JavaModule module,
                                     boolean loaded,
                                     net.bytebuddy.dynamic.DynamicType dynamicType) {
            note("wove " + simple(type.getName()) + (loaded ? " (retransformed)" : " (on load)"));
        }

        @Override
        public void onError(String typeName,
                            ClassLoader loader,
                            net.bytebuddy.utility.JavaModule module,
                            boolean loaded,
                            Throwable throwable) {
            note("ERROR " + simple(typeName) + ": "
                + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static void forceRetransform(Instrumentation inst) {
        for (String target : new String[]{STRING_STUB_INDEX}) {
            Class<?> found = null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (target.equals(c.getName())) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                note(simple(target) + " not yet loaded (will weave on load)");
                continue;
            }
            if (!inst.isModifiableClass(found)) {
                note(simple(target) + " NOT MODIFIABLE");
                continue;
            }
            try {
                inst.retransformClasses(found);
                note(simple(target) + " retransform requested");
            } catch (Throwable t) {
                note(simple(target) + " retransform failed: " + t.getClass().getSimpleName());
            }
        }
    }

    private static String simple(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }

    private static final StringBuilder NOTES = new StringBuilder();

    private static void note(String s) {
        synchronized (NOTES) {
            if (NOTES.length() < 4000) {
                NOTES.append("  ").append(s).append('\n');
            }
        }
    }

    private static String notes() {
        synchronized (NOTES) {
            return NOTES.toString();
        }
    }

    /**
     * Produces a real file for the boot jar. Tried in order:
     *
     * <ol>
     *   <li>the copy embedded in this plugin jar as a resource, unpacked to a temp file -- this is
     *       independent of how the IDE laid the plugin out on disk;</li>
     *   <li>{@code <pluginPath>/boot/ruby-probe-boot.jar} via the plugin descriptor;</li>
     *   <li>the same, derived from this class's CodeSource. IntelliJ's PathClassLoader does not
     *       reliably populate a usable CodeSource location, so this is last.</li>
     * </ol>
     */
    private static File materializeBootJar(StringBuilder trace) {
        // 1. embedded resource
        try (java.io.InputStream in =
                 ProbeInstaller.class.getResourceAsStream("/boot/ruby-probe-boot.jar")) {
            if (in != null) {
                java.nio.file.Path tmp =
                    java.nio.file.Files.createTempFile("ruby-probe-boot", ".jar");
                tmp.toFile().deleteOnExit();
                java.nio.file.Files.copy(in, tmp,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                trace.append("resource=ok");
                return tmp.toFile();
            }
            trace.append("resource=absent");
        } catch (Throwable t) {
            trace.append("resource=").append(t.getClass().getSimpleName());
        }

        // 2. plugin descriptor path
        try {
            com.intellij.ide.plugins.IdeaPluginDescriptor d =
                com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                    com.intellij.openapi.extensions.PluginId.getId(PLUGIN_ID));
            if (d != null && d.getPluginPath() != null) {
                File f = d.getPluginPath().resolve("boot").resolve("ruby-probe-boot.jar").toFile();
                trace.append("; descriptor=").append(f);
                if (f.isFile()) {
                    return f;
                }
            } else {
                trace.append("; descriptor=null");
            }
        } catch (Throwable t) {
            trace.append("; descriptor=").append(t.getClass().getSimpleName());
        }

        // 3. CodeSource
        try {
            URI uri = ProbeInstaller.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
            File self = new File(uri);              // .../lib/ruby-analysis-probe.jar
            File pluginRoot = self.getParentFile().getParentFile();
            File f = new File(new File(pluginRoot, "boot"), "ruby-probe-boot.jar");
            trace.append("; codesource=").append(f);
            if (f.isFile()) {
                return f;
            }
        } catch (Throwable t) {
            trace.append("; codesource=").append(t.getClass().getSimpleName());
        }
        return null;
    }

    // -------------------------------------------------- reflective accessors

    public static String render() {
        Method m = renderMethod;
        if (m == null) {
            return "Ruby probe: " + status;
        }
        try {
            return String.valueOf(m.invoke(null));
        } catch (Throwable t) {
            return "Ruby probe: read error";
        }
    }

    public static String details() {
        Method m = detailsMethod;
        if (m == null) {
            return "Ruby Analysis Probe\nagent status: " + status;
        }
        try {
            return String.valueOf(m.invoke(null))
                + "\nagent status: " + status
                + "\nweaving:\n" + notes()
                + "boot jar: " + bootJarPath;
        } catch (Throwable t) {
            return "Ruby Analysis Probe\nread error: " + t;
        }
    }

    public static String keys() {
        return invokeString(keysMethod);
    }

    private static String invokeString(Method m) {
        if (m == null) {
            return "(agent not installed: " + status + ")\n";
        }
        try {
            return String.valueOf(m.invoke(null));
        } catch (Throwable t) {
            return "(read error: " + t + ")\n";
        }
    }

    public static void reset() {
        Method m = resetMethod;
        if (m != null) {
            try {
                m.invoke(null);
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    public static void setEnabled(boolean value) {
        set(setEnabledMethod, value);
    }

    public static void setNegativeCache(boolean value) {
        set(negativeCacheMethod, value);
    }

    public static void setCutBursts(boolean value) {
        set(cutBurstsMethod, value);
    }

    /** Defaults to true so the checkbox reads correctly before the agent has installed. */
    public static boolean isNegativeCache() {
        return get(isNegativeCacheMethod);
    }

    public static boolean isCutBursts() {
        return get(isCutBurstsMethod);
    }

    private static void set(Method m, boolean value) {
        if (m != null) {
            try {
                m.invoke(null, Boolean.valueOf(value));
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    private static boolean get(Method m) {
        if (m == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(m.invoke(null));
        } catch (Throwable t) {
            return true;
        }
    }
}
