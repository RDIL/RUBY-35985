package rocks.rdil.rubyprobe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool window used to jump to the bottom right of the text box on every refresh.
 *
 * These tests pin the mechanism rather than the appearance: a caret that follows {@code setText} to
 * the end of the document is what calls {@code scrollRectToVisible} and moves the viewport, so
 * asserting the caret does not move is asserting the scroll does not happen. That needs no layout, no
 * window and no display, which is what makes it a dependable regression test rather than a flaky one.
 *
 * Plain Swing types throughout -- deliberately not JBScrollPane, which would want an Application.
 */
class ProbePanelScrollTest {

    private static final String LONG_TEXT = """
        line one with a fairly long tail so a horizontal scrollbar has something to work with
        line two
        line three
        line four
        """;

    @Test
    @DisplayName("the caret is frozen so document updates cannot scroll the viewport")
    void caretIsFrozen() {
        JTextArea area = new JTextArea();
        assertEquals(DefaultCaret.UPDATE_WHEN_ON_EDT,
            ((DefaultCaret) area.getCaret()).getUpdatePolicy(),
            "precondition: Swing's default policy is the one that causes the jump");

        ProbePanel.freezeCaret(area);

        assertEquals(DefaultCaret.NEVER_UPDATE,
            ((DefaultCaret) area.getCaret()).getUpdatePolicy());
    }

    /**
     * Runs on the EDT, which is not incidental: Swing's default UPDATE_WHEN_ON_EDT policy only follows
     * the insert when the mutation happens on the EDT, and the real refresh is driven by a Swing Timer.
     * Asserting off the EDT would make the unfixed code look fixed.
     */
    private static void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    @Test
    @DisplayName("replacing the text does not drag the caret to the end of the document")
    void caretDoesNotFollowTheInsert() throws Exception {
        JTextArea area = new JTextArea();
        JScrollPane pane = new JScrollPane(area);
        ProbePanel.freezeCaret(area);

        onEdt(() -> ProbePanel.setTextPreservingScroll(area, pane, LONG_TEXT));

        // With the default policy the caret lands at the end, which is precisely what scrolled the
        // view to the bottom right.
        assertNotEquals(area.getDocument().getLength(), area.getCaretPosition(),
            "caret followed the insert to the end of the document; the viewport would follow it");
        assertEquals(0, area.getCaretPosition());
        assertEquals(LONG_TEXT, area.getText(), "the text must still actually update");
    }

    @Test
    @DisplayName("without freezing, the caret does jump -- proving the fix is what prevents it")
    void withoutFreezingTheCaretJumps() throws Exception {
        JTextArea area = new JTextArea();
        JScrollPane pane = new JScrollPane(area);
        // no freezeCaret here

        onEdt(() -> ProbePanel.setTextPreservingScroll(area, pane, LONG_TEXT));

        assertEquals(area.getDocument().getLength(), area.getCaretPosition(),
            "if this no longer jumps, Swing changed and freezeCaret may be redundant");
    }

    @Test
    @DisplayName("a scroll position is restored, clamped to maximum minus the visible extent")
    void scrollRestoreClampsToUsableRange() {
        JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL);
        bar.setValues(0, 20, 0, 100);   // value, extent, min, max

        ProbePanel.restoreScroll(bar, 50);
        assertEquals(50, bar.getValue(), "a position inside the range must be preserved exactly");

        // 95 is past the largest reachable position (100 - 20). The old code clamped against
        // getMaximum() instead, handing the bar a value it could only resolve by pinning to the end.
        ProbePanel.restoreScroll(bar, 95);
        assertEquals(80, bar.getValue());
        assertTrue(bar.getValue() <= bar.getMaximum() - bar.getVisibleAmount());
    }

    @Test
    @DisplayName("shrinking content clamps rather than throwing the position away")
    void shorterTextClampsTheScroll() {
        JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL);
        bar.setValues(0, 100, 0, 100);   // everything visible: nowhere to scroll

        ProbePanel.restoreScroll(bar, 40);

        assertEquals(0, bar.getValue(), "with no scrollable range the only valid position is 0");
    }
}
