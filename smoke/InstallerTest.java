import java.lang.reflect.Method;

/**
 * Drives the real ProbeInstaller with only the built plugin jar + ByteBuddy on the classpath.
 *
 * This exercises the strategy that actually failed in RubyMine: locating the boot jar. The IntelliJ
 * classes referenced by fallback #2 are deliberately absent from this classpath, so if the embedded
 * resource strategy regresses, the status string will say so rather than silently passing.
 */
public class InstallerTest {

    public static void main(String[] args) throws Exception {
        Class<?> installer = Class.forName("rocks.rdil.rubyprobe.ProbeInstaller");

        installer.getMethod("installOnce").invoke(null);

        String status = String.valueOf(installer.getMethod("status").invoke(null));
        String details = String.valueOf(installer.getMethod("details").invoke(null));

        System.out.println("status  : " + status);
        System.out.println();
        System.out.println(details);
        System.out.println();

        boolean installed = "installed".equals(status);
        System.out.println(installed
            ? "[PASS] installOnce() reported installed"
            : "[FAIL] installOnce() -> " + status);

        // ProbeState must be resolvable from the bootstrap loader, and from nowhere else.
        boolean bootstrap = false;
        try {
            Class<?> ps = Class.forName("rocks.rdil.rubyprobe.ProbeState", true, null);
            bootstrap = (ps.getClassLoader() == null);
            Method render = ps.getMethod("render");
            System.out.println("render  : " + render.invoke(null));
        } catch (Throwable t) {
            System.out.println("ProbeState lookup failed: " + t);
        }
        System.out.println(bootstrap
            ? "[PASS] ProbeState on bootstrap loader"
            : "[FAIL] ProbeState not on bootstrap loader");

        if (!installed || !bootstrap) {
            System.exit(1);
        }
    }
}
