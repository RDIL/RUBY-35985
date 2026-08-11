package rocks.rdil.rubyprobe;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTextArea;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;

/**
 * Read-only monospaced view of the probe state.
 *
 * The point of being a tool window rather than a status bar widget is stability: nothing here
 * resizes or relayouts as values change, the ranked histograms are cumulative rather than
 * instantaneous, and Pause freezes the view outright so a fast-moving readout can still be read.
 */
public final class ProbePanel extends JPanel {

    private final JTextArea text = new JTextArea();
    private final JBCheckBox paused = new JBCheckBox("Pause", false);
    private final JBCheckBox cutCycles = new JBCheckBox("Cut anon cycles", true);
    private final JBCheckBox negativeCache = new JBCheckBox("Suppress empty lookups", true);
    private final JBCheckBox measure = new JBCheckBox("Measure", true);
    private final Timer timer;

    public ProbePanel() {
        super(new BorderLayout());
        ProbeInstaller.installOnce();

        text.setEditable(false);
        text.setLineWrap(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12)));
        text.setBorder(JBUI.Borders.empty(6));

        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> CopyPasteManager.getInstance()
            .setContents(new StringSelection(fullReport())));

        JButton reset = new JButton("Reset counters");
        reset.addActionListener(e -> {
            ProbeInstaller.reset();
            refresh(true);
        });

        // This plugin modifies IDE behaviour, so say so where it cannot be missed -- otherwise later
        // analysis oddities are easy to misattribute to RubyMine itself.
        cutCycles.setSelected(ProbeInstaller.isCutCycles());
        cutCycles.setToolTipText("Skip re-entrant ancestor resolution of an anonymous FQN already "
            + "on the same thread's stack. Uncheck to restore stock RubyMine behaviour.");
        cutCycles.addActionListener(e -> ProbeInstaller.setCutCycles(cutCycles.isSelected()));

        negativeCache.setSelected(ProbeInstaller.isNegativeCache());
        negativeCache.setToolTipText("Serve an empty result for anonymous stub keys measured to "
            + "return nothing, for up to 2s at a time.");
        negativeCache.addActionListener(
            e -> ProbeInstaller.setNegativeCache(negativeCache.isSelected()));

        // The fix is worth keeping on permanently; the instrumentation behind it is not. Turning
        // this off drops the per-resolution bookkeeping while leaving both patches active.
        measure.setToolTipText("Record symbol names, locations and histograms. Turn off to keep the "
            + "patches with none of the measurement overhead.");
        measure.addActionListener(e -> ProbeInstaller.setEnabled(measure.isSelected()));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        controls.add(paused);
        controls.add(copy);
        controls.add(reset);
        controls.add(cutCycles);
        controls.add(negativeCache);
        controls.add(measure);
        controls.setBorder(BorderFactory.createEmptyBorder());

        add(controls, BorderLayout.NORTH);
        add(new JBScrollPane(text), BorderLayout.CENTER);

        timer = new Timer(500, e -> refresh(false));
        timer.setRepeats(true);
        timer.start();
    }

    private String fullReport() {
        return ProbeInstaller.details()
            + "\n---- root symbols ----\n" + ProbeInstaller.roots()
            + "\n---- stub keys ----\n" + ProbeInstaller.keys();
    }

    private void refresh(boolean force) {
        if (!force && paused.isSelected()) {
            return;
        }
        String next = ProbeInstaller.details();
        if (next.equals(text.getText())) {
            return;
        }
        // Hold the viewport steady; otherwise a periodic setText scrolls the panel out from under
        // whatever is being read.
        JScrollBar bar = ((JBScrollPane) ((javax.swing.JViewport) text.getParent()).getParent())
            .getVerticalScrollBar();
        int scroll = bar.getValue();
        text.setText(next);
        bar.setValue(Math.min(scroll, bar.getMaximum()));
    }

    public void dispose() {
        timer.stop();
    }
}
