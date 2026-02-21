package com.kacmedija.claudeassist.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Modeless dialog for displaying Claude's text responses (Explain, Review, Tests).
 * Features: animated loading spinner, streaming text display, markdown rendering.
 */
public class ClaudeResponseDialog extends DialogWrapper {

    private final JEditorPane editorPane;
    private final JBScrollPane scrollPane;
    private final JBLabel statusLabel;
    private final StringBuilder accumulatedText = new StringBuilder();
    private final Timer renderTimer;
    private volatile boolean dirty = false;

    public ClaudeResponseDialog(@NotNull Project project, @NotNull String title) {
        super(project, false);
        setTitle(title);
        setModal(false);

        editorPane = new JEditorPane();
        editorPane.setContentType("text/html");
        editorPane.setEditable(false);
        editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        editorPane.setBackground(UIUtil.getPanelBackground());

        // Open links in browser
        editorPane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    com.intellij.ide.BrowserUtil.browse(e.getURL().toURI());
                } catch (Exception ignored) {}
            }
        });

        scrollPane = new JBScrollPane(editorPane);

        statusLabel = new JBLabel("Waiting for Claude response...",
            new AnimatedIcon.Default(), SwingConstants.LEFT);

        // Timer coalesces rapid text deltas into periodic re-renders
        renderTimer = new Timer(80, e -> {
            if (dirty) {
                renderMarkdown();
            }
        });

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setPreferredSize(new Dimension(700, 550));

        panel.add(statusLabel, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        Action copyAction = new DialogWrapperAction("Copy") {
            @Override
            protected void doAction(java.awt.event.ActionEvent e) {
                String text;
                synchronized (accumulatedText) {
                    text = accumulatedText.toString();
                }
                if (!text.isEmpty()) {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(text), null);
                    statusLabel.setText("Copied to clipboard.");
                }
            }
        };
        return new Action[]{copyAction, getOKAction()};
    }

    @Override
    protected @NotNull Action getOKAction() {
        Action action = super.getOKAction();
        action.putValue(Action.NAME, "Close");
        return action;
    }

    /**
     * Start streaming mode with animated loading indicator.
     */
    public void setLoading() {
        statusLabel.setIcon(new AnimatedIcon.Default());
        statusLabel.setText("Generating response...");
        synchronized (accumulatedText) {
            accumulatedText.setLength(0);
            dirty = false;
        }
        editorPane.setText(MarkdownRenderer.toHtml(""));
        renderTimer.start();
    }

    /**
     * Append a text chunk during streaming. Thread-safe — can be called from any thread.
     */
    public void appendText(@NotNull String chunk) {
        synchronized (accumulatedText) {
            accumulatedText.append(chunk);
            dirty = true;
        }
    }

    /**
     * Mark the response as complete. Performs a final render with the authoritative text.
     * Must be called on EDT.
     */
    public void setComplete(@Nullable String finalText) {
        renderTimer.stop();

        if (finalText != null && !finalText.isEmpty()) {
            synchronized (accumulatedText) {
                accumulatedText.setLength(0);
                accumulatedText.append(finalText);
            }
        }

        renderMarkdown();
        statusLabel.setIcon(null);
        statusLabel.setText("Complete");
    }

    /**
     * Legacy method for non-streaming callers.
     * Must be called on EDT.
     */
    public void setResponseText(@NotNull String text) {
        setComplete(text);
    }

    public void setError(@NotNull String error) {
        renderTimer.stop();

        synchronized (accumulatedText) {
            accumulatedText.setLength(0);
            accumulatedText.append("**Error:** ").append(error);
        }

        renderMarkdown();
        statusLabel.setIcon(AllIcons.General.Error);
        statusLabel.setText("Failed");
    }

    private void renderMarkdown() {
        String text;
        synchronized (accumulatedText) {
            text = accumulatedText.toString();
            dirty = false;
        }

        // Auto-scroll: check if user is at the bottom before updating
        JScrollBar vsb = scrollPane.getVerticalScrollBar();
        boolean wasAtBottom = (vsb.getValue() + vsb.getVisibleAmount()) >= (vsb.getMaximum() - 20);

        editorPane.setText(MarkdownRenderer.toHtml(text));

        if (wasAtBottom) {
            SwingUtilities.invokeLater(() -> vsb.setValue(vsb.getMaximum()));
        }
    }

    @Override
    protected void dispose() {
        renderTimer.stop();
        super.dispose();
    }
}
