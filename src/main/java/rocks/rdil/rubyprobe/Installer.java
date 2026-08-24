package rocks.rdil.rubyprobe;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Self-attaches the agent and weaves {@link StubKeyAdvice}. Works without a vmoptions edit because
 * RubyMine already ships {@code -Djdk.attach.allowAttachSelf=true}.
 */
public final class Installer {

    private Installer() {
    }

    private static final String STRING_STUB_INDEX =
        "org.jetbrains.plugins.ruby.ruby.lang.psi.indexes.RubyStringStubIndexExtension";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    public static void installOnce() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Instrumentation inst = ByteBuddyAgent.install();

            // BurstGuard goes on the BOOTSTRAP loader, not the plugin classpath. The advice is
            // inlined into a class owned by the Ruby plugin's own classloader, which cannot see our
            // jar; bootstrap is visible from everywhere, and it guarantees exactly one copy of the
            // per-thread state.
            inst.appendToBootstrapClassLoaderSearch(new JarFile(bootJar()));

            new AgentBuilder.Default()
                .disableClassFormatChanges()
                // Covers the target if it is somehow already loaded. Installing from
                // AppLifecycleListener normally gets us in first, so this is the belt to that braces.
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named(STRING_STUB_INDEX))
                .transform((builder, type, loader, module, pd) -> builder.visit(
                    Advice.to(StubKeyAdvice.class)
                        .on(ElementMatchers.named("getElements")
                            .and(ElementMatchers.takesArgument(2, String.class)))))
                .installOn(inst);
        } catch (Throwable t) {
            Logger.getLogger("rubyprobe").log(Level.WARNING, "ruby-probe: install failed", t);
        }
    }

    /**
     * Unpacks the boot jar from this plugin's own jar.
     *
     * Read as a resource rather than resolved on disk on purpose: IntelliJ's PathClassLoader does not
     * reliably populate a usable CodeSource location, so deriving the path from the plugin layout is
     * unreliable in a way that reading the resource is not.
     */
    private static File bootJar() throws Exception {
        try (InputStream in = Installer.class.getResourceAsStream("/boot/ruby-probe-boot.jar")) {
            if (in == null) {
                throw new IllegalStateException("boot/ruby-probe-boot.jar is missing from the plugin jar");
            }
            Path tmp = Files.createTempFile("ruby-probe-boot", ".jar");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp.toFile();
        }
    }
}
