package rocks.rdil.rubyprobe;

import com.intellij.ide.AppLifecycleListener;

import java.util.List;

/**
 * Installs before any project opens, which matters: get in ahead of the first analysis pass and
 * RubyStringStubIndexExtension is woven as it loads, rather than needing a retransform afterwards.
 *
 * Both hooks are implemented because a headless or frameless startup never calls
 * {@code appFrameCreated}. {@link Installer#installOnce()} is idempotent, so whichever fires first
 * wins and the other is a no-op.
 */
public final class Bootstrap implements AppLifecycleListener {

    @Override
    public void appFrameCreated(List<String> commandLineArgs) {
        Installer.installOnce();
    }

    @Override
    public void appStarted() {
        Installer.installOnce();
    }
}
