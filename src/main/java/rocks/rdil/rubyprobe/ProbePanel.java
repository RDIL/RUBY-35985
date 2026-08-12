package rocks.rdil.rubyprobe;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.Caret;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
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
    private final JBScrollPane scrollPane = new JBScrollPane(text);
    private final Timer timer;

    /** Compared instead of calling getText() on the whole document every tick. */
    private String lastRendered = "";

    public ProbePanel() {
        super(new BorderLayout());
        ProbeInstaller.installOnce();

        text.setEditable(false);
        text.setLineWrap(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12)));
        text.setBorder(JBUI.Borders.empty(6));
        freezeCaret(text);

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
        add(scrollPane, BorderLayout.CENTER);

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
        if (next.equals(lastRendered)) {
            return;
        }
        lastRendered = next;
        setTextPreservingScroll(text, scrollPane, next);
    }

    /**
     * Stops the caret from dragging the viewport around on every update.
     *
     * {@code setText} is a document remove followed by an insert. A {@link DefaultCaret} on its
     * default {@code UPDATE_WHEN_ON_EDT} policy follows the insert to the end of the new text and then
     * calls {@code scrollRectToVisible} on itself -- end of the last line, so the view snaps to the
     * bottom, and to that line's end, so it snaps right as well. That is the whole "jumps to the
     * bottom right" symptom, and no amount of restoring scrollbars afterwards fixes the cause,
     * because the caret's scroll can be deferred to a later EDT pass and simply wins.
     */
    static void freezeCaret(JTextComponent area) {
        Caret caret = area.getCaret();
        if (caret instanceof DefaultCaret) {
            ((DefaultCaret) caret).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
    }

    /**
     * Replaces the text without moving the viewport, horizontally or vertically.
     *
     * Relies on {@link #freezeCaret} having been applied; the explicit save/restore below is for the
     * remaining case, where the new text is shorter and the scrollbar maxima shrink under the current
     * position.
     */
    static void setTextPreservingScroll(JTextComponent area, JScrollPane pane, String next) {
        JScrollBar vertical = pane.getVerticalScrollBar();
        JScrollBar horizontal = pane.getHorizontalScrollBar();
        int v = vertical.getValue();
        int h = horizontal.getValue();

        area.setText(next);

        restoreScroll(vertical, v);
        restoreScroll(horizontal, h);
        // The maxima only settle once the new text has been laid out, so re-apply after that.
        SwingUtilities.invokeLater(() -> {
            restoreScroll(vertical, v);
            restoreScroll(horizontal, h);
        });
    }

    /** The largest value a scrollbar can actually hold is maximum - visibleAmount, not maximum. */
    static void restoreScroll(JScrollBar bar, int value) {
        int max = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
        bar.setValue(Math.min(value, max));
    }

    public void dispose() {
        timer.stop();
    }
}
