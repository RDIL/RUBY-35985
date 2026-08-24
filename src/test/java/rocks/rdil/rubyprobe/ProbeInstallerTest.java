package rocks.rdil.rubyprobe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real {@link ProbeInstaller}.
 *
 * The thing under test is boot-jar location, which has failed for real once: IntelliJ's
 * PathClassLoader does not populate a usable CodeSource location, so deriving the path from disk is
 * not dependable and the embedded resource is the primary lookup. The build lands that resource in
 * the main resources output, so the classpath here matches what ships.
 */
class ProbeInstallerTest {

    @Test
    @DisplayName("the boot jar is reachable as a classpath resource")
    void bootJarIsEmbeddedAsAResource() throws Exception {
        try (InputStream in = ProbeInstaller.class.getResourceAsStream("/boot/ruby-probe-boot.jar")) {
            assertNotNull(in, "/boot/ruby-probe-boot.jar is not on the classpath; the plugin would "
                + "fall back to path derivation, which does not work inside the IDE");
            assertTrue(in.readAllBytes().length > 0, "embedded boot jar is empty");
        }
    }

    @Test
    @DisplayName("the bootstrap classes are not reachable from the application classloader")
    void bootstrapClassesAreNotOnTheAppClasspath() {
        // Mirrors the packaging invariant verifyPluginLayout asserts: exactly one copy, on bootstrap.
        assertNull(ProbeInstallerTest.class.getResource("/rocks/rdil/rubyprobe/ProbeState.class"),
            "ProbeState is on the application classpath as well as the boot jar");
        assertNull(ProbeInstallerTest.class.getResource("/rocks/rdil/rubyprobe/ProbePatch.class"),
            "ProbePatch is on the application classpath as well as the boot jar");
    }

    @Test
    @DisplayName("installOnce() reports installed and exposes the patch state")
    void installOnceSucceeds() {
        ProbeInstaller.installOnce();

        assertEquals("installed", ProbeInstaller.status(),
            "installer failed; its status carries the reason");

        String details = ProbeInstaller.details();
        assertTrue(details.contains("runtime patch"),
            "details() should surface the patch block:\n" + details);
        assertTrue(details.contains("boot jar: "),
            "details() should report which boot jar was used:\n" + details);
    }

    @Test
    @DisplayName("the patch toggles round-trip through the installer")
    void togglesRoundTrip() {
        ProbeInstaller.installOnce();
        try {
            ProbeInstaller.setCutBursts(false);
            assertEquals(false, ProbeInstaller.isCutBursts());
            ProbeInstaller.setNegativeCache(false);
            assertEquals(false, ProbeInstaller.isNegativeCache());
        } finally {
            ProbeInstaller.setCutBursts(true);
            ProbeInstaller.setNegativeCache(true);
        }
        assertEquals(true, ProbeInstaller.isCutBursts());
        assertEquals(true, ProbeInstaller.isNegativeCache());
    }
}
