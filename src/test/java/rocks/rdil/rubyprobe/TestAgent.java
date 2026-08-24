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
 * Weaves the real {@link StubKeyAdvice} onto {@link StubIndexStub}, the same way {@code Installer}
 * weaves it onto RubyStringStubIndexExtension.
 *
 * BurstGuard is appended to the bootstrap loader here too, so the copy the inlined advice reaches is
 * the same one the IDE would use. Nothing in the tests touches BurstGuard directly -- doing so would
 * load a second copy from the application classloader and prove nothing.
 */
final class TestAgent {

    private TestAgent() {
    }

    private static boolean installed;

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
            new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named(StubIndexStub.class.getName()))
                .transform((b, t, cl, m, pd) -> b.visit(
                    Advice.to(StubKeyAdvice.class)
                        .on(ElementMatchers.named("getElements")
                            .and(ElementMatchers.takesArgument(2, String.class)))))
                .installOn(inst);
            installed = true;
        } catch (Exception e) {
            throw new IllegalStateException("could not install the test agent", e);
        }
    }
}
