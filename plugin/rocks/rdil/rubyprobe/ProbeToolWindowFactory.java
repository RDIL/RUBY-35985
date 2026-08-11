package rocks.rdil.rubyprobe;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

public final class ProbeToolWindowFactory implements ToolWindowFactory {

    /**
     * Called while tool windows are registered at project open, before the window is ever shown.
     * Installing here means the agent attaches early, which matters: SymbolHierarchy gets loaded by
     * the first analysis pass, and weaving it after that requires a retransform.
     */
    @Override
    public boolean isApplicable(Project project) {
        ProbeInstaller.installOnce();
        return true;
    }

    @Override
    public void init(ToolWindow toolWindow) {
        ProbeInstaller.installOnce();
    }

    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        ProbePanel panel = new ProbePanel();
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel::dispose);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public boolean shouldBeAvailable(Project project) {
        return true;
    }
}
