package com.kacmedija.claudeassist.agents;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Right-side editor panel for viewing and editing agent prompt content,
 * with a version selector bar at the bottom.
 */
public final class AgentEditorPanel extends JPanel {

    private final Project project;

    private final JBLabel titleLabel;
    private final JBTextArea textArea;
    private JComboBox<AgentVersion> versionCombo;
    private JButton restoreButton;
    private JButton compareButton;
    private final JPanel editorCard;
    private final JPanel emptyCard;
    private final CardLayout cardLayout;

    private @Nullable AgentFile currentAgent;
    private @Nullable String savedContent;
    private boolean modified = false;

    // Callbacks
    private @Nullable Runnable onModifiedChanged;
    private @Nullable Consumer<AgentVersion> onRestoreVersion;
    private @Nullable Consumer<AgentVersion> onCompareVersion;

    private static final String CARD_EDITOR = "editor";
    private static final String CARD_EMPTY = "empty";

    public AgentEditorPanel(@NotNull Project project) {
        super(new CardLayout());
        this.project = project;
        this.cardLayout = (CardLayout) getLayout();

        // ── Editor card ──────────────────────────────────────────
        editorCard = new JPanel(new BorderLayout());

        // Title bar
        titleLabel = new JBLabel("No agent selected");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setBorder(JBUI.Borders.empty(6, 8));
        editorCard.add(titleLabel, BorderLayout.NORTH);

        // Text editor
        textArea = new JBTextArea();
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setEnabled(false);
        textArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkModified(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkModified(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkModified(); }
        });

        JBScrollPane editorScroll = new JBScrollPane(textArea);
        editorScroll.setBorder(JBUI.Borders.empty());
        editorCard.add(editorScroll, BorderLayout.CENTER);

        // Version bar
        editorCard.add(createVersionBar(), BorderLayout.SOUTH);

        // ── Empty card ───────────────────────────────────────────
        emptyCard = new JPanel(new BorderLayout());
        JBLabel emptyLabel = new JBLabel("Select an agent to edit", SwingConstants.CENTER);
        emptyLabel.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        emptyCard.add(emptyLabel, BorderLayout.CENTER);

        // ── Card layout ──────────────────────────────────────────
        add(editorCard, CARD_EDITOR);
        add(emptyCard, CARD_EMPTY);
        cardLayout.show(this, CARD_EMPTY);
    }

    private JPanel createVersionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.setBorder(JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1, 0, 0, 0));

        bar.add(new JBLabel("Versions:"));

        versionCombo = new JComboBox<>();
        versionCombo.setPrototypeDisplayValue(null);
        versionCombo.setPreferredSize(new Dimension(260, 24));
        bar.add(versionCombo);

        restoreButton = new JButton("Restore", AllIcons.Actions.Rollback);
        restoreButton.setEnabled(false);
        restoreButton.addActionListener(e -> {
            AgentVersion version = (AgentVersion) versionCombo.getSelectedItem();
            if (version != null && onRestoreVersion != null) {
                onRestoreVersion.accept(version);
            }
        });
        bar.add(restoreButton);

        compareButton = new JButton("Compare", AllIcons.Actions.Diff);
        compareButton.setEnabled(false);
        compareButton.addActionListener(e -> {
            AgentVersion version = (AgentVersion) versionCombo.getSelectedItem();
            if (version != null && onCompareVersion != null) {
                onCompareVersion.accept(version);
            }
        });
        bar.add(compareButton);

        versionCombo.addActionListener(e -> {
            boolean hasVersion = versionCombo.getSelectedItem() != null;
            restoreButton.setEnabled(hasVersion);
            compareButton.setEnabled(hasVersion);
        });

        return bar;
    }

    // ── Agent Loading ────────────────────────────────────────────

    /**
     * Loads an agent into the editor.
     */
    public void loadAgent(@Nullable AgentFile agent) {
        currentAgent = agent;

        if (agent == null) {
            textArea.setText("");
            textArea.setEnabled(false);
            savedContent = null;
            modified = false;
            titleLabel.setText("No agent selected");
            versionCombo.removeAllItems();
            restoreButton.setEnabled(false);
            compareButton.setEnabled(false);
            cardLayout.show(this, CARD_EMPTY);
            return;
        }

        cardLayout.show(this, CARD_EDITOR);
        textArea.setEnabled(true);
        savedContent = agent.getContent();
        textArea.setText(agent.getContent());
        textArea.setCaretPosition(0);
        modified = false;
        updateTitle();
    }

    /**
     * Refreshes the version dropdown for the current agent.
     */
    public void refreshVersions(@NotNull List<AgentVersion> versions) {
        versionCombo.removeAllItems();
        for (AgentVersion version : versions) {
            versionCombo.addItem(version);
        }
        boolean hasVersions = !versions.isEmpty();
        restoreButton.setEnabled(hasVersions);
        compareButton.setEnabled(hasVersions);
    }

    // ── Content Access ───────────────────────────────────────────

    /**
     * Returns the current text content in the editor.
     */
    @NotNull
    public String getContent() {
        return textArea.getText();
    }

    /**
     * Sets the editor content programmatically (e.g. after AI enhance apply).
     */
    public void setContent(@NotNull String content) {
        textArea.setText(content);
        textArea.setCaretPosition(0);
    }

    /**
     * Marks the content as saved (clears modified state).
     */
    public void markSaved() {
        savedContent = textArea.getText();
        modified = false;
        updateTitle();
    }

    public boolean isModified() {
        return modified;
    }

    @Nullable
    public AgentFile getCurrentAgent() {
        return currentAgent;
    }

    // ── Callbacks ────────────────────────────────────────────────

    public void setOnModifiedChanged(@Nullable Runnable callback) {
        this.onModifiedChanged = callback;
    }

    public void setOnRestoreVersion(@Nullable Consumer<AgentVersion> callback) {
        this.onRestoreVersion = callback;
    }

    public void setOnCompareVersion(@Nullable Consumer<AgentVersion> callback) {
        this.onCompareVersion = callback;
    }

    // ── Internal ─────────────────────────────────────────────────

    private void checkModified() {
        boolean wasModified = modified;
        modified = savedContent != null && !savedContent.equals(textArea.getText());
        if (modified != wasModified) {
            updateTitle();
            if (onModifiedChanged != null) {
                onModifiedChanged.run();
            }
        }
    }

    private void updateTitle() {
        if (currentAgent == null) {
            titleLabel.setText("No agent selected");
        } else {
            String title = currentAgent.getFileName();
            if (modified) {
                title += " *";
            }
            titleLabel.setText(title);
        }
    }
}
